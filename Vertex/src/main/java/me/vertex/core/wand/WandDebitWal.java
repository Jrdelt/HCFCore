package me.vertex.core.wand;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronous local write-ahead log recording, for one container location at
 * a time, the gunpowder/sand a TNT Wand conversion still owes that container
 * -- written and fsynced before any material is actually removed.
 *
 * <p>The faction bank credit for a TNT conversion is committed to SQL (and
 * therefore irrevocable) before the source materials are ever touched; see
 * {@code WandListener.runTnt}/{@code settleTnt}. Without this journal, a hard
 * crash between that credit and the container's own removal reaching disk
 * (a chest's contents are only as durable as the next chunk autosave; a
 * Chunk Collector's own SQL write is separately async) would let the source
 * materials survive while the bank was already credited, creating TNT for
 * free. Replayed by {@code WandManager} against the container's live
 * contents once its chunk is loaded, instead of trusting whichever state
 * happened to survive.
 */
final class WandDebitWal {
    private static final int FORMAT_VERSION = 1;

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    WandDebitWal(File dataFolder) {
        this.file = new File(dataFolder, "wand-debit-wal.yml");
    }

    synchronized List<Entry> load() throws IOException {
        entries.clear();
        if (!file.isFile()) return List.of();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (Exception e) {
            throw new IOException("Could not read the wand debit WAL", e);
        }
        int formatVersion = config.getInt("version", FORMAT_VERSION);
        if (formatVersion != FORMAT_VERSION) {
            throw new IOException("Unsupported wand debit WAL format " + formatVersion);
        }
        ConfigurationSection root = config.getConfigurationSection("entries");
        if (root == null) return List.of();
        for (String locationKey : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(locationKey);
            if (section == null) continue;
            long version = section.getLong("version", 0);
            int gunpowder = section.getInt("gunpowder", 0);
            int sand = section.getInt("sand", 0);
            if (gunpowder <= 0 && sand <= 0) continue;
            entries.put(locationKey, new Entry(locationKey, version, gunpowder, sand));
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

    /** Removes {@code locationKey} only if its stored entry is still at {@code version}. */
    synchronized void removeIfVersion(String locationKey, long version) throws IOException {
        Entry current = entries.get(locationKey);
        if (current == null || current.version() != version) return;
        Entry removed = entries.remove(locationKey);
        try {
            save();
        } catch (IOException error) {
            entries.put(locationKey, removed);
            throw error;
        }
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
            config.set(path + ".gunpowder", entry.gunpowder());
            config.set(path + ".sand", entry.sand());
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

    /** How much gunpowder/sand a settled TNT conversion still owes {@code locationKey}. */
    record Entry(String locationKey, long version, int gunpowder, int sand) {
    }
}
