package me.hcfcore.core.ability;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.kit.ArmorClass;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.user.User;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class AbilityManager {

    public static final String ABILITY_ID_KEY = "ability_id";
    public static final String USES_KEY = "ability_uses";
    private static final String PORTABLE_BARD_ID = "portable-bard";
    private static final Set<String> COMMON_KEYS = Set.of("material", "name", "lore", "cooldown-seconds");

    private final Plugin plugin;
    private final Storage storage;
    private final File file;
    private final Map<String, Ability> abilities = new LinkedHashMap<>();
    private final Map<UUID, Long> lastAbilityUse = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();

    public AbilityManager(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "abilities.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("abilities.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("abilities");
        abilities.clear();
        if (root == null) {
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            Material material = parseMaterial(section.getString("material"), id);
            String name = section.getString("name", id);
            List<String> lore = section.getStringList("lore");
            int cooldown = section.getInt("cooldown-seconds", 0);

            Map<String, Object> settings = new HashMap<>(section.getValues(false));
            settings.keySet().removeAll(COMMON_KEYS);

            abilities.put(id.toLowerCase(Locale.ROOT), new Ability(id, material, name, lore, cooldown, settings));
        }
    }

    private Material parseMaterial(String raw, String id) {
        if (raw != null) {
            try {
                return Material.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Unknown material '" + raw + "' for ability '" + id + "', defaulting to STONE", e);
            }
        }
        return Material.STONE;
    }

    public Ability get(String id) {
        return abilities.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, Ability> getAbilities() {
        return abilities;
    }

    public ItemStack createItem(Ability ability) {
        ItemStack item = new ItemStack(ability.getMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(ability.getDisplayName()));

        List<Component> lore = new ArrayList<>();
        for (String line : ability.getLore()) {
            lore.add(MessageFormatter.deserialize(line));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, ABILITY_ID_KEY), PersistentDataType.STRING, ability.getId());
        int uses = ability.getInt("uses", 0);
        if (uses > 0) {
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, USES_KEY), PersistentDataType.INTEGER, uses);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Ability cooldowns share User's kit-cooldown map, namespaced under
     * "ability:" so they can't collide with a kit's own key -- see
     * UserManager.load(), which merges the separate ability_cooldowns
     * table into that same map at login.
     */
    public boolean isOnCooldown(User user, Ability ability) {
        return remainingCooldownMillis(user, ability) > 0;
    }

    public long remainingCooldownMillis(User user, Ability ability) {
        return Math.max(0L, user.getCooldownExpiry(cooldownKey(ability)) - System.currentTimeMillis());
    }

    /**
     * How long `ability` should stay on cooldown for this particular
     * player, which is not always the flat abilities.yml value.
     *
     * <p>Portable Bard is the bard kit's own ability, and its long
     * cooldown exists to stop non-bards who got hold of the item from
     * spamming faction-wide buffs. A player actually wearing the full
     * gold set is playing the class the item belongs to, so they get the
     * short in-kit cooldown ("bard-cooldown-seconds") instead.
     */
    public int effectiveCooldownSeconds(Player player, Ability ability) {
        if (PORTABLE_BARD_ID.equalsIgnoreCase(ability.getId()) && ArmorClass.isBard(player)) {
            return ability.getInt("bard-cooldown-seconds", ability.getCooldownSeconds());
        }
        return ability.getCooldownSeconds();
    }

    public void startCooldown(Player player, User user, Ability ability) {
        int cooldownSeconds = effectiveCooldownSeconds(player, ability);
        if (cooldownSeconds <= 0) {
            return;
        }
        setCooldownExpiry(player, user, ability, System.currentTimeMillis() + cooldownSeconds * 1000L);
    }

    /**
     * Re-scores an outstanding Portable Bard cooldown the moment a player
     * puts the full gold set on, capping it at the in-kit
     * "bard-cooldown-seconds" wait.
     *
     * <p>The long flat cooldown is a penalty for using the bard's item
     * outside the bard's class, so it shouldn't follow a player into the
     * class: someone who fired it off in diamond and then geared into gold
     * would otherwise spend most of their bard session locked out of the
     * ability the kit is built around.
     *
     * @return the seconds the player now has left, or -1 if there was
     *         nothing to shorten (not a bard, no live cooldown, or already
     *         within the bard wait)
     */
    public long shortenBardCooldownForKitSwap(Player player, User user) {
        Ability ability = get(PORTABLE_BARD_ID);
        if (ability == null || !ArmorClass.isBard(player)) {
            return -1L;
        }
        long now = System.currentTimeMillis();
        long expiry = user.getCooldownExpiry(cooldownKey(ability));
        long bardExpiry = now + effectiveCooldownSeconds(player, ability) * 1000L;
        if (expiry <= now || expiry <= bardExpiry) {
            return -1L;
        }
        setCooldownExpiry(player, user, ability, bardExpiry);
        return (bardExpiry - now + 999L) / 1000L;
    }

    private void setCooldownExpiry(Player player, User user, Ability ability, long expiry) {
        user.setCooldownExpiry(cooldownKey(ability), expiry);

        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                storage.saveAbilityCooldown(player.getUniqueId(), ability.getId(), expiry);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist ability cooldown for " + player.getUniqueId(), e);
            }
        });
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Timed out waiting for ability cooldown writes during shutdown.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for ability cooldown writes.", e);
        }
    }

    /**
     * Per-buff cooldowns for the abilities offered inside an ability's own
     * GUI (currently only Portable Bard's buff picker). Kept in memory
     * rather than in the User cooldown map because they are short enough
     * that surviving a relog would never matter, and because they are not
     * abilities in their own right -- /cooldowns lists one entry for the
     * parent ability, not five.
     */
    private final Map<UUID, Map<String, Long>> buffCooldowns = new ConcurrentHashMap<>();

    public long buffCooldownRemainingMillis(UUID playerId, Ability ability, String buffId) {
        Map<String, Long> playerBuffs = buffCooldowns.get(playerId);
        if (playerBuffs == null) {
            return 0L;
        }
        String key = buffKey(ability, buffId);
        long remaining = playerBuffs.getOrDefault(key, 0L) - System.currentTimeMillis();
        if (remaining <= 0L) {
            playerBuffs.remove(key);
            if (playerBuffs.isEmpty()) {
                buffCooldowns.remove(playerId, playerBuffs);
            }
            return 0L;
        }
        return remaining;
    }

    public void startBuffCooldown(UUID playerId, Ability ability, String buffId) {
        int seconds = ability.getInt("buff-cooldown-seconds", 0);
        if (seconds <= 0) {
            return;
        }
        buffCooldowns.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(buffKey(ability, buffId), System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * Housekeeping for a leaving player. Only drops what has already
     * expired: wiping live buff cooldowns on quit would let a bard relog
     * to re-apply the same buff immediately.
     */
    public void clearExpiredBuffCooldowns(UUID playerId) {
        Map<String, Long> playerBuffs = buffCooldowns.get(playerId);
        if (playerBuffs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        playerBuffs.values().removeIf(expiry -> expiry <= now);
        if (playerBuffs.isEmpty()) {
            buffCooldowns.remove(playerId, playerBuffs);
        }
    }

    private static String buffKey(Ability ability, String buffId) {
        return ability.getId().toLowerCase(Locale.ROOT) + ":" + buffId;
    }

    public boolean isOnGlobalCooldown(UUID uuid) {
        return globalCooldownRemainingMillis(uuid) > 0;
    }

    public long globalCooldownRemainingMillis(UUID uuid) {
        Long last = lastAbilityUse.get(uuid);
        if (last == null) {
            return 0L;
        }
        return Math.max(0L, globalCooldownMillis() - (System.currentTimeMillis() - last));
    }

    public void markGlobalCooldown(UUID uuid) {
        lastAbilityUse.put(uuid, System.currentTimeMillis());
    }

    private long globalCooldownMillis() {
        return plugin.getConfig().getLong("abilities.global-cooldown-seconds", 3) * 1000L;
    }

    private static String cooldownKey(Ability ability) {
        return "ability:" + ability.getId();
    }
}
