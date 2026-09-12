package me.vertex.core.faction;

import me.vertex.core.factions.FactionRole;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Durable storage for Vertex-owned faction money, experience, and TNT balances. */
public final class FactionBankStorage {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS faction_banks (
                faction_id INT NOT NULL PRIMARY KEY,
                money DOUBLE NOT NULL,
                experience BIGINT NOT NULL,
                tnt BIGINT NOT NULL DEFAULT 0
            )""";
    private static final String SELECT_ALL = "SELECT faction_id, money, experience, tnt FROM faction_banks";
    private static final String UPSERT_MYSQL = """
            INSERT INTO faction_banks (faction_id, money, experience, tnt) VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE money = VALUES(money), experience = VALUES(experience), tnt = VALUES(tnt)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO faction_banks (faction_id, money, experience, tnt) VALUES (?, ?, ?, ?)
            ON CONFLICT(faction_id) DO UPDATE SET money = excluded.money,
                experience = excluded.experience, tnt = excluded.tnt""";
    private static final String DELETE = "DELETE FROM faction_banks WHERE faction_id = ?";

    private final Database database;
    private final String upsert;

    public FactionBankStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
                statement.executeUpdate();
            }
            migrateTntColumn(connection);
        }
    }

    /**
     * `CREATE TABLE IF NOT EXISTS` never updates an existing server's schema.
     * Faction banks predate Vertex owning the TNT balance, so a server
     * upgrading from that version has no tnt column and every SELECT/UPSERT
     * here would fail on startup without this.
     */
    private void migrateTntColumn(Connection connection) throws SQLException {
        try (PreparedStatement probe = connection.prepareStatement("SELECT tnt FROM faction_banks WHERE 1 = 0")) {
            probe.executeQuery();
        } catch (SQLException missingColumn) {
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE faction_banks ADD COLUMN tnt BIGINT NOT NULL DEFAULT 0")) {
                alter.executeUpdate();
            }
        }
    }

    public List<StoredBank> loadAll() throws SQLException {
        List<StoredBank> banks = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                banks.add(new StoredBank(results.getInt("faction_id"), results.getDouble("money"),
                        results.getLong("experience"), results.getLong("tnt")));
            }
        }
        return banks;
    }

    public void save(int factionId, double money, long experience, long tnt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setInt(1, factionId);
            statement.setDouble(2, money);
            statement.setLong(3, experience);
            statement.setLong(4, tnt);
            statement.executeUpdate();
        }
    }

    /**
     * Locks the shared faction row, calculates from the database value, and
     * commits the replacement in one transaction. This is the authoritative
     * path for gameplay mutations when several shards share MySQL.
     */
    public Optional<StoredBank> mutate(int factionId, Function<StoredBank, StoredBank> mutation)
            throws SQLException {
        return mutate(factionId, null, null, null, false, mutation);
    }

    /** Gameplay mutation that checks the acting member's live role permission in the same transaction. */
    public Optional<StoredBank> mutateAuthorized(int factionId, UUID actor,
            FactionRole expectedRole, String action, boolean defaultAllowed,
            Function<StoredBank, StoredBank> mutation) throws SQLException {
        if (actor == null || expectedRole == null || action == null || action.isBlank()) {
            return Optional.empty();
        }
        return mutate(factionId, actor, expectedRole, action, defaultAllowed, mutation);
    }

    private Optional<StoredBank> mutate(int factionId, UUID actor, FactionRole expectedRole,
            String action, boolean defaultAllowed, Function<StoredBank, StoredBank> mutation)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    connection.rollback();
                    return Optional.empty();
                }
                if (actor != null && !authorized(connection, factionId, actor, expectedRole,
                        action, defaultAllowed)) {
                    connection.rollback();
                    return Optional.empty();
                }
                String insert = database.dialect() == Database.Dialect.SQLITE
                        ? "INSERT OR IGNORE INTO faction_banks(faction_id,money,experience,tnt) VALUES(?,0,0,0)"
                        : "INSERT IGNORE INTO faction_banks(faction_id,money,experience,tnt) VALUES(?,0,0,0)";
                try (PreparedStatement ensure = connection.prepareStatement(insert)) {
                    ensure.setInt(1, factionId);
                    ensure.executeUpdate();
                }
                StoredBank current;
                String select = "SELECT money,experience,tnt FROM faction_banks WHERE faction_id=?"
                        + (database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "");
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    statement.setInt(1, factionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Faction bank row disappeared during mutation");
                        current = new StoredBank(factionId, row.getDouble(1), row.getLong(2), row.getLong(3));
                    }
                }
                StoredBank next = mutation.apply(current);
                if (next == null) {
                    connection.rollback();
                    return Optional.empty();
                }
                if (!Double.isFinite(next.money()) || next.money() < 0D
                        || next.experience() < 0L || next.tnt() < 0L) {
                    throw new SQLException("Faction bank mutation produced an invalid balance");
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE faction_banks SET money=?,experience=?,tnt=? WHERE faction_id=?")) {
                    update.setDouble(1, next.money());
                    update.setLong(2, next.experience());
                    update.setLong(3, next.tnt());
                    update.setInt(4, factionId);
                    if (update.executeUpdate() != 1) throw new SQLException("Faction bank update was not committed");
                }
                connection.commit();
                return Optional.of(next);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private boolean authorized(Connection connection, int factionId, UUID actor,
            FactionRole expectedRole, String action, boolean defaultAllowed) throws SQLException {
        if (!SqlSchema.tableExists(connection, "vertex_faction_members")) return false;
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        FactionRole durableRole;
        try (PreparedStatement member = connection.prepareStatement(
                "SELECT role FROM vertex_faction_members WHERE player_uuid=? AND faction_id=?" + suffix)) {
            member.setString(1, actor.toString());
            member.setInt(2, factionId);
            try (ResultSet row = member.executeQuery()) {
                if (!row.next()) return false;
                durableRole = FactionRole.parse(row.getString(1), FactionRole.RECRUIT);
            }
        }
        if (durableRole != expectedRole) return false;
        if (durableRole == FactionRole.LEADER) return true;
        try (PreparedStatement permission = connection.prepareStatement(
                "SELECT allowed FROM vertex_faction_permissions WHERE faction_id=? AND role=? AND action_key=?")) {
            permission.setInt(1, factionId);
            permission.setString(2, durableRole.permissionBucket());
            permission.setString(3, action);
            try (ResultSet row = permission.executeQuery()) {
                return row.next() ? row.getBoolean(1) : defaultAllowed;
            }
        }
    }

    public void delete(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }

    public record StoredBank(int factionId, double money, long experience, long tnt) {
    }
}
