package me.vertex.core.faction;

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
import java.util.UUID;

/**
 * Synchronous local write-ahead log recording, per TNT bank deposit
 * operation, that its source materials are about to leave a player's
 * inventory before either the bank credit or a compensating refund is
 * durable (ISS-12).
 *
 * <p>Unlike {@code SpawnerDebitWal}/{@code WandDebitWal}, there is nothing
 * this journal can safely auto-resolve on replay: whether the credit
 * actually reached SQL, and whether a refund already ran, cannot be told
 * apart from a survived entry alone (the bank balance mutation carries no
 * per-operation receipt). A survived entry is therefore reported for staff
 * reconciliation rather than guessed at -- the point is turning a silent
 * loss into an auditable one, not automated recovery. See
 * {@code FactionBankManager.replayDepositJournal}.
 */
final class TntDepositWal {
    private static final int FORMAT_VERSION = 1;

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    TntDepositWal(File dataFolder) {
        this.file = new File(dataFolder, "tnt-deposit-wal.yml");
    }

    synchronized List<Entry> load() throws IOException {
        entries.clear();
        if (!file.isFile()) return List.of();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (Exception e) {
            throw new IOException("Could not read the TNT deposit WAL", e);
        }
        int formatVersion = config.getInt("version", FORMAT_VERSION);
        if (formatVersion != FORMAT_VERSION) {
            throw new IOException("Unsupported TNT deposit WAL format " + formatVersion);
        }
        ConfigurationSection root = config.getConfigurationSection("entries");
        if (root == null) return List.of();
        for (String operationId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(operationId);
            if (section == null) continue;
            String ownerRaw = section.getString("owner");
            int factionId = section.getInt("faction-id", -1);
            long amount = section.getLong("amount", 0);
            UUID owner;
            try {
                owner = ownerRaw == null ? null : UUID.fromString(ownerRaw);
            } catch (IllegalArgumentException error) {
                owner = null;
            }
            if (owner == null || factionId < 0 || amount <= 0) continue;
            entries.put(operationId, new Entry(operationId, owner, factionId, amount));
        }
        return List.copyOf(entries.values());
    }

    synchronized void put(Entry entry) throws IOException {
        entries.put(entry.operationId(), entry);
        try {
            save();
        } catch (IOException error) {
            entries.remove(entry.operationId());
            throw error;
        }
    }

    synchronized void remove(String operationId) throws IOException {
        Entry removed = entries.remove(operationId);
        if (removed == null) return;
        try {
            save();
        } catch (IOException error) {
            entries.put(operationId, removed);
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
            String path = "entries." + entry.operationId();
            config.set(path + ".owner", entry.owner().toString());
            config.set(path + ".faction-id", entry.factionId());
            config.set(path + ".amount", entry.amount());
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

    record Entry(String operationId, UUID owner, int factionId, long amount) {
    }
}
