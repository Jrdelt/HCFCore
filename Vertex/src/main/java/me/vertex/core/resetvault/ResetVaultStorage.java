package me.vertex.core.resetvault;

import me.vertex.core.storage.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative SQL persistence layer for the Reset Vault module.
 * Completely independent of seasonal map cleanup tables.
 */
public final class ResetVaultStorage {

    private final Database database;

    public ResetVaultStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        String autoId = sqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "INT AUTO_INCREMENT PRIMARY KEY";

        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rv_player_data (
                        uuid CHAR(36) PRIMARY KEY,
                        last_ign VARCHAR(16) NOT NULL,
                        bonus_slots INT NOT NULL,
                        items LONGBLOB NOT NULL,
                        updated_at BIGINT NOT NULL
                    )""");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS rv_access_blocks ("
                    + "id " + autoId + ", "
                    + "world VARCHAR(64) NOT NULL, "
                    + "x INT NOT NULL, "
                    + "y INT NOT NULL, "
                    + "z INT NOT NULL, "
                    + "hologram_id VARCHAR(128) NOT NULL)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS rv_backups ("
                    + "id " + autoId + ", "
                    + "timestamp BIGINT NOT NULL, "
                    + "map_label VARCHAR(64) NOT NULL, "
                    + "initiator VARCHAR(64) NOT NULL, "
                    + "vault_count INT NOT NULL, "
                    + "compressed_size BIGINT NOT NULL, "
                    + "checksum VARCHAR(64) NOT NULL, "
                    + "status VARCHAR(32) NOT NULL, "
                    + "created_at BIGINT NOT NULL)");

            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rv_backup_data (
                        backup_id INT PRIMARY KEY,
                        data LONGBLOB NOT NULL
                    )""");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS rv_restore_history ("
                    + "id " + autoId + ", "
                    + "backup_id INT NOT NULL, "
                    + "restored_at BIGINT NOT NULL, "
                    + "restored_by VARCHAR(64) NOT NULL)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS rv_audit_log ("
                    + "id " + autoId + ", "
                    + "timestamp BIGINT NOT NULL, "
                    + "action VARCHAR(64) NOT NULL, "
                    + "actor VARCHAR(64) NOT NULL, "
                    + "target_uuid VARCHAR(36) NOT NULL, "
                    + "detail TEXT NOT NULL)");

            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rv_phase (
                        id INT PRIMARY KEY DEFAULT 1,
                        phase VARCHAR(32) NOT NULL
                    )""");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS rv_blacklist ("
                    + "id " + autoId + ", "
                    + "entry_type VARCHAR(16) NOT NULL, "
                    + "entry_key VARCHAR(128) NOT NULL, "
                    + "rejection_key VARCHAR(128) NOT NULL)");
        }
    }

    // ------------------------------------------------------------------
    // Global Phase
    // ------------------------------------------------------------------

    public ResetVaultPhase loadPhase() throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT phase FROM rv_phase WHERE id = 1")) {
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    try {
                        return ResetVaultPhase.valueOf(rs.getString(1));
                    } catch (IllegalArgumentException ignored) {
                        return ResetVaultPhase.CLOSED;
                    }
                }
            }
        }
        return ResetVaultPhase.CLOSED;
    }

    public void savePhase(ResetVaultPhase phase) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO rv_phase (id, phase) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET phase = excluded.phase"
                : "INSERT INTO rv_phase (id, phase) VALUES (1, ?) ON DUPLICATE KEY UPDATE phase = VALUES(phase)";
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, phase.name());
            s.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Player Vault Data
    // ------------------------------------------------------------------

    public ResetVaultData loadPlayerData(UUID uuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT last_ign, bonus_slots, items FROM rv_player_data WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    String ign = rs.getString(1);
                    int bonusSlots = rs.getInt(2);
                    byte[] bytes = rs.getBytes(3);
                    ItemStack[] items;
                    try {
                        items = ItemStack.deserializeItemsFromBytes(bytes);
                    } catch (Exception e) {
                        items = new ItemStack[0];
                    }
                    return new ResetVaultData(uuid, ign, bonusSlots, Arrays.asList(items));
                }
            }
        }
        return null;
    }

    public void savePlayerData(ResetVaultData data) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? """
                INSERT INTO rv_player_data (uuid, last_ign, bonus_slots, items, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    last_ign = excluded.last_ign,
                    bonus_slots = excluded.bonus_slots,
                    items = excluded.items,
                    updated_at = excluded.updated_at"""
                : """
                INSERT INTO rv_player_data (uuid, last_ign, bonus_slots, items, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    last_ign = VALUES(last_ign),
                    bonus_slots = VALUES(bonus_slots),
                    items = VALUES(items),
                    updated_at = VALUES(updated_at)""";

        byte[] itemsBytes = ItemStack.serializeItemsAsBytes(data.items().toArray(new ItemStack[0]));
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, data.uuid().toString());
            s.setString(2, data.lastKnownIgn());
            s.setInt(3, data.permanentBonusSlots());
            s.setBytes(4, itemsBytes);
            s.setLong(5, System.currentTimeMillis());
            s.executeUpdate();
        }
    }

    public Map<UUID, ResetVaultData> loadAllPlayerData() throws SQLException {
        Map<UUID, ResetVaultData> result = new LinkedHashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT uuid, last_ign, bonus_slots, items FROM rv_player_data");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                String ign = rs.getString(2);
                int bonusSlots = rs.getInt(3);
                byte[] bytes = rs.getBytes(4);
                ItemStack[] items;
                try {
                    items = ItemStack.deserializeItemsFromBytes(bytes);
                } catch (Exception e) {
                    items = new ItemStack[0];
                }
                result.put(uuid, new ResetVaultData(uuid, ign, bonusSlots, Arrays.asList(items)));
            }
        }
        return result;
    }

    public void saveAllPlayerData(Map<UUID, ResetVaultData> allData) throws SQLException {
        try (Connection c = database.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM rv_player_data");
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO rv_player_data (uuid, last_ign, bonus_slots, items, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                    long now = System.currentTimeMillis();
                    for (ResetVaultData data : allData.values()) {
                        byte[] itemsBytes = ItemStack.serializeItemsAsBytes(data.items().toArray(new ItemStack[0]));
                        s.setString(1, data.uuid().toString());
                        s.setString(2, data.lastKnownIgn());
                        s.setInt(3, data.permanentBonusSlots());
                        s.setBytes(4, itemsBytes);
                        s.setLong(5, now);
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    // ------------------------------------------------------------------
    // Physical Access Blocks
    // ------------------------------------------------------------------

    public record AccessBlockRow(int id, String world, int x, int y, int z, String hologramId) {}

    public List<AccessBlockRow> loadAccessBlocks() throws SQLException {
        List<AccessBlockRow> result = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, world, x, y, z, hologram_id FROM rv_access_blocks ORDER BY id ASC");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                result.add(new AccessBlockRow(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getInt(4),
                        rs.getInt(5),
                        rs.getString(6)
                ));
            }
        }
        return result;
    }

    public int saveAccessBlock(String world, int x, int y, int z, String hologramId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO rv_access_blocks (world, x, y, z, hologram_id) VALUES (?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, world);
            s.setInt(2, x);
            s.setInt(3, y);
            s.setInt(4, z);
            s.setString(5, hologramId);
            s.executeUpdate();
            try (ResultSet rs = s.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated ID for access block");
    }

    public void deleteAccessBlock(int id) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM rv_access_blocks WHERE id = ?")) {
            s.setInt(1, id);
            s.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Backups & Snapshots
    // ------------------------------------------------------------------

    public record BackupRecord(
            int id,
            long timestamp,
            String mapLabel,
            String initiator,
            int vaultCount,
            long compressedSize,
            String checksum,
            String status,
            long createdAt
    ) {
        public boolean isSelectable() {
            return "COMPLETE".equals(status) || "PRE_RESTORE".equals(status) || "PRE_UNDO".equals(status);
        }
    }

    public int saveBackup(long timestamp, String mapLabel, String initiator, int vaultCount,
                           long compressedSize, String checksum, String status, byte[] data) throws SQLException {
        int backupId = -1;
        try (Connection c = database.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO rv_backups (timestamp, map_label, initiator, vault_count, compressed_size, checksum, status, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    s.setLong(1, timestamp);
                    s.setString(2, mapLabel);
                    s.setString(3, initiator);
                    s.setInt(4, vaultCount);
                    s.setLong(5, compressedSize);
                    s.setString(6, checksum);
                    s.setString(7, status);
                    s.setLong(8, System.currentTimeMillis());
                    s.executeUpdate();
                    try (ResultSet rs = s.getGeneratedKeys()) {
                        if (rs.next()) {
                            backupId = rs.getInt(1);
                        }
                    }
                }
                if (backupId == -1) {
                    throw new SQLException("Could not generate ID for backup");
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO rv_backup_data (backup_id, data) VALUES (?, ?)")) {
                    s.setInt(1, backupId);
                    s.setBytes(2, data);
                    s.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
        return backupId;
    }

    public List<BackupRecord> loadBackupHistory() throws SQLException {
        List<BackupRecord> list = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, timestamp, map_label, initiator, vault_count, compressed_size, checksum, status, created_at "
                             + "FROM rv_backups ORDER BY id DESC");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                list.add(new BackupRecord(
                        rs.getInt(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getLong(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getLong(9)
                ));
            }
        }
        return list;
    }

    public BackupRecord loadBackupRecord(int id) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, timestamp, map_label, initiator, vault_count, compressed_size, checksum, status, created_at "
                             + "FROM rv_backups WHERE id = ?")) {
            s.setInt(1, id);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    return new BackupRecord(
                            rs.getInt(1),
                            rs.getLong(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getInt(5),
                            rs.getLong(6),
                            rs.getString(7),
                            rs.getString(8),
                            rs.getLong(9)
                    );
                }
            }
        }
        return null;
    }

    public byte[] loadBackupData(int backupId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT data FROM rv_backup_data WHERE backup_id = ?")) {
            s.setInt(1, backupId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes(1);
                }
            }
        }
        return null;
    }

    public void saveRestoreHistory(int backupId, String restoredBy, long restoredAt) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO rv_restore_history (backup_id, restored_at, restored_by) VALUES (?, ?, ?)")) {
            s.setInt(1, backupId);
            s.setLong(2, restoredAt);
            s.setString(3, restoredBy);
            s.executeUpdate();
        }
    }

    public int findLatestRestoreBackupId() throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT backup_id FROM rv_restore_history ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    public int findLatestPreRestoreBackupId() throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id FROM rv_backups WHERE status = 'PRE_RESTORE' ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Audit Log
    // ------------------------------------------------------------------

    public void appendAuditLog(long timestamp, String action, String actor, String targetUuid, String detail) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO rv_audit_log (timestamp, action, actor, target_uuid, detail) VALUES (?, ?, ?, ?, ?)")) {
            s.setLong(1, timestamp);
            s.setString(2, action);
            s.setString(3, actor);
            s.setString(4, targetUuid);
            s.setString(5, detail);
            s.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Blacklist
    // ------------------------------------------------------------------

    public record BlacklistEntry(int id, String entryType, String entryKey, String rejectionKey) {}

    public List<BlacklistEntry> loadBlacklist() throws SQLException {
        List<BlacklistEntry> list = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id, entry_type, entry_key, rejection_key FROM rv_blacklist ORDER BY id ASC");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                list.add(new BlacklistEntry(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)
                ));
            }
        }
        return list;
    }

    public int addBlacklistEntry(String type, String key, String rejectionKey) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO rv_blacklist (entry_type, entry_key, rejection_key) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, type);
            s.setString(2, key);
            s.setString(3, rejectionKey == null ? "" : rejectionKey);
            s.executeUpdate();
            try (ResultSet rs = s.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated ID for blacklist entry");
    }

    public void removeBlacklistEntry(int id) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM rv_blacklist WHERE id = ?")) {
            s.setInt(1, id);
            s.executeUpdate();
        }
    }

    public void compactBlacklist(List<BlacklistEntry> entries) throws SQLException {
        try (Connection c = database.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM rv_blacklist");
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO rv_blacklist (entry_type, entry_key, rejection_key) VALUES (?, ?, ?)")) {
                    for (BlacklistEntry entry : entries) {
                        s.setString(1, entry.entryType());
                        s.setString(2, entry.entryKey());
                        s.setString(3, entry.rejectionKey());
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }
}
