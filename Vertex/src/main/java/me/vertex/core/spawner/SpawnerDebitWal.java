package me.vertex.core.spawner;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Synchronous local write-ahead log recording, for one spawner location at a
 * time, the final stack state a withdrawal is about to apply (or that it is
 * about to be fully removed) -- written and fsynced before that state is
 * actually applied. A withdrawal's payout is admitted to the durable
 * delivery inbox before the placed stack is ever touched, and that admission
 * cannot be undone; this journal exists so a hard crash between that
 * admission and this location's own SQL/PDC write reaching disk cannot leave
 * the placed stack at its old (undebited) size while the payout still lands.
 * Replayed by {@code SpawnerManager} before trusting SQL or a block's PDC.
 */
final class SpawnerDebitWal {
    private static final int FORMAT_VERSION = 1;

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    SpawnerDebitWal(File dataFolder) {
        this.file = new File(dataFolder, "spawner-debit-wal.yml");
    }

    synchronized List<Entry> load() throws IOException {
        entries.clear();
        if (!file.isFile()) return List.of();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (Exception e) {
            throw new IOException("Could not read the spawner debit WAL", e);
        }
        int formatVersion = config.getInt("version", FORMAT_VERSION);
        if (formatVersion != FORMAT_VERSION) {
            throw new IOException("Unsupported spawner debit WAL format " + formatVersion);
        }
        ConfigurationSection root = config.getConfigurationSection("entries");
        if (root == null) return List.of();
        for (String locationKey : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(locationKey);
            if (section == null) continue;
            long version = section.getLong("version", 0);
            boolean removed = section.getBoolean("removed", false);
            if (removed) {
                entries.put(locationKey, Entry.removing(locationKey, version));
                continue;
            }
            String mobTypeName = section.getString("mob-type");
            String ages = section.getString("ages", "");
            EntityType mobType;
            try {
                mobType = mobTypeName == null ? null : EntityType.valueOf(mobTypeName);
            } catch (IllegalArgumentException error) {
                mobType = null;
            }
            if (mobType == null || ages.isBlank()) {
                // A malformed apply entry has nothing safe to replay -- the
                // stack's real final state can't be reconstructed from it.
                continue;
            }
            String owner = section.getString("owner", null);
            entries.put(locationKey, new Entry(locationKey, version, false, mobType, ages, owner));
        }
        return List.copyOf(entries.values());
    }

    synchronized void put(Entry entry) throws IOException {
        Entry previous = entries.put(entry.locationKey(), entry);
        try {
            save();
        } catch (IOException error) {
            if (previous == null) {
                entries.remove(entry.locationKey());
            } else {
                entries.put(entry.locationKey(), previous);
            }
            throw error;
        }
    }

    synchronized void remove(String locationKey) throws IOException {
        Entry removed = entries.remove(locationKey);
        if (removed == null) return;
        try {
            save();
        } catch (IOException error) {
            entries.put(locationKey, removed);
            throw error;
        }
    }

    /** Removes {@code locationKey} only if its stored entry is still at {@code version}. */
    synchronized void removeIfVersion(String locationKey, long version) throws IOException {
        Entry current = entries.get(locationKey);
        if (current == null || current.version() != version) return;
        remove(locationKey);
    }

    private void save() throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create " + parent);
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("version", FORMAT_VERSION);
        for (Entry entry : entries.values()) {
            String path = "entries." + entry.locationKey();
            config.set(path + ".version", entry.version());
            if (entry.removed()) {
                config.set(path + ".removed", true);
                continue;
            }
            config.set(path + ".mob-type", entry.mobType().name());
            config.set(path + ".ages", entry.ages());
            if (entry.owner() != null) {
                config.set(path + ".owner", entry.owner());
            }
        }

        byte[] bytes = config.saveToString().getBytes(StandardCharsets.UTF_8);
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** One location's pending journaled outcome: either its final state, or full removal. */
    record Entry(String locationKey, long version, boolean removed, EntityType mobType, String ages, String owner) {
        static Entry applying(String locationKey, long version, SpawnerData data) {
            String encodedAges = data.placedAtMillis().stream().map(String::valueOf)
                    .collect(Collectors.joining(","));
            return new Entry(locationKey, version, false, data.mobType(), encodedAges, data.ownerFactionTag());
        }

        static Entry removing(String locationKey, long version) {
            return new Entry(locationKey, version, true, null, null, null);
        }

        SpawnerData toSpawnerData() {
            List<Long> parsedAges = new ArrayList<>();
            for (String token : ages.split(",")) {
                if (token.isBlank()) continue;
                try {
                    parsedAges.add(Long.parseLong(token));
                } catch (NumberFormatException ignored) {
                    // Skipped -- SpawnerData.normalizeAges backfills any
                    // shortfall with fresh timestamps rather than fail here.
                }
            }
            return new SpawnerData(mobType, parsedAges, owner);
        }
    }
}
