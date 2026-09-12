package me.vertex.core.factions;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Durable audit trail for every successful or attempted /fa override. */
public final class AdminAuditStorage {
    private final Database database;

    public AdminAuditStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        String id = database.dialect() == Database.Dialect.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS vertex_faction_admin_audit (id " + id
                        + ", actor_uuid VARCHAR(36), actor_name VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL, target VARCHAR(128), details VARCHAR(1024), success BOOLEAN NOT NULL, created_at BIGINT NOT NULL)")) {
            statement.executeUpdate();
        }
    }

    public void insert(String actorUuid, String actorName, String action, String target,
            String details, boolean success) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vertex_faction_admin_audit(actor_uuid,actor_name,action,target,details,success,created_at) VALUES(?,?,?,?,?,?,?)")) {
            statement.setString(1, actorUuid);
            statement.setString(2, actorName);
            statement.setString(3, action);
            statement.setString(4, target);
            statement.setString(5, details);
            statement.setBoolean(6, success);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
