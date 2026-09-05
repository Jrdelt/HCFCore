package me.hcfcore.core.collector;

import me.hcfcore.core.storage.Database;
import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Only tracks WHERE Chunk Collectors are, for rediscovery on restart -- the
 * actual per-item-type storage counts and upgrade tier live on the block's
 * own {@link org.bukkit.block.TileState#getPersistentDataContainer()},
 * which already round-trips through normal chunk save/load, so there's
 * nothing else worth duplicating into SQL. Independent of the shared
 * {@code Storage} interface for the same reason {@code SpawnerStorage} is:
 * that interface is implemented by test fakes scattered across many
 * unrelated test files.
 */
public final class ChunkCollectorStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS chunk_collectors (
                world VARCHAR(64) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                owner_faction VARCHAR(64) NULL,
                owner_uuid CHAR(36) NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )""";

    private static final String SELECT_ALL =
            "SELECT world, x, y, z, owner_faction, owner_uuid FROM chunk_collectors";
    private static final String UPSERT = """
            INSERT INTO chunk_collectors (world, x, y, z, owner_faction, owner_uuid) VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE owner_faction = VALUES(owner_faction), owner_uuid = VALUES(owner_uuid)""";
    private static final String DELETE = "DELETE FROM chunk_collectors WHERE world = ? AND x = ? AND y = ? AND z = ?";

    private final Database database;

    public ChunkCollectorStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public List<StoredCollector> loadAll() throws SQLException {
        List<StoredCollector> collectors = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                collectors.add(new StoredCollector(
                        results.getString("world"),
                        results.getInt("x"),
                        results.getInt("y"),
                        results.getInt("z"),
                        results.getString("owner_faction"),
                        results.getString("owner_uuid")));
            }
        }
        return collectors;
    }

    public void save(Location location, String ownerFactionTag, String ownerUuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, location.getWorld().getName());
            statement.setInt(2, location.getBlockX());
            statement.setInt(3, location.getBlockY());
            statement.setInt(4, location.getBlockZ());
            statement.setString(5, ownerFactionTag);
            statement.setString(6, ownerUuid);
            statement.executeUpdate();
        }
    }

    public void delete(Location location) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, location.getWorld().getName());
            statement.setInt(2, location.getBlockX());
            statement.setInt(3, location.getBlockY());
            statement.setInt(4, location.getBlockZ());
            statement.executeUpdate();
        }
    }

    public record StoredCollector(String world, int x, int y, int z, String ownerFactionTag, String ownerUuid) {
    }
}
