package me.hcfcore.core.blueprint;

import me.hcfcore.core.storage.Database;
import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists just enough about an active build to resume it after a
 * restart: where it is, which template, whose it is, and how far it got.
 * The clipboard itself is re-loaded from the same .schem file on resume
 * (deterministic iteration order means re-flattening it produces the same
 * position list), so there's no need to persist the block list itself.
 */
public final class BlueprintStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS blueprint_builds (
                id INT AUTO_INCREMENT PRIMARY KEY,
                world VARCHAR(64) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                template VARCHAR(64) NOT NULL,
                owner_uuid CHAR(36) NOT NULL,
                owner_faction VARCHAR(64) NULL,
                current_index INT NOT NULL,
                started_at BIGINT NOT NULL
            )""";

    private static final String SELECT_ALL = """
            SELECT id, world, x, y, z, template, owner_uuid, owner_faction, current_index, started_at
            FROM blueprint_builds""";
    private static final String INSERT = """
            INSERT INTO blueprint_builds (world, x, y, z, template, owner_uuid, owner_faction, current_index, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String UPDATE_PROGRESS = "UPDATE blueprint_builds SET current_index = ? WHERE id = ?";
    private static final String DELETE = "DELETE FROM blueprint_builds WHERE id = ?";

    private final Database database;

    public BlueprintStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public List<StoredBuild> loadAll() throws SQLException {
        List<StoredBuild> builds = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                builds.add(new StoredBuild(
                        results.getInt("id"),
                        results.getString("world"),
                        results.getInt("x"),
                        results.getInt("y"),
                        results.getInt("z"),
                        results.getString("template"),
                        results.getString("owner_uuid"),
                        results.getString("owner_faction"),
                        results.getInt("current_index"),
                        results.getLong("started_at")));
            }
        }
        return builds;
    }

    /** @return the generated id for the new row. */
    public int insert(Location location, String template, String ownerUuid, String ownerFaction, long startedAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, location.getWorld().getName());
            statement.setInt(2, location.getBlockX());
            statement.setInt(3, location.getBlockY());
            statement.setInt(4, location.getBlockZ());
            statement.setString(5, template);
            statement.setString(6, ownerUuid);
            statement.setString(7, ownerFaction);
            statement.setInt(8, 0);
            statement.setLong(9, startedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public void updateProgress(int id, int currentIndex) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_PROGRESS)) {
            statement.setInt(1, currentIndex);
            statement.setInt(2, id);
            statement.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    public record StoredBuild(int id, String world, int x, int y, int z, String template, String ownerUuid,
                               String ownerFaction, int currentIndex, long startedAt) {
    }
}
