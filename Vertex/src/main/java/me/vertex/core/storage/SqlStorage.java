package me.vertex.core.storage;

import me.vertex.core.staff.Death;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@link Storage} implementation backing both supported database
 * engines: a local SQLite file (the zero-setup default) or a MySQL/MariaDB
 * server. {@link Database} already picked and connected to one of the two;
 * this class only needs to pick the handful of SQL statements that
 * actually differ between them -- everything else (plain SELECT/DELETE,
 * and every CREATE TABLE except the ones with an auto-incrementing id) is
 * shared verbatim, since SQLite accepts MySQL's column type names
 * (VARCHAR/CHAR/BIGINT/BLOB) via its own type-affinity rules.
 */
public final class SqlStorage implements Storage {

    private static final Logger LOGGER = Logger.getLogger(SqlStorage.class.getName());

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS kit_cooldowns (
                uuid CHAR(36) NOT NULL,
                kit_name VARCHAR(64) NOT NULL,
                available_at BIGINT NOT NULL,
                PRIMARY KEY (uuid, kit_name)
            )""";

    private static final String SELECT_COOLDOWNS = "SELECT kit_name, available_at FROM kit_cooldowns WHERE uuid = ?";
    private static final String UPSERT_COOLDOWN_MYSQL = """
            INSERT INTO kit_cooldowns (uuid, kit_name, available_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE available_at = VALUES(available_at)""";
    private static final String UPSERT_COOLDOWN_SQLITE = """
            INSERT INTO kit_cooldowns (uuid, kit_name, available_at) VALUES (?, ?, ?)
            ON CONFLICT (uuid, kit_name) DO UPDATE SET available_at = excluded.available_at""";

    private static final String CREATE_ABILITY_TABLE = """
            CREATE TABLE IF NOT EXISTS ability_cooldowns (
                uuid CHAR(36) NOT NULL,
                ability_id VARCHAR(64) NOT NULL,
                available_at BIGINT NOT NULL,
                PRIMARY KEY (uuid, ability_id)
            )""";

    private static final String SELECT_ABILITY_COOLDOWNS =
            "SELECT ability_id, available_at FROM ability_cooldowns WHERE uuid = ?";
    private static final String UPSERT_ABILITY_COOLDOWN_MYSQL = """
            INSERT INTO ability_cooldowns (uuid, ability_id, available_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE available_at = VALUES(available_at)""";
    private static final String UPSERT_ABILITY_COOLDOWN_SQLITE = """
            INSERT INTO ability_cooldowns (uuid, ability_id, available_at) VALUES (?, ?, ?)
            ON CONFLICT (uuid, ability_id) DO UPDATE SET available_at = excluded.available_at""";

    private static final String CREATE_LOCALE_TABLE = """
            CREATE TABLE IF NOT EXISTS user_locale (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                locale VARCHAR(16) NOT NULL
            )""";

    private static final String SELECT_LOCALE = "SELECT locale FROM user_locale WHERE uuid = ?";
    private static final String UPSERT_LOCALE_MYSQL = """
            INSERT INTO user_locale (uuid, locale) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE locale = VALUES(locale)""";
    private static final String UPSERT_LOCALE_SQLITE = """
            INSERT INTO user_locale (uuid, locale) VALUES (?, ?)
            ON CONFLICT (uuid) DO UPDATE SET locale = excluded.locale""";

    private static final String CREATE_DEATH_TABLE_MYSQL = """
            CREATE TABLE IF NOT EXISTS player_deaths (
                id INT AUTO_INCREMENT PRIMARY KEY,
                uuid CHAR(36) NOT NULL,
                timestamp BIGINT NOT NULL,
                cause VARCHAR(255) NOT NULL,
                killer_name VARCHAR(16),
                items LONGBLOB NOT NULL,
                helmet BLOB,
                chestplate BLOB,
                leggings BLOB,
                boots BLOB,
                offhand BLOB,
                INDEX (uuid, timestamp DESC)
            )""";
    private static final String CREATE_DEATH_TABLE_SQLITE = """
            CREATE TABLE IF NOT EXISTS player_deaths (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid CHAR(36) NOT NULL,
                timestamp BIGINT NOT NULL,
                cause VARCHAR(255) NOT NULL,
                killer_name VARCHAR(16),
                items LONGBLOB NOT NULL,
                helmet BLOB,
                chestplate BLOB,
                leggings BLOB,
                boots BLOB,
                offhand BLOB
            )""";
    private static final String CREATE_DEATH_INDEX_SQLITE =
            "CREATE INDEX IF NOT EXISTS idx_player_deaths_uuid_timestamp ON player_deaths (uuid, timestamp DESC)";

    private static final String INSERT_DEATH = """
            INSERT INTO player_deaths (uuid, timestamp, cause, killer_name, items, helmet, chestplate, leggings, boots, offhand)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_DEATHS = """
            SELECT timestamp, cause, killer_name, items, helmet, chestplate, leggings, boots, offhand
            FROM player_deaths WHERE uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?""";

    /** Avoid an unbounded DB walk if a legacy server has a large run of damaged rows. */
    private static final int MAX_DEATH_ROWS_TO_SCAN = 500;

    /** How long death-inventory history is retained before being pruned. */
    private static final long DEATH_RETENTION_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(14);

    private static final String CLEANUP_DEATHS = "DELETE FROM player_deaths WHERE uuid = ? AND timestamp < ?";
    private static final String CLEANUP_ALL_DEATHS = "DELETE FROM player_deaths WHERE timestamp < ?";

    private final Database database;
    private final boolean sqlite;
    private final String upsertCooldown;
    private final String upsertAbilityCooldown;
    private final String upsertLocale;
    private final String createDeathTable;

    public SqlStorage(Database database) {
        this.database = database;
        this.sqlite = database.dialect() == Database.Dialect.SQLITE;
        this.upsertCooldown = sqlite ? UPSERT_COOLDOWN_SQLITE : UPSERT_COOLDOWN_MYSQL;
        this.upsertAbilityCooldown = sqlite ? UPSERT_ABILITY_COOLDOWN_SQLITE : UPSERT_ABILITY_COOLDOWN_MYSQL;
        this.upsertLocale = sqlite ? UPSERT_LOCALE_SQLITE : UPSERT_LOCALE_MYSQL;
        this.createDeathTable = sqlite ? CREATE_DEATH_TABLE_SQLITE : CREATE_DEATH_TABLE_MYSQL;
    }

    @Override
    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_TABLE);
            statement.executeUpdate(CREATE_ABILITY_TABLE);
            statement.executeUpdate(CREATE_LOCALE_TABLE);
            statement.executeUpdate(createDeathTable);
            if (sqlite) {
                // MySQL's inline INDEX(...) table constraint has no SQLite
                // equivalent -- it needs its own statement there instead.
                statement.executeUpdate(CREATE_DEATH_INDEX_SQLITE);
            }
        }
    }

    @Override
    public Map<String, Long> loadCooldowns(UUID uuid) throws SQLException {
        Map<String, Long> cooldowns = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_COOLDOWNS)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    cooldowns.put(resultSet.getString("kit_name"), resultSet.getLong("available_at"));
                }
            }
        }
        return cooldowns;
    }

    @Override
    public void saveCooldown(UUID uuid, String kitName, long availableAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsertCooldown)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, kitName.toLowerCase(Locale.ROOT));
            statement.setLong(3, availableAt);
            statement.executeUpdate();
        }
    }

    @Override
    public Map<String, Long> loadAbilityCooldowns(UUID uuid) throws SQLException {
        Map<String, Long> cooldowns = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ABILITY_COOLDOWNS)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    cooldowns.put(resultSet.getString("ability_id"), resultSet.getLong("available_at"));
                }
            }
        }
        return cooldowns;
    }

    @Override
    public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsertAbilityCooldown)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, abilityId.toLowerCase(Locale.ROOT));
            statement.setLong(3, availableAt);
            statement.executeUpdate();
        }
    }

    @Override
    public String loadLocale(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_LOCALE)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("locale") : null;
            }
        }
    }

    @Override
    public void saveLocale(UUID uuid, String locale) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsertLocale)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, locale.toLowerCase(Locale.ROOT));
            statement.executeUpdate();
        }
    }

    @Override
    public void saveDeath(UUID uuid, Death death) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);

                try (PreparedStatement statement = connection.prepareStatement(INSERT_DEATH)) {
                    statement.setString(1, uuid.toString());
                    statement.setLong(2, death.getTimestamp());
                    statement.setString(3, death.getCause());
                    statement.setString(4, death.getKillerName());
                    statement.setBytes(5, serializeItemList(death.getItems()));
                    statement.setBytes(6, serializeItem(death.getHelmet()));
                    statement.setBytes(7, serializeItem(death.getChestplate()));
                    statement.setBytes(8, serializeItem(death.getLeggings()));
                    statement.setBytes(9, serializeItem(death.getBoots()));
                    statement.setBytes(10, serializeItem(death.getOffhand()));
                    statement.executeUpdate();
                }

                cleanupOldDeaths(uuid, connection);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public List<Death> loadDeaths(UUID uuid, int limit) throws SQLException {
        List<Death> deaths = new ArrayList<>();
        if (limit <= 0) {
            return deaths;
        }
        int offset = 0;
        int pageSize = Math.max(limit, 20);
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_DEATHS)) {
            statement.setString(1, uuid.toString());
            while (deaths.size() < limit && offset < MAX_DEATH_ROWS_TO_SCAN) {
                int requested = Math.min(pageSize, MAX_DEATH_ROWS_TO_SCAN - offset);
                statement.setInt(2, requested);
                statement.setInt(3, offset);
                int rowsRead = 0;
                try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rowsRead++;
                    long timestamp = resultSet.getLong("timestamp");
                    try {
                        String cause = resultSet.getString("cause");
                        String killerName = resultSet.getString("killer_name");
                        List<ItemStack> items = deserializeItemList(resultSet.getBytes("items"));
                        ItemStack helmet = deserializeItem(resultSet.getBytes("helmet"));
                        ItemStack chestplate = deserializeItem(resultSet.getBytes("chestplate"));
                        ItemStack leggings = deserializeItem(resultSet.getBytes("leggings"));
                        ItemStack boots = deserializeItem(resultSet.getBytes("boots"));
                        ItemStack offhand = deserializeItem(resultSet.getBytes("offhand"));

                        deaths.add(new Death(timestamp, cause, killerName, items, helmet, chestplate, leggings, boots, offhand));
                    } catch (SQLException e) {
                        // One corrupted row (e.g. a truncated blob) used to
                        // abort the whole query, hiding every OTHER death of
                        // this player's behind it -- /rollback would report
                        // "no previous deaths" even for someone who died
                        // many times, as long as exactly one of those rows
                        // failed to deserialize. Skip just that row instead.
                        LOGGER.log(Level.WARNING,
                                "Skipping a corrupted death record for " + uuid + " (timestamp " + timestamp + ")", e);
                    }
                    if (deaths.size() >= limit) {
                        break;
                    }
                }
                }
                offset += rowsRead;
                if (rowsRead < requested) {
                    break;
                }
            }
        }
        return deaths;
    }

    private void cleanupOldDeaths(UUID uuid, Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLEANUP_DEATHS)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, System.currentTimeMillis() - DEATH_RETENTION_MILLIS);
            statement.executeUpdate();
        }
    }

    @Override
    public void cleanupExpiredDeaths() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLEANUP_ALL_DEATHS)) {
            statement.setLong(1, System.currentTimeMillis() - DEATH_RETENTION_MILLIS);
            statement.executeUpdate();
        }
    }

    private byte[] serializeItem(ItemStack item) throws SQLException {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(item);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new SQLException("Failed to serialize ItemStack", e);
        }
    }

    private byte[] serializeItemList(List<ItemStack> items) throws SQLException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeInt(items.size());
            for (ItemStack item : items) {
                oos.writeObject(item);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new SQLException("Failed to serialize ItemStack list", e);
        }
    }

    private ItemStack deserializeItem(byte[] data) throws SQLException {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) {
            throw new SQLException("Failed to deserialize ItemStack", e);
        }
    }

    private List<ItemStack> deserializeItemList(byte[] data) throws SQLException {
        List<ItemStack> items = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            int size = ois.readInt();
            for (int i = 0; i < size; i++) {
                items.add((ItemStack) ois.readObject());
            }
            return items;
        } catch (Exception e) {
            throw new SQLException("Failed to deserialize ItemStack list", e);
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
