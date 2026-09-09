package me.vertex.core.spawner;

import me.vertex.core.storage.Database;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
                placed_at_data TEXT NULL,
                PRIMARY KEY (world, x, y, z)
            )""";

    private static final String SELECT_ALL =
            "SELECT world, x, y, z, mob_type, stack_size, owner_faction, placed_at_data FROM spawners";
    private static final String UPSERT_MYSQL = """
            INSERT INTO spawners (world, x, y, z, mob_type, stack_size, owner_faction, placed_at_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE mob_type = VALUES(mob_type), stack_size = VALUES(stack_size),
                owner_faction = VALUES(owner_faction), placed_at_data = VALUES(placed_at_data)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO spawners (world, x, y, z, mob_type, stack_size, owner_faction, placed_at_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (world, x, y, z) DO UPDATE SET mob_type = excluded.mob_type,
                stack_size = excluded.stack_size, owner_faction = excluded.owner_faction,
                placed_at_data = excluded.placed_at_data""";
    private static final String DELETE = "DELETE FROM spawners WHERE world = ? AND x = ? AND y = ? AND z = ?";

    private final Database database;
    private final String upsert;

    public SpawnerStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
            migrateColumns(connection);
        }
    }

    /**
     * `CREATE TABLE IF NOT EXISTS` never updates an existing server's
     * schema. Older Spawner tables lacked owner_faction, which made every
     * current SELECT/UPSERT fail immediately after an upgrade.
     */
    private void migrateColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "owner_faction", "VARCHAR(64) NULL");
        addColumnIfMissing(connection, "placed_at_data", "TEXT NULL");
    }

    private static void addColumnIfMissing(Connection connection, String column, String definition) throws SQLException {
        try (PreparedStatement probe = connection.prepareStatement("SELECT " + column + " FROM spawners WHERE 1 = 0")) {
            probe.executeQuery();
        } catch (SQLException missingColumn) {
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE spawners ADD COLUMN " + column + " " + definition)) {
                alter.executeUpdate();
            }
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
                        results.getString("owner_faction"),
                        parseAges(results.getString("placed_at_data"), results.getInt("stack_size"))));
            }
        }
        return spawners;
    }

    public void save(Location location, SpawnerData data) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setString(1, location.getWorld().getName());
            statement.setInt(2, location.getBlockX());
            statement.setInt(3, location.getBlockY());
            statement.setInt(4, location.getBlockZ());
            statement.setString(5, data.mobType().name());
            statement.setInt(6, data.stackSize());
            statement.setString(7, data.ownerFactionTag());
            statement.setString(8, encodeAges(data.placedAtMillis()));
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

    private static String encodeAges(List<Long> ages) {
        return ages.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> parseAges(String encoded, int stackSize) {
        List<Long> ages = new ArrayList<>();
        if (encoded != null && !encoded.isBlank()) {
            for (String token : encoded.split(",")) {
                try {
                    ages.add(Long.parseLong(token));
                } catch (NumberFormatException ignored) {
                    // Ignore one corrupt entry; the data object will not let
                    // it turn into an artificially aged spawner.
                }
            }
        }
        int count = Math.max(1, stackSize);
        while (ages.size() < count) {
            // Legacy rows had only a stack count. Never invent a historical
            // age for them; make the missing individual spawners fresh.
            ages.add(System.currentTimeMillis());
        }
        if (ages.size() > count) {
            return new ArrayList<>(ages.subList(0, count));
        }
        return ages;
    }

    public record StoredSpawner(String world, int x, int y, int z, EntityType mobType, int stackSize,
                                 String ownerFactionTag, List<Long> placedAtMillis) {
    }
}
