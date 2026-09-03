package me.hcfcore.core.ability;

import me.hcfcore.core.storage.Storage;
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

    public void startCooldown(Player player, User user, Ability ability) {
        if (ability.getCooldownSeconds() <= 0) {
            return;
        }
        long expiry = System.currentTimeMillis() + ability.getCooldownSeconds() * 1000L;
        user.setCooldownExpiry(cooldownKey(ability), expiry);

        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                storage.saveAbilityCooldown(player.getUniqueId(), ability.getId(), expiry);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist ability cooldown for " + player.getUniqueId(), e);
            }
        });
        pendingWrites.add(write);
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

    public void awaitWrites() {
        CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).join();
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
