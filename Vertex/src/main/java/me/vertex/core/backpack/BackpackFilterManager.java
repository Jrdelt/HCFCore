package me.vertex.core.backpack;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Persists each player's auto-collection filter independently of their Backpack item. */
public final class BackpackFilterManager {
    private final Plugin plugin;
    private final File file;
    private final ConcurrentHashMap<UUID, Set<Material>> filters = new ConcurrentHashMap<>();
    private final AtomicBoolean persistenceHealthy = new AtomicBoolean(true);

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
                    filters.put(playerId, Set.copyOf(materials));
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
        Map<UUID, Set<Material>> next = snapshot();
        Set<Material> current = mutable(next.get(playerId));
        boolean enabled = !current.remove(material);
        if (enabled) current.add(material);
        put(next, playerId, current);
        if (!save(next)) return filters.getOrDefault(playerId, Set.of()).contains(material);
        replace(next);
        return enabled;
    }

    public synchronized boolean add(UUID playerId, Material material) {
        Map<UUID, Set<Material>> next = snapshot();
        Set<Material> current = mutable(next.get(playerId));
        boolean changed = current.add(material);
        if (!changed) return false;
        put(next, playerId, current);
        if (!save(next)) return false;
        replace(next);
        return changed;
    }

    public synchronized boolean remove(UUID playerId, Material material) {
        Map<UUID, Set<Material>> next = snapshot();
        Set<Material> current = mutable(next.get(playerId));
        boolean changed = current != null && current.remove(material);
        if (!changed) return false;
        put(next, playerId, current);
        if (!save(next)) return false;
        replace(next);
        return changed;
    }

    public synchronized int clear(UUID playerId) {
        Set<Material> removed = filters.get(playerId);
        if (removed == null || removed.isEmpty()) return 0;
        Map<UUID, Set<Material>> next = snapshot();
        next.remove(playerId);
        if (!save(next)) return 0;
        replace(next);
        return removed.size();
    }

    public Set<Material> filtered(UUID playerId) {
        Set<Material> current = filters.get(playerId);
        return current == null || current.isEmpty() ? Set.of() : Set.copyOf(current);
    }

    public boolean persistenceHealthy() {
        return persistenceHealthy.get();
    }

    private Map<UUID, Set<Material>> snapshot() {
        Map<UUID, Set<Material>> copy = new HashMap<>();
        filters.forEach((owner, values) -> copy.put(owner, Set.copyOf(values)));
        return copy;
    }

    private static Set<Material> mutable(Set<Material> values) {
        Set<Material> copy = EnumSet.noneOf(Material.class);
        if (values != null) copy.addAll(values);
        return copy;
    }

    private static void put(Map<UUID, Set<Material>> target, UUID owner, Set<Material> values) {
        if (values.isEmpty()) target.remove(owner);
        else target.put(owner, Set.copyOf(values));
    }

    private void replace(Map<UUID, Set<Material>> next) {
        filters.clear();
        filters.putAll(next);
    }

    private boolean save(Map<UUID, Set<Material>> values) {
        YamlConfiguration config = new YamlConfiguration();
        for (var entry : values.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue().stream().map(Material::name).sorted().toList());
        }
        Path temporary = null;
        try {
            Path target = file.toPath().toAbsolutePath();
            Path directory = target.getParent();
            if (directory == null) throw new IOException("Backpack filter path has no parent");
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, file.getName() + ".", ".tmp");
            byte[] bytes = config.saveToString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            persistenceHealthy.set(true);
            return true;
        } catch (IOException e) {
            persistenceHealthy.set(false);
            plugin.getLogger().log(Level.SEVERE, "Failed to save Backpack filters.", e);
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }
}
