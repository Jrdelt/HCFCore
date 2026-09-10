package me.vertex.core.dupe;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQL authority for the staff-only suspected-duplication investigation queue. */
public final class DupeStorage {
    private final Database database;

    public DupeStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        String table = "CREATE TABLE IF NOT EXISTS dupe_cases ("
                + "id VARCHAR(36) PRIMARY KEY, fingerprint VARCHAR(255) NOT NULL UNIQUE,"
                + "holder_uuid VARCHAR(36), holder_name VARCHAR(64), item_id VARCHAR(64), material VARCHAR(64) NOT NULL,"
                + "source VARCHAR(128) NOT NULL, details TEXT NOT NULL, status VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL,"
                + "resolved_by VARCHAR(64), resolved_at BIGINT, resolution TEXT)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(table)) {
            statement.executeUpdate();
        }
    }

    /** @return true only when this evidence created a new open case. */
    public boolean create(DupeCase entry) throws SQLException {
        String insert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO dupe_cases (id, fingerprint, holder_uuid, holder_name, item_id, material, source, details, status, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?) ON CONFLICT(fingerprint) DO NOTHING"
                : "INSERT IGNORE INTO dupe_cases (id, fingerprint, holder_uuid, holder_name, item_id, material, source, details, status, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, entry.id());
            statement.setString(2, entry.fingerprint());
            statement.setString(3, entry.holderUuid());
            statement.setString(4, entry.holderName());
            statement.setString(5, entry.itemId());
            statement.setString(6, entry.material());
            statement.setString(7, entry.source());
            statement.setString(8, entry.details());
            statement.setLong(9, entry.createdAt());
            return statement.executeUpdate() > 0;
        }
    }

    public int unresolvedCount() throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM dupe_cases WHERE status = 'OPEN'" ); ResultSet results = statement.executeQuery()) {
            return results.next() ? results.getInt(1) : 0;
        }
    }

    public List<DupeCase> openCases(int offset, int limit) throws SQLException {
        List<DupeCase> cases = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM dupe_cases WHERE status = 'OPEN' ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?")) {
            statement.setInt(1, Math.max(1, limit));
            statement.setInt(2, Math.max(0, offset));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    cases.add(read(results));
                }
            }
        }
        return cases;
    }

    public DupeCase find(String id) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM dupe_cases WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? read(results) : null;
            }
        }
    }

    public boolean resolve(String id, String staffName, String status, String resolution, long now) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE dupe_cases SET status = ?, resolved_by = ?, resolved_at = ?, resolution = ?"
                        + " WHERE id = ? AND status = 'OPEN'")) {
            statement.setString(1, status);
            statement.setString(2, staffName);
            statement.setLong(3, now);
            statement.setString(4, resolution);
            statement.setString(5, id);
            return statement.executeUpdate() == 1;
        }
    }

    private static DupeCase read(ResultSet results) throws SQLException {
        return new DupeCase(results.getString("id"), results.getString("fingerprint"),
                results.getString("holder_uuid"), results.getString("holder_name"), results.getString("item_id"),
                results.getString("material"), results.getString("source"), results.getString("details"),
                results.getString("status"), results.getLong("created_at"), results.getString("resolved_by"),
                results.getLong("resolved_at"), results.getString("resolution"));
    }
}
