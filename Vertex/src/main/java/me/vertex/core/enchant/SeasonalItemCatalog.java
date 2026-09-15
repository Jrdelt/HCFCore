package me.vertex.core.enchant;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Admin-curated catalog of fully-built seasonal reward items -- exactly the
 * {@link ItemStack} an admin was holding when they saved it, enchantments,
 * lore, custom model data, PDC and all, keyed by a short id such as
 * {@code fallen_helmet}. Exists so refilling a new seasonal crate is
 * "build the item once, {@code /seasonal catalog save <id>}, then
 * {@code /seasonal give <id>} forever after" instead of hand-authoring a
 * crate plugin's raw ItemStack YAML for every piece by hand.
 *
 * <p>Backed by {@code seasonal-items.yml} in the plugin's data folder.
 * Bukkit's {@link ItemStack} is a {@code ConfigurationSerializable}, so a
 * round trip through {@link YamlConfiguration} preserves every component
 * (enchantments, lore, custom model data, custom item-plugin PDC data)
 * without this catalog needing to know what any of them mean.
 */
public final class SeasonalItemCatalog {

    private final Plugin plugin;
    private final File file;
    private final java.util.Map<String, ItemStack> items = new ConcurrentHashMap<>();

    public SeasonalItemCatalog(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "seasonal-items.yml");
    }

    public void load() {
        items.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("items");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ItemStack item = section.getItemStack(id);
            if (item != null) {
                items.put(id.toLowerCase(Locale.ROOT), item);
            }
        }
    }

    /** Saves {@code item} (cloned) under {@code id}, overwriting any existing entry, and persists immediately. */
    public synchronized void save(String id, ItemStack item) {
        String key = id.toLowerCase(Locale.ROOT);
        items.put(key, item.clone());
        writeToDisk();
    }

    /** @return whether an entry existed to remove. */
    public synchronized boolean remove(String id) {
        boolean removed = items.remove(id.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            writeToDisk();
        }
        return removed;
    }

    /** A fresh clone, safe to hand out and mutate, or null if {@code id} isn't catalogued. */
    public ItemStack get(String id) {
        ItemStack item = items.get(id.toLowerCase(Locale.ROOT));
        return item == null ? null : item.clone();
    }

    public boolean has(String id) {
        return items.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public Set<String> ids() {
        return new TreeSet<>(items.keySet());
    }

    private void writeToDisk() {
        YamlConfiguration config = new YamlConfiguration();
        for (var entry : items.entrySet()) {
            config.set("items." + entry.getKey(), entry.getValue());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save seasonal-items.yml", e);
        }
    }
}
