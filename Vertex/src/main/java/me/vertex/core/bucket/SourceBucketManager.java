package me.vertex.core.bucket;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Core Source Bucket logic: config loading, item creation/tagging,
 * zone/base-claim/combat validation, claim-boundary-aware flow computation,
 * and the validate-place-then-charge economic flow.
 *
 * <p><b>Testability.</b> Every native-faction/CombatManager/BaseClaimManager
 * lookup this class needs is injected as a plain functional interface,
 * mirroring {@code ChunkBusterManager}'s exact pattern (itself mirroring
 * {@code ExplosionProtectionListener}'s {@code Predicate<Location>
 * isBaseClaim}): claim queries are injected rather than resolving a live
 * faction service in a unit test.
 * Production wiring (see {@code VertexPlugin}) passes real method
 * references; tests pass lambdas returning canned values, so every zone and
 * flow rule below can be exercised without MockBukkit's world or a running
 * external faction API at all -- only actual block mutation needs a real (mock)
 * {@code World}.
 *
 * <p><b>Why no new database table.</b> A Source Bucket is a physical,
 * permanently-reusable item -- once bought, possession of the item itself
 * *is* the record of ownership, the same way a Wand, a Backpack, or a
 * Chunk Buster needs no server-side "who owns one" ledger. The spec's
 * "one-time purchase, unlocks infinite use" describes what happens to a
 * single physical item (it never gets consumed), not a per-player
 * entitlement the server must remember independently of whether the
 * player still has the item. Nothing in the spec asks for re-buy
 * prevention, a usage log, or any other server-side bookkeeping a table
 * would exist to serve -- so, unlike Base Claims/Shield/Chunk Busters
 * (which all track state no physical item could carry on its own), this
 * phase adds none.
 *
 * <p><b>Item identity.</b> Tagged with a single plain type-marker PDC key
 * ({@code source_bucket_variant} -> the variant's {@link
 * SourceBucketType#id()}), exactly {@code WandManager}'s {@code wand_tier}
 * pattern -- <em>not</em> an instance ID via {@code TrackedItemIds}. Source
 * Buckets are not in the anti-dupe spec's initial target list (Sell/TNT
 * Wands, Blueprints, custom armour), they carry no per-item mutable state
 * that duplication could desync (a Wand's remaining-uses counter is exactly
 * the kind of state that motivates instance IDs; a Source Bucket has
 * nothing analogous -- every copy of the same variant behaves identically
 * forever), and a plain type marker is sufficient for the listener to
 * recognize "this is a Source Bucket of variant X." If a future phase adds
 * per-item state to buckets (a use counter, a durability system, etc.),
 * that would be the point to migrate to {@code TrackedItemIds}, same as
 * this class doc for {@code TrackedItemIds} itself anticipates for Runes.
 */
public final class SourceBucketManager {

    public enum UseResult {
        OK,
        TYPE_DISABLED,
        IN_COMBAT,
        WRONG_ZONE,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS,
        FAILED_PLACEMENT
    }

    private final Plugin plugin;
    private final File file;
    private final NamespacedKey variantKey;

    /** The claim's owning faction id at a location, or {@code FactionsHook.NO_FACTION} for wilderness. */
    private final ToIntFunction<Location> claimFactionIdAt;
    /** The claim's faction tag/name at a location, or null for wilderness -- checked against disabledClaimNames. */
    private final Function<Location, String> claimTagAt;
    /** Whether a location is part of a Base Claim region -- {@code BaseClaimManager::isBaseClaim}. */
    private final Predicate<Location> isBaseClaimAt;
    private final Predicate<UUID> isCombatTagged;

    private volatile Map<String, SourceBucketType> variants = Map.of();
    private volatile Set<String> disabledClaimNames = Set.of();

    public SourceBucketManager(Plugin plugin, ToIntFunction<Location> claimFactionIdAt,
            Function<Location, String> claimTagAt, Predicate<Location> isBaseClaimAt,
            Predicate<UUID> isCombatTagged) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sourcebuckets.yml");
        this.variantKey = new NamespacedKey(plugin, "source_bucket_variant");
        this.claimFactionIdAt = claimFactionIdAt;
        this.claimTagAt = claimTagAt;
        this.isBaseClaimAt = isBaseClaimAt;
        this.isCombatTagged = isCombatTagged;
    }

    // ---- Config ----

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("sourcebuckets.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, SourceBucketType> loaded = new LinkedHashMap<>();
        for (SourceBucketType.PlacedBlock placedBlock : SourceBucketType.PlacedBlock.values()) {
            readBlockSection(config.getConfigurationSection(placedBlock.configKey()), placedBlock, loaded);
        }
        variants = Map.copyOf(loaded);

        disabledClaimNames = Set.copyOf(config.getStringList("disabled-claim-names"));
    }

    private void readBlockSection(ConfigurationSection section, SourceBucketType.PlacedBlock placedBlock,
            Map<String, SourceBucketType> into) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection variant = section.getConfigurationSection(key);
            if (variant == null) {
                continue;
            }
            String id = placedBlock.configKey() + ":" + key.toLowerCase(Locale.ROOT);

            boolean enabled = variant.getBoolean("enabled", true);

            SourceBucketType.FlowPattern pattern =
                    SourceBucketType.FlowPattern.fromConfigKey(variant.getString("flow-pattern", "single-source"));
            if (pattern == null) {
                plugin.getLogger().warning("sourcebuckets.yml: variant '" + id
                        + "' has an unknown flow-pattern, using single-source.");
                pattern = SourceBucketType.FlowPattern.SINGLE_SOURCE;
            }
            int maxDistance = configuredMaxDistance(variant, pattern, id);
            boolean baseClaimOnly = variant.getBoolean("base-claim-only", false);
            boolean combatAllowed = variant.getBoolean("combat-allowed", false);
            double shopPrice = Math.max(0D, variant.getDouble("shop-price", 0D));
            double perUseFee = Math.max(0D, variant.getDouble("per-use-fee", 0D));

            Material defaultMaterial = placedBlock.defaultItemMaterial();
            Material material = Material.matchMaterial(
                    String.valueOf(variant.getString("material", defaultMaterial.name())).toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("sourcebuckets.yml: variant '" + id + "' has an unknown material, using "
                        + defaultMaterial + ".");
                material = defaultMaterial;
            }
            Integer customModelData = variant.contains("custom-model-data") ? variant.getInt("custom-model-data") : null;
            boolean glow = variant.getBoolean("glow", false);
            String name = variant.getString("name", key);
            List<String> lore = variant.getStringList("lore");

            into.put(id, new SourceBucketType(id, enabled, placedBlock, pattern, maxDistance, baseClaimOnly, combatAllowed,
                    shopPrice, perUseFee, material, customModelData, name, lore, glow));
        }
    }

    private int configuredMaxDistance(ConfigurationSection variant, SourceBucketType.FlowPattern pattern, String id) {
        return switch (pattern) {
            case SINGLE_SOURCE -> 0;
            case DOWNWARD -> {
                int configured = variant.getInt("max-depth", 0);
                if (configured < -1) {
                    plugin.getLogger().warning("sourcebuckets.yml: variant '" + id
                            + "' has max-depth below -1, using 0.");
                    yield 0;
                }
                yield configured; // -1 means from the origin down to the world's minimum Y.
            }
            case OUTWARD -> {
                int configured = variant.getInt("max-length", 16);
                if (configured < 1) {
                    plugin.getLogger().warning("sourcebuckets.yml: variant '" + id
                            + "' has max-length below 1, using 1.");
                    yield 1;
                }
                yield configured;
            }
        };
    }

    // ---- Variant accessors ----

    public SourceBucketType variant(String id) {
        return id == null ? null : variants.get(id);
    }

    public List<SourceBucketType> enabledVariants() {
        List<SourceBucketType> result = new ArrayList<>();
        for (SourceBucketType variant : variants.values()) {
            if (variant.enabled()) {
                result.add(variant);
            }
        }
        return result;
    }

    // ---- Item identity ----

    public ItemStack createItem(SourceBucketType variant) {
        ItemStack item = new ItemStack(variant.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(variant.name())));
        List<Component> lore = new ArrayList<>();
        for (String line : variant.lore()) {
            lore.add(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(line)));
        }
        meta.lore(lore);
        if (variant.customModelData() != null) {
            meta.setCustomModelData(variant.customModelData());
        }
        if (variant.glow()) {
            // Fake enchant glow with no gameplay effect, hidden from the
            // tooltip -- same idiom SandBotManager.createGiveItem uses.
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        // Reusable/infinite-use, never consumed -- multiple copies in one
        // inventory would be redundant, so this never stacks (Paper's
        // per-item max-stack-size override, added in the 1.20.5 API).
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, variant.id());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * @return the Source Bucket variant this item is, or null when it is
     *         not one. A variant that has since been *removed* from {@code
     *         sourcebuckets.yml} entirely (as opposed to merely disabled)
     *         also resolves to null here -- an already-owned item simply
     *         stops being recognized, the same limitation {@code
     *         WandManager#tierOf} already has for a removed tier, since
     *         there is genuinely no config left to describe it.
     */
    public SourceBucketType variantOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(variantKey, PersistentDataType.STRING);
        return variant(id);
    }

    // ---- Zone / combat validation ----

    /**
     * The non-economic half of "can this player use this bucket here right
     * now": type enabled, combat gate (per-type, see {@link
     * SourceBucketType#combatAllowed()}), and the zone rules from the spec
     * collapsed into a single {@code WRONG_ZONE} result (SafeZone, WarZone,
     * unclaimed Wilderness, and -- when {@link SourceBucketType#baseClaimOnly()}
     * is set -- a Raid Claim, all read the same to the player: "you can't
     * use this here").
     */
    public UseResult validateZoneAndCombat(Player player, Location target, SourceBucketType variant) {
        if (!variant.enabled()) {
            return UseResult.TYPE_DISABLED;
        }
        if (!variant.combatAllowed() && isCombatTagged.test(player.getUniqueId())) {
            return UseResult.IN_COMBAT;
        }
        if (!isValidZone(target, variant)) {
            return UseResult.WRONG_ZONE;
        }
        return UseResult.OK;
    }

    /**
     * True only inside a valid faction claim (never Wilderness), never a
     * disabled claim (SafeZone/WarZone, matched case-insensitively by tag --
     * identical mechanism to {@code ChunkBusterManager}/{@code
     * FactionsHook.isDisabledClaim}), and -- when {@code baseClaimOnly} is
     * set -- only inside that claim's Base Claim region specifically.
     * Deliberately does <em>not</em> check faction ownership against the
     * player: unlike Chunk Busters, Source Buckets are usable by everyone
     * in anyone's valid claim, per spec ("usable by everyone, no faction
     * permission").
     */
    private boolean isValidZone(Location location, SourceBucketType variant) {
        String tag = claimTagAt.apply(location);
        if (tag != null && disabledClaimNames.stream().anyMatch(tag::equalsIgnoreCase)) {
            return false;
        }
        if (claimFactionIdAt.applyAsInt(location) == FactionsHook.NO_FACTION) {
            return false;
        }
        return !variant.baseClaimOnly() || isBaseClaimAt.test(location);
    }

    // ---- Flow computation ----

    /**
     * Computes, in placement order, every block position a use of this
     * bucket at {@code origin} would actually occupy -- stopping at the
     * first position that would leave the origin's claim (per the rules
     * below) or hit an obstruction. Purely a read: no block is mutated, so
     * this can be safely called ahead of any world change to decide
     * success/failure (an empty result is a failed placement) before
     * committing anything, and to gate the balance check on that outcome
     * per the spec's "validate placement, validate balance, perform
     * placement, then charge" ordering.
     *
     * <p><b>Claim-boundary rule (the correctness-critical part of this
     * phase).</b> Every candidate position must, independently:
     * <ul>
     *   <li>be inside a claim at all (never Wilderness/unclaimed land);
     *   <li>belong to the <em>same</em> faction's claim as {@code origin}
     *       -- any change of claiming faction id stops the flow
     *       immediately, unconditionally, regardless of ally/enemy/neutral
     *       relationship (there is no relationship check here at all --
     *       the id comparison alone is what enforces "always stop, no
     *       exceptions based on relationship");
     *   <li>not be a disabled claim (SafeZone/WarZone, by tag) -- checked
     *       explicitly and independently of the faction-id comparison
     *       above (belt-and-braces: a same-named/re-used faction id could
     *       theoretically coincide, and the spec calls out SafeZone/WarZone
     *       as their own explicit stopping rule, not merely an implication
     *       of the ally/enemy rule);
     *   <li>still satisfy {@code baseClaimOnly}, if set, at *every* step --
     *       not just at the origin -- so a variant restricted to Base
     *       Claims can never drift into an adjoining Raid Claim of the same
     *       faction either.
     * </ul>
     * A chunk boundary is never itself a stopping condition -- only these
     * per-block claim checks are. This is particularly important for an
     * OUTWARD flow, which can cross chunks and therefore must validate
     * every block instead of trusting the origin's claim.
     *
     * <p><b>Obstruction rule.</b> A position stops the flow (without being
     * added to the result) when its current block is neither air nor
     * already the same block material this variant places. This deliberately
     * does not overwrite replaceable blocks such as tall grass or snow
     * layers; only air and an existing matching block are valid.
     */
    List<Location> computeFlowPositions(Location origin, SourceBucketType variant) {
        return computeFlowPositions(origin, null, variant);
    }

    /**
     * Computes placement positions for a use. {@code outwardFace} is only
     * required for OUTWARD variants and is the face the player clicked.
     */
    List<Location> computeFlowPositions(Location origin, BlockFace outwardFace, SourceBucketType variant) {
        List<Location> positions = new ArrayList<>();
        int originFactionId = claimFactionIdAt.applyAsInt(origin);
        Material placedMaterial = variant.placedBlock().blockMaterial();
        int maxSteps = switch (variant.flowPattern()) {
            case SINGLE_SOURCE -> 0;
            case DOWNWARD -> variant.maxDistance() < 0
                    ? Math.max(0, origin.getBlockY() - origin.getWorld().getMinHeight())
                    : variant.maxDistance();
            case OUTWARD -> variant.maxDistance() - 1;
        };

        if (variant.flowPattern() == SourceBucketType.FlowPattern.OUTWARD
                && (outwardFace == null || (outwardFace.getModX() == 0
                && outwardFace.getModY() == 0 && outwardFace.getModZ() == 0))) {
            return positions;
        }

        for (int step = 0; step <= maxSteps; step++) {
            Location candidate = switch (variant.flowPattern()) {
                case SINGLE_SOURCE -> origin.clone();
                case DOWNWARD -> origin.clone().add(0, -step, 0);
                case OUTWARD -> origin.clone().add(
                        outwardFace.getModX() * step,
                        outwardFace.getModY() * step,
                        outwardFace.getModZ() * step);
            };
            if (!staysWithinClaimBoundary(candidate, originFactionId, variant)) {
                break;
            }
            Block block = candidate.getBlock();
            Material current = block.getType();
            if (!(current.isAir() || current == placedMaterial)) {
                break; // obstruction: never overwrite/push through it
            }
            positions.add(candidate);
        }
        return positions;
    }

    private boolean staysWithinClaimBoundary(Location location, int originFactionId, SourceBucketType variant) {
        int factionId = claimFactionIdAt.applyAsInt(location);
        if (factionId == FactionsHook.NO_FACTION) {
            return false; // never enter unclaimed land
        }
        if (factionId != originFactionId) {
            return false; // any faction-boundary crossing stops the flow, regardless of relationship
        }
        String tag = claimTagAt.apply(location);
        if (tag != null && disabledClaimNames.stream().anyMatch(tag::equalsIgnoreCase)) {
            return false; // SafeZone/WarZone boundary
        }
        return !variant.baseClaimOnly() || isBaseClaimAt.test(location);
    }

    // ---- Full use: validate -> place -> charge-only-after-success ----

    /**
     * The full use flow, in the exact order the spec requires -- a
     * deliberate departure from this codebase's usual debit-first-refund-
     * on-failure idiom (see {@code AuctionManager.list}), safe here only
     * because everything below runs synchronously on the calling thread
     * with no async gap between checking and placing:
     *
     * <ol>
     *   <li>Zone/combat validation ({@link #validateZoneAndCombat}) --
     *       fails fast with no world read at all.
     *   <li>Flow computation ({@link #computeFlowPositions}, read-only) --
     *       an empty result means the origin itself is obstructed, i.e. a
     *       failed placement; nothing is charged.
     *   <li>Balance validation ({@code Economy.has}) -- only reached once
     *       placement is already known to be possible, per "validate
     *       placement, validate balance" in that order.
     *   <li>The actual placement mutation.
     *   <li>The charge ({@code Economy.withdrawPlayer}), only after every
     *       block above was actually placed.
     * </ol>
     *
     * <p>A variant with {@code perUseFee() <= 0} skips the economy checks
     * entirely (no {@code NO_ECONOMY}/{@code INSUFFICIENT_FUNDS} is
     * possible for a free bucket).
     */
    public UseResult use(Player player, Location target, SourceBucketType variant) {
        return use(player, target, null, variant);
    }

    /** Uses a bucket, following {@code outwardFace} for an OUTWARD variant. */
    public UseResult use(Player player, Location target, BlockFace outwardFace, SourceBucketType variant) {
        UseResult zoneResult = validateZoneAndCombat(player, target, variant);
        if (zoneResult != UseResult.OK) {
            return zoneResult;
        }

        List<Location> positions = computeFlowPositions(target, outwardFace, variant);
        if (positions.isEmpty()) {
            return UseResult.FAILED_PLACEMENT;
        }

        boolean charges = variant.perUseFee() > 0D;
        if (charges) {
            if (!EconomyHook.isAvailable()) {
                return UseResult.NO_ECONOMY;
            }
            Economy economy = EconomyHook.getEconomy();
            if (!economy.has(player, variant.perUseFee())) {
                return UseResult.INSUFFICIENT_FUNDS;
            }
        }

        Material placedMaterial = variant.placedBlock().blockMaterial();
        for (Location position : positions) {
            position.getBlock().setType(placedMaterial);
        }

        if (charges) {
            EconomyResponse response = EconomyHook.getEconomy().withdrawPlayer(player, variant.perUseFee());
            if (!response.transactionSuccess()) {
                // The has() check above passed moments earlier on the same
                // thread with no gap for the balance to change; if the
                // withdrawal still somehow fails (a misbehaving economy
                // plugin), the placement has already happened and is not
                // rolled back -- there is nothing left to undo safely, and
                // the world state (what the player actually sees) is what
                // matters most here. Logged so an admin can investigate a
                // broken economy provider.
                plugin.getLogger().warning("A Source Bucket use placed its blocks but the follow-up charge of "
                        + variant.perUseFee() + " on " + player.getName() + " failed: " + response.errorMessage);
            }
        }
        return UseResult.OK;
    }
}
