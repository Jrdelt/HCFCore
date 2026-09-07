package me.vertex.core.backpack;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Persists each player's auto-collection filter independently of their Backpack item. */
public final class BackpackFilterManager {
    private final Plugin plugin;
    private final File file;
    private final ConcurrentHashMap<UUID, Set<Material>> filters = new ConcurrentHashMap<>();

    public BackpackFilterManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "backpack-filters.yml");
    }

    public void load() {
        filters.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                Set<Material> materials = EnumSet.noneOf(Material.class);
                for (String name : config.getStringList(key)) {
                    Material material = Material.matchMaterial(name);
                    if (material != null && !material.isAir()) {
                        materials.add(material);
                    }
                }
                if (!materials.isEmpty()) {
                    filters.put(playerId, materials);
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid Backpack filter owner: " + key);
            }
        }
    }

    public boolean isFiltered(UUID playerId, Material material) {
        return filters.getOrDefault(playerId, Set.of()).contains(material);
    }

    /** @return true when the material is now filtered; false when it was removed. */
    public synchronized boolean toggle(UUID playerId, Material material) {
        Set<Material> current = filters.computeIfAbsent(playerId, ignored -> EnumSet.noneOf(Material.class));
        boolean enabled;
        if (current.contains(material)) {
            current.remove(material);
            enabled = false;
            if (current.isEmpty()) {
                filters.remove(playerId);
            }
        } else {
            current.add(material);
            enabled = true;
        }
        save();
        return enabled;
    }

    public synchronized int clear(UUID playerId) {
        Set<Material> removed = filters.remove(playerId);
        save();
        return removed == null ? 0 : removed.size();
    }

    public Set<Material> filtered(UUID playerId) {
        Set<Material> current = filters.get(playerId);
        return current == null || current.isEmpty() ? Set.of() : Set.copyOf(current);
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (var entry : filters.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue().stream().map(Material::name).sorted().toList());
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save Backpack filters.", e);
        }
    }
}
