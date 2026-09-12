package me.vertex.core.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous local write-ahead log for delivery batches that have not yet
 * reached SQL. The file is replaced atomically and replayed before normal
 * delivery starts, so a hard JVM stop cannot erase an in-flight overflow.
 */
final class DeliveryWal {
    private static final int FORMAT_VERSION = 1;

    private final File file;
    private final Map<String, WalBatch> batches = new LinkedHashMap<>();

    DeliveryWal(File dataFolder) {
        this.file = new File(dataFolder, "delivery-wal.yml");
    }

    synchronized List<WalBatch> load() throws Exception {
        batches.clear();
        if (!file.isFile()) return List.of();

        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        int version = config.getInt("version", FORMAT_VERSION);
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported delivery WAL format " + version);
        }
        ConfigurationSection root = config.getConfigurationSection("batches");
        if (root == null) return List.of();
        for (String batchId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(batchId);
            if (section == null) throw new IOException("Malformed delivery WAL batch " + batchId);
            UUID owner;
            try {
                owner = UUID.fromString(section.getString("owner", ""));
                UUID.fromString(batchId);
            } catch (IllegalArgumentException error) {
                throw new IOException("Malformed delivery WAL identity " + batchId, error);
            }
            String source = section.getString("source", "unknown");
            List<DeliveryStorage.PreparedDelivery> items = new ArrayList<>();
            for (Map<?, ?> encoded : section.getMapList("items")) {
                Object rawId = encoded.get("id");
                Object rawData = encoded.get("data");
                if (!(rawId instanceof String id) || !(rawData instanceof String data)) {
                    throw new IOException("Malformed item in delivery WAL batch " + batchId);
                }
                try {
                    UUID.fromString(id);
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
                    if (item == null || item.isEmpty()) throw new IllegalArgumentException("empty item");
                    items.add(new DeliveryStorage.PreparedDelivery(id, item));
                } catch (RuntimeException error) {
                    throw new IOException("Corrupt item in delivery WAL batch " + batchId, error);
                }
            }
            if (items.isEmpty()) throw new IOException("Empty delivery WAL batch " + batchId);
            WalBatch batch = new WalBatch(batchId, owner, items, source);
            batches.put(batchId, batch);
        }
        return List.copyOf(batches.values());
    }

    synchronized void put(WalBatch batch) throws IOException {
        if (batches.containsKey(batch.batchId())) return;
        batches.put(batch.batchId(), batch);
        try {
            save();
        } catch (IOException error) {
            batches.remove(batch.batchId());
            throw error;
        }
    }

    synchronized void remove(String batchId) throws IOException {
        WalBatch removed = batches.remove(batchId);
        if (removed == null) return;
        try {
            save();
        } catch (IOException error) {
            batches.put(batchId, removed);
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
        for (WalBatch batch : batches.values()) {
            String path = "batches." + batch.batchId();
            config.set(path + ".owner", batch.owner().toString());
            config.set(path + ".source", batch.source());
            List<Map<String, Object>> items = new ArrayList<>();
            for (DeliveryStorage.PreparedDelivery prepared : batch.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", prepared.id());
                row.put("data", Base64.getEncoder().encodeToString(prepared.item().serializeAsBytes()));
                items.add(row);
            }
            config.set(path + ".items", items);
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

    record WalBatch(String batchId, UUID owner, List<DeliveryStorage.PreparedDelivery> items, String source) {
        WalBatch {
            items = List.copyOf(items);
            source = source == null || source.isBlank() ? "unknown" : source;
        }
    }
}
