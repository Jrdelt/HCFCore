package me.vertex.core.chunkbuster;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.performance.PerformanceManager;
import me.vertex.core.spawner.SpawnerManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.logging.Level;

/**
 * Core Chunk Buster logic: zone/claim/combat/role-permission validation,
 * protected-block filtering, batched block removal, the persisted
 * restart-safe processing lock, and the shop-purchasable custom item.
 *
 * <p><b>Testability.</b> Every native-faction/CombatManager lookup this
 * class needs is injected as a plain functional interface, exactly the
 * pattern {@code ExplosionProtectionListener} uses for its {@code
 * Predicate<Location> isBaseClaim}; production wiring (see {@code VertexPlugin})
 * passes real method references; tests pass lambdas returning canned
 * values, so {@link #validate} can be exercised without MockBukkit's world
 * or a running faction service at all.
 *
 * <p><b>The persisted restart-safe lock.</b> See {@link ChunkBusterStorage}'s
 * class doc for why {@code chunk_buster_operations} exists; this class is
 * what writes/deletes that row (via {@link #beginOperation}/{@link
 * #finishOperation}) and what recovers it on startup ({@link
 * #recoverAbandonedOperations()}). The in-memory {@link #activeLocks} set
 * mirrors the same lifetime but needs no separate "clear on startup" step
 * of its own -- a fresh {@code ChunkBusterManager} always starts with an
 * empty set, so the durable row's deletion during recovery is the only
 * "lock clearing" that actually matters across a restart.
 *
 * <p><b>Batching.</b> {@link #beginOperation} precomputes every position to
 * remove (a plain in-memory queue -- see {@link ChunkBusterArea}, at most
 * ~98,000 entries for a full -64..320 chunk) and drains it in fixed-size
 * batches on a {@code runTaskTimer}, mirroring {@code MineRegenQueue}'s
 * bounded-drain shape. Defaults (1000 blocks/tick, every tick) clear a
 * full chunk in ~100 ticks, roughly 5 seconds -- see {@code
 * chunkbuster.yml}'s {@code processing} section for the exact reasoning.
 */
public final class ChunkBusterManager {

    public enum UseResult {
        OK,
        TYPE_DISABLED,
        IN_COMBAT,
        BLOCKED_ZONE,
        NOT_YOUR_CLAIM,
        NO_ROLE_PERMISSION,
        AREA_BUSY,
        ITEM_MISSING,
        PERSIST_FAILED
    }

    /** Faction roles a permission may be set for -- matches {@code RallyManager.roleId}'s four buckets exactly. */
    public static final List<String> ROLES = List.of("admin", "mod", "member", "recruit");

    private record TypeConfig(boolean enabled, double price, Integer customModelData,
            String name, List<String> lore) {
    }

    private record ChunkAreaKey(String world, int chunkX, int chunkZ) {
    }

    private final Plugin plugin;
    private final ChunkBusterStorage storage;
    private final SpawnerManager spawners;
    private final File file;
    private final NamespacedKey typeKey;

    /** The claim's owning faction id at a location, or {@code FactionsHook.NO_FACTION} for wilderness. */
    private final ToIntFunction<Location> claimFactionIdAt;
    /** The claim's faction tag/name at a location, or null for wilderness -- checked against disabledClaimNames. */
    private final Function<Location, String> claimTagAt;
    private final ToIntFunction<Player> playerFactionId;
    private final Function<Player, String> playerRoleId;
    private final Predicate<UUID> isCombatTagged;
    /**
     * The live faction-rank permission check. Production wires this to the
     * same matrix used by /f permissions; the nullable fallback keeps old
     * persisted Chunk Buster rows readable for existing installations and
     * unit tests that construct this manager directly.
     */
    private final Predicate<Player> factionPermission;

    private volatile Map<ChunkBusterType, TypeConfig> typeConfigs = Map.of();
    private volatile Set<Material> protectedMaterials = Set.of();
    private volatile Set<String> disabledClaimNames = Set.of();
    /** Legacy fallback only; production delegates access to RallyManager's /f permissions action. */
    private volatile Map<String, Boolean> defaultRolePermissions = Map.of();
    private volatile int blocksPerTick = 1000;
    private volatile long periodTicks = 1L;
    private volatile PerformanceManager performance = PerformanceManager.disabled();

    /** Legacy per-faction rows, retained only so an old database remains readable. */
    private final Map<Integer, Map<String, Boolean>> rolePermissions = new ConcurrentHashMap<>();
    /** Chunks currently mid-operation; checked by BlockPlaceEvent/BlockBreakEvent handlers. */
    private final Set<ChunkAreaKey> activeLocks = ConcurrentHashMap.newKeySet();

    public ChunkBusterManager(Plugin plugin, ChunkBusterStorage storage, SpawnerManager spawners,
            ToIntFunction<Location> claimFactionIdAt, Function<Location, String> claimTagAt,
            ToIntFunction<Player> playerFactionId, Function<Player, String> playerRoleId,
            Predicate<UUID> isCombatTagged) {
        this(plugin, storage, spawners, claimFactionIdAt, claimTagAt, playerFactionId, playerRoleId,
                isCombatTagged, null);
    }

    public ChunkBusterManager(Plugin plugin, ChunkBusterStorage storage, SpawnerManager spawners,
            ToIntFunction<Location> claimFactionIdAt, Function<Location, String> claimTagAt,
            ToIntFunction<Player> playerFactionId, Function<Player, String> playerRoleId,
            Predicate<UUID> isCombatTagged, Predicate<Player> factionPermission) {
        this.plugin = plugin;
        this.storage = storage;
        this.spawners = spawners;
        this.file = new File(plugin.getDataFolder(), "chunkbuster.yml");
        this.typeKey = new NamespacedKey(plugin, "chunk_buster_type");
        this.claimFactionIdAt = claimFactionIdAt;
        this.claimTagAt = claimTagAt;
        this.playerFactionId = playerFactionId;
        this.playerRoleId = playerRoleId;
        this.isCombatTagged = isCombatTagged;
        this.factionPermission = factionPermission;
    }

    /**
     * Wired once from {@code VertexPlugin}; left at {@link PerformanceManager#disabled()}
     * for any caller (including every unit test) that never sets one, so
     * this is purely additive and never required for correctness.
     */
    public void setPerformanceManager(PerformanceManager performance) {
        this.performance = performance == null ? PerformanceManager.disabled() : performance;
    }

    // ---- Config ----

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("chunkbuster.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<ChunkBusterType, TypeConfig> types = new EnumMap<>(ChunkBusterType.class);
        ConfigurationSection typesSection = config.getConfigurationSection("types");
        for (ChunkBusterType type : ChunkBusterType.values()) {
            ConfigurationSection section = typesSection == null ? null : typesSection.getConfigurationSection(type.configKey());
            types.put(type, readTypeConfig(type, section));
        }
        typeConfigs = types;

        Set<Material> protectedSet = EnumSet.noneOf(Material.class);
        for (String raw : config.getStringList("protected-blocks")) {
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("chunkbuster.yml: protected-blocks lists unknown material '" + raw + "', ignoring it.");
                continue;
            }
            protectedSet.add(material);
        }
        protectedMaterials = protectedSet;

        disabledClaimNames = Set.copyOf(config.getStringList("disabled-claim-names"));

        Map<String, Boolean> defaults = new java.util.HashMap<>();
        for (String role : ROLES) {
            defaults.put(role, config.getBoolean("role-permissions.defaults." + role, "admin".equals(role) || "mod".equals(role)));
        }
        defaultRolePermissions = Map.copyOf(defaults);

        blocksPerTick = Math.max(1, config.getInt("processing.blocks-per-tick", 1000));
        periodTicks = Math.max(1L, config.getLong("processing.period-ticks", 1L));
        performance.registerScheduledTask("chunkbuster.batch-removal", periodTicks);
    }

    private TypeConfig readTypeConfig(ChunkBusterType type, ConfigurationSection section) {
        if (section == null) {
            return new TypeConfig(true, 0D, null, type.name(), List.of());
        }
        boolean enabled = section.getBoolean("enabled", true);
        double price = Math.max(0D, section.getDouble("price", 0D));
        Integer customModelData = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;
        String name = section.getString("name", type.name());
        List<String> lore = section.getStringList("lore");
        return new TypeConfig(enabled, price, customModelData, name, lore);
    }

    /** Rebuilds legacy role rows from durable storage. Must run after {@link #load()}. */
    public void loadState() {
        rolePermissions.clear();
        try {
            for (ChunkBusterStorage.RolePermissionRow row : storage.loadAllRolePermissions()) {
                rolePermissions.computeIfAbsent(row.factionId(), k -> new ConcurrentHashMap<>())
                        .put(row.role(), row.allowed());
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Chunk Buster role permissions; starting with defaults only.", error);
        }
    }

    /**
     * Finds any operation row left over from before the last restart (a
     * completed operation always deletes its own row), logs it, and
     * deletes it -- never resumed, per spec. Must run once during {@code
     * onEnable()} before any player can start a new operation.
     */
    public void recoverAbandonedOperations() {
        try {
            List<ChunkBusterStorage.OperationRow> rows = storage.loadOperations();
            for (ChunkBusterStorage.OperationRow row : rows) {
                plugin.getLogger().warning("Abandoning an unfinished Chunk Buster operation from before the last "
                        + "restart: " + row.type() + " in " + row.world() + " chunk (" + row.chunkX() + ", "
                        + row.chunkZ() + "). Blocks already removed before the restart stay removed; the rest of "
                        + "the area is left as-is and the operation will not resume.");
                storage.deleteOperation(row.id());
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to recover Chunk Buster operations after a restart.", error);
        }
    }

    // ---- Type config accessors ----

    public boolean isEnabled(ChunkBusterType type) {
        TypeConfig config = typeConfigs.get(type);
        return config != null && config.enabled();
    }

    public double price(ChunkBusterType type) {
        TypeConfig config = typeConfigs.get(type);
        return config == null ? 0D : config.price();
    }

    public String displayName(ChunkBusterType type) {
        TypeConfig config = typeConfigs.get(type);
        return config == null ? type.name() : config.name();
    }

    public List<ChunkBusterType> enabledTypes() {
        List<ChunkBusterType> result = new ArrayList<>();
        for (ChunkBusterType type : ChunkBusterType.values()) {
            if (isEnabled(type)) {
                result.add(type);
            }
        }
        return result;
    }

    // ---- Item identity ----

    public ItemStack createItem(ChunkBusterType type) {
        TypeConfig config = typeConfigs.getOrDefault(type, new TypeConfig(true, 0D, null, type.name(), List.of()));
        // Chunk Busters deliberately always use the same glowing magma-block
        // item. Their type is carried by PDC and their display name, not by a
        // different vanilla material, so the raiding shop is immediately
        // recognisable and the configured type cannot be spoofed by a lookalike.
        ItemStack item = new ItemStack(Material.MAGMA_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(config.name())));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : config.lore()) {
            lore.add(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(line)));
        }
        meta.lore(lore);
        if (config.customModelData() != null) {
            meta.setCustomModelData(config.customModelData());
        }
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }

    /** @return the Chunk Buster type this item is, or null when it is not one (or is disabled). */
    public ChunkBusterType typeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return ChunkBusterType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- Protected blocks ----

    /** Bedrock is always protected, on top of whatever chunkbuster.yml lists -- confirmation can never override this. */
    public boolean isProtected(Material material) {
        return material == Material.BEDROCK || protectedMaterials.contains(material);
    }

    // ---- Spawner presence (drives the flashy confirmation-GUI variant) ----

    public boolean hasTrackedSpawners(Location target) {
        World world = target.getWorld();
        return world != null && !spawners.getSpawnersInChunk(world.getChunkAt(target)).isEmpty();
    }

    // ---- Area lock (checked by BlockPlaceEvent/BlockBreakEvent) ----

    public boolean isLocked(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        return activeLocks.contains(new ChunkAreaKey(world.getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4));
    }

    // ---- Validation, shared by initial item-use and confirm-time revalidation ----

    public UseResult validate(Player player, Location target, ChunkBusterType type) {
        return validate(player, target, type, true);
    }

    private UseResult validate(Player player, Location target, ChunkBusterType type, boolean checkLock) {
        if (!isEnabled(type)) {
            return UseResult.TYPE_DISABLED;
        }
        if (isCombatTagged.test(player.getUniqueId())) {
            return UseResult.IN_COMBAT;
        }
        String claimTag = claimTagAt.apply(target);
        if (claimTag != null && disabledClaimNames.stream().anyMatch(claimTag::equalsIgnoreCase)) {
            return UseResult.BLOCKED_ZONE;
        }
        int claimFactionId = claimFactionIdAt.applyAsInt(target);
        if (claimFactionId != FactionsHook.NO_FACTION) {
            int playerFaction = playerFactionId.applyAsInt(player);
            if (playerFaction == FactionsHook.NO_FACTION || playerFaction != claimFactionId) {
                return UseResult.NOT_YOUR_CLAIM;
            }
            if (!hasFactionPermission(player, playerFaction)) {
                return UseResult.NO_ROLE_PERMISSION;
            }
        }
        // Wilderness (claimFactionId == NO_FACTION): no faction-permission check at all, per spec.
        if (checkLock && isLocked(target)) {
            return UseResult.AREA_BUSY;
        }
        return UseResult.OK;
    }

    private boolean hasRolePermission(int factionId, String role) {
        Boolean explicit = rolePermissions.getOrDefault(factionId, Map.of()).get(role);
        if (explicit != null) {
            return explicit;
        }
        return defaultRolePermissions.getOrDefault(role, false);
    }

    private boolean hasFactionPermission(Player player, int factionId) {
        return factionPermission != null
                ? factionPermission.test(player)
                : hasRolePermission(factionId, playerRoleId.apply(player));
    }

    public boolean rolePermission(int factionId, String role) {
        return hasRolePermission(factionId, role);
    }

    /** @return false if {@code role} isn't one of {@link #ROLES}. */
    public boolean setRolePermission(int factionId, String role, boolean allowed) {
        if (!ROLES.contains(role)) {
            return false;
        }
        rolePermissions.computeIfAbsent(factionId, k -> new ConcurrentHashMap<>()).put(role, allowed);
        try {
            storage.upsertRolePermission(factionId, role, allowed);
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Chunk Buster role-permission change.", error);
        }
        return true;
    }

    // ---- Confirm + execute ----

    /**
     * Full revalidation (item still held, combat, zone, permission, area
     * lock) followed by consuming the item and starting batched removal.
     * Called only from the confirmation GUI's confirm button -- never at
     * initial item-use time, since that only opens the GUI.
     */
    public UseResult confirmAndExecute(Player player, Location target, ChunkBusterType type) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (typeOf(held) != type) {
            return UseResult.ITEM_MISSING;
        }
        UseResult validation = validate(player, target, type);
        if (validation != UseResult.OK) {
            return validation;
        }

        World world = target.getWorld();
        if (world == null) {
            return UseResult.ITEM_MISSING;
        }
        ChunkAreaKey area = new ChunkAreaKey(world.getName(), target.getBlockX() >> 4, target.getBlockZ() >> 4);
        if (!activeLocks.add(area)) {
            return UseResult.AREA_BUSY;
        }

        long operationId;
        try {
            operationId = storage.insertOperation(area.world(), area.chunkX(), area.chunkZ(), type.name(),
                    System.currentTimeMillis(), ChunkBusterStorage.STATUS_IN_PROGRESS);
        } catch (SQLException error) {
            activeLocks.remove(area);
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Chunk Buster operation lock; aborting.", error);
            return UseResult.PERSIST_FAILED;
        }

        // Point of no return: the durable lock exists, so consume the item now.
        consumeOne(player, held);

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        List<ChunkBusterArea.BlockPos> computed = ChunkBusterArea.positions(type, target.getBlockX(),
                target.getBlockY(), target.getBlockZ(), minHeight, maxHeight);
        Queue<ChunkBusterArea.BlockPos> queue = new ArrayDeque<>(computed);

        UUID playerId = player.getUniqueId();
        Location logLocation = target.clone();
        int batchSize = blocksPerTick;

        BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> performance.time("chunkbuster.batch-removal", () -> {
            World liveWorld = Bukkit.getWorld(area.world());
            if (liveWorld == null) {
                taskHolder[0].cancel();
                stopOperation(operationId, area, playerId, "world unavailable");
                return;
            }
            // Claims, memberships and /f permissions may change during this
            // multi-tick operation. Only the job's own area lock is bypassed.
            Player livePlayer = Bukkit.getPlayer(playerId);
            UseResult current = livePlayer == null ? UseResult.NO_ROLE_PERMISSION
                    : validate(livePlayer, logLocation, type, false);
            if (current != UseResult.OK) {
                taskHolder[0].cancel();
                stopOperation(operationId, area, playerId, current.name());
                return;
            }
            int processed = 0;
            while (processed < batchSize && !queue.isEmpty()) {
                processed++;
                ChunkBusterArea.BlockPos pos = queue.poll();
                removeOneBlock(liveWorld, pos);
            }
            if (queue.isEmpty()) {
                taskHolder[0].cancel();
                finishOperation(operationId, area, playerId, type, logLocation);
            }
        }), periodTicks, periodTicks);

        return UseResult.OK;
    }

    private void removeOneBlock(World world, ChunkBusterArea.BlockPos pos) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Material material = block.getType();
        if (material == Material.AIR || isProtected(material)) {
            return;
        }
        if (spawners.isTracked(block.getLocation())) {
            spawners.remove(block.getLocation());
        }
        // No physics update: this is a deliberate bulk clear, not a player
        // break, and re-triggering neighbor updates (falling sand/gravel,
        // water/lava flow, redstone) for every one of up to ~98,000 blocks
        // would multiply the tick cost this batching is specifically meant
        // to avoid. Containers (chests/furnaces/barrels/etc.) are destroyed
        // with their contents by the same call -- setType never opens,
        // drops, or saves a container's inventory anywhere.
        block.setType(Material.AIR, false);
    }

    private void finishOperation(long operationId, ChunkAreaKey area, UUID playerId, ChunkBusterType type,
            Location logLocation) {
        activeLocks.remove(area);
        try {
            storage.deleteOperation(operationId);
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear a completed Chunk Buster operation's lock row.", error);
        }
        CompletableFuture.runAsync(() -> {
            try {
                storage.insertLog(playerId.toString(), type.name(), logLocation.getWorld().getName(),
                        logLocation.getBlockX(), logLocation.getBlockY(), logLocation.getBlockZ(),
                        System.currentTimeMillis());
            } catch (Exception error) {
                plugin.getLogger().log(Level.WARNING, "Failed to write a Chunk Buster log entry.", error);
            }
        });
    }

    private void stopOperation(long operationId, ChunkAreaKey area, UUID playerId, String reason) {
        activeLocks.remove(area);
        plugin.getLogger().warning("Stopped Chunk Buster operation " + operationId + " by " + playerId
                + " at " + area + ": " + reason + ". Already-cleared blocks remain cleared.");
        try { storage.stopOperation(operationId); }
        catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Could not mark stopped Chunk Buster " + operationId, error); }
    }

    private static void consumeOne(Player player, ItemStack item) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }
}
