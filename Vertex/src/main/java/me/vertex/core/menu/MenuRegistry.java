package me.vertex.core.menu;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads every menu definition from {@code gui/<id>.yml}.
 *
 * <p>A bad value never takes a menu down: each one falls back to its default
 * with a console warning naming the file, the key, and what was used
 * instead. A GUI that silently refuses to open is far harder to diagnose
 * than one that opens with a stone icon and a warning in the log.
 */
public final class MenuRegistry {

    /** Chest inventories are 9-54 slots in multiples of 9; nothing else is a valid size. */
    private static final int MIN_SIZE = 9;
    private static final int MAX_SIZE = 54;

    private final Plugin plugin;
    private final List<String> bundled;
    private final Map<String, MenuLayout> layouts = new ConcurrentHashMap<>();

    public MenuRegistry(Plugin plugin, List<String> bundledMenuIds) {
        this.plugin = plugin;
        this.bundled = List.copyOf(bundledMenuIds);
    }

    public void load() {
        layouts.clear();
        for (String id : bundled) {
            File file = new File(plugin.getDataFolder(), "gui/" + id + ".yml");
            if (!file.exists()) {
                plugin.saveResource("gui/" + id + ".yml", false);
            }
            layouts.put(id, parse(id, YamlConfiguration.loadConfiguration(file)));
        }
    }

    /**
     * @return the configured layout, or a minimal usable one if the file was
     *         missing entirely, so a caller never has to null-check
     */
    public MenuLayout layout(String id) {
        MenuLayout layout = layouts.get(id);
        if (layout != null) {
            return layout;
        }
        plugin.getLogger().warning("gui/" + id + ".yml was never loaded; using an empty fallback layout.");
        return new MenuLayout(id, 27, id, 0L, null, Map.of(), Map.of(), Map.of());
    }

    private MenuLayout parse(String id, YamlConfiguration config) {
        String where = "gui/" + id + ".yml";
        int size = readSize(where, config.getInt("size", 27));
        String title = config.getString("title", id);
        long refreshTicks = Math.max(0L, config.getLong("refresh-ticks", 0L));

        MenuItemTemplate filler = null;
        ConfigurationSection fillerSection = config.getConfigurationSection("filler");
        if (fillerSection != null) {
            filler = readItem(where, "filler", fillerSection);
        }

        Map<String, MenuItemTemplate> items = new LinkedHashMap<>();
        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection section = itemsSection.getConfigurationSection(key);
                if (section == null) {
                    plugin.getLogger().warning(where + ": items." + key + " is not a section, skipping it.");
                    continue;
                }
                items.put(key, readItem(where, key, section));
            }
        }
        Map<String, int[]> slotLists = new LinkedHashMap<>();
        ConfigurationSection layoutSection = config.getConfigurationSection("layout");
        if (layoutSection != null) {
            for (String key : layoutSection.getKeys(false)) {
                slotLists.put(key, toSlotArray(where, key, layoutSection.getIntegerList(key)));
            }
        }
        Map<String, String> titleVariants = new LinkedHashMap<>();
        ConfigurationSection titlesSection = config.getConfigurationSection("titles");
        if (titlesSection != null) {
            for (String key : titlesSection.getKeys(false)) {
                titleVariants.put(key, titlesSection.getString(key, title));
            }
        }
        return new MenuLayout(id, size, title, refreshTicks, filler, items, slotLists, titleVariants);
    }

    private int readSize(String where, int configured) {
        if (configured >= MIN_SIZE && configured <= MAX_SIZE && configured % 9 == 0) {
            return configured;
        }
        plugin.getLogger().warning(where + ": size " + configured
                + " is not 9-54 in multiples of 9, using 27.");
        return 27;
    }

    private MenuItemTemplate readItem(String where, String key, ConfigurationSection section) {
        Material material = readMaterial(where, key, section.getString("material"));
        Integer customModelData = section.contains("custom-model-data")
                ? section.getInt("custom-model-data") : null;
        int amount = Math.max(1, section.getInt("amount", 1));
        String name = section.getString("name", "");
        List<String> lore = section.getStringList("lore");
        boolean enabled = section.getBoolean("enabled", true);
        Sound sound = readSound(where, key, section.getString("sound"));
        boolean glow = section.getBoolean("glow", false);
        return new MenuItemTemplate(key, material, customModelData, amount, name, lore,
                readSlots(where, key, section), enabled, sound, glow);
    }

    private Material readMaterial(String where, String key, String raw) {
        if (raw == null || raw.isBlank()) {
            // Legitimate: templates rendered many times supply their own icon.
            return null;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning(where + ": " + key + " has unknown material '" + raw
                    + "', falling back to STONE.");
            return Material.STONE;
        }
        return material;
    }

    private Sound readSound(String where, String key, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        NamespacedKey soundKey = NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
        Sound sound = soundKey == null ? null : Registry.SOUNDS.get(soundKey);
        if (sound == null) {
            plugin.getLogger().warning(where + ": " + key + " has unknown sound '" + raw + "', playing none.");
        }
        return sound;
    }

    private int[] toSlotArray(String where, String key, List<Integer> raw) {
        List<Integer> valid = new ArrayList<>();
        for (int slot : raw) {
            if (slot < 0 || slot >= MAX_SIZE) {
                plugin.getLogger().warning(where + ": layout." + key + " has out-of-range slot "
                        + slot + ", ignoring it.");
                continue;
            }
            valid.add(slot);
        }
        int[] result = new int[valid.size()];
        for (int index = 0; index < valid.size(); index++) {
            result[index] = valid.get(index);
        }
        return result;
    }

    /** Accepts a single {@code slot} or a {@code slots} list; either may be omitted. */
    private int[] readSlots(String where, String key, ConfigurationSection section) {
        List<Integer> slots = new ArrayList<>();
        if (section.contains("slot")) {
            slots.add(section.getInt("slot"));
        }
        slots.addAll(section.getIntegerList("slots"));

        List<Integer> valid = new ArrayList<>();
        for (int slot : slots) {
            if (slot < 0 || slot >= MAX_SIZE) {
                plugin.getLogger().warning(where + ": " + key + " has out-of-range slot " + slot + ", ignoring it.");
                continue;
            }
            valid.add(slot);
        }
        int[] result = new int[valid.size()];
        for (int index = 0; index < valid.size(); index++) {
            result[index] = valid.get(index);
        }
        return result;
    }
}
