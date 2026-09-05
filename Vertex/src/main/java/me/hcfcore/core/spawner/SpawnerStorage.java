package me.hcfcore.core.spawner;

import me.hcfcore.core.storage.Database;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent of the shared {@code Storage} interface on purpose -- that
 * interface is implemented by test fakes scattered across many unrelated
 * test files, and adding spawner methods to it would mean updating every
 * one of them for a feature they have nothing to do with. This uses the
 * same underlying connection pool directly instead.
 */
public final class SpawnerStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS spawners (
                world VARCHAR(64) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                mob_type VARCHAR(64) NOT NULL,
                stack_size INT NOT NULL,
                owner_faction VARCHAR(64) NULL,
                PRIMARY KEY (world, x, y, z)
            )""";

    private static final String SELECT_ALL =
            "SELECT world, x, y, z, mob_type, stack_size, owner_faction FROM spawners";
    private static final String UPSERT = """
            INSERT INTO spawners (world, x, y, z, mob_type, stack_size, owner_faction) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE mob_type = VALUES(mob_type), stack_size = VALUES(stack_size),
                owner_faction = VALUES(owner_faction)""";
    private static final String DELETE = "DELETE FROM spawners WHERE world = ? AND x = ? AND y = ? AND z = ?";

    private final Database database;

    public SpawnerStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    /** Every tracked spawner, keyed by its location's world/x/y/z. */
    public List<StoredSpawner> loadAll() throws SQLException {
        List<StoredSpawner> spawners = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                EntityType mobType;
                try {
                    mobType = EntityType.valueOf(results.getString("mob_type"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                spawners.add(new StoredSpawner(
                        results.getString("world"),
                        results.getInt("x"),
                        results.getInt("y"),
                        results.getInt("z"),
                        mobType,
                        results.getInt("stack_size"),
                        results.getString("owner_faction")));
            }
        }
        return spawners;
    }

    public void save(Location location, SpawnerData data) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, location.getWorld().getName());
            statement.setInt(2, location.getBlockX());
            statement.setInt(3, location.getBlockY());
            statement.setInt(4, location.getBlockZ());
            statement.setString(5, data.mobType().name());
            statement.setInt(6, data.stackSize());
            statement.setString(7, data.ownerFactionTag());
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

    public record StoredSpawner(String world, int x, int y, int z, EntityType mobType, int stackSize,
                                 String ownerFactionTag) {
    }
}
