package me.hcfcore.core.storage;

import me.hcfcore.core.staff.Death;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MySQLStorage implements Storage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS kit_cooldowns (
                uuid CHAR(36) NOT NULL,
                kit_name VARCHAR(64) NOT NULL,
                available_at BIGINT NOT NULL,
                PRIMARY KEY (uuid, kit_name)
            )""";

    private static final String SELECT_COOLDOWNS = "SELECT kit_name, available_at FROM kit_cooldowns WHERE uuid = ?";
    private static final String UPSERT_COOLDOWN = """
            INSERT INTO kit_cooldowns (uuid, kit_name, available_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE available_at = VALUES(available_at)""";

    private static final String CREATE_ABILITY_TABLE = """
            CREATE TABLE IF NOT EXISTS ability_cooldowns (
                uuid CHAR(36) NOT NULL,
                ability_id VARCHAR(64) NOT NULL,
                available_at BIGINT NOT NULL,
                PRIMARY KEY (uuid, ability_id)
            )""";

    private static final String SELECT_ABILITY_COOLDOWNS =
            "SELECT ability_id, available_at FROM ability_cooldowns WHERE uuid = ?";
    private static final String UPSERT_ABILITY_COOLDOWN = """
            INSERT INTO ability_cooldowns (uuid, ability_id, available_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE available_at = VALUES(available_at)""";

    private static final String CREATE_LOCALE_TABLE = """
            CREATE TABLE IF NOT EXISTS user_locale (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                locale VARCHAR(16) NOT NULL
            )""";

    private static final String SELECT_LOCALE = "SELECT locale FROM user_locale WHERE uuid = ?";
    private static final String UPSERT_LOCALE = """
            INSERT INTO user_locale (uuid, locale) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE locale = VALUES(locale)""";

    private static final String CREATE_DEATH_TABLE = """
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

    private static final String INSERT_DEATH = """
            INSERT INTO player_deaths (uuid, timestamp, cause, killer_name, items, helmet, chestplate, leggings, boots, offhand)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_DEATHS = """
            SELECT timestamp, cause, killer_name, items, helmet, chestplate, leggings, boots, offhand
            FROM player_deaths WHERE uuid = ? ORDER BY timestamp DESC LIMIT ?""";

    /** How long death-inventory history is retained before being pruned. */
    private static final long DEATH_RETENTION_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(14);

    private static final String CLEANUP_DEATHS = "DELETE FROM player_deaths WHERE uuid = ? AND timestamp < ?";

    private final Database database;

    public MySQLStorage(Database database) {
        this.database = database;
    }

    @Override
    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_TABLE);
            statement.executeUpdate(CREATE_ABILITY_TABLE);
            statement.executeUpdate(CREATE_LOCALE_TABLE);
            statement.executeUpdate(CREATE_DEATH_TABLE);
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
             PreparedStatement statement = connection.prepareStatement(UPSERT_COOLDOWN)) {
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
             PreparedStatement statement = connection.prepareStatement(UPSERT_ABILITY_COOLDOWN)) {
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
             PreparedStatement statement = connection.prepareStatement(UPSERT_LOCALE)) {
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
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_DEATHS)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long timestamp = resultSet.getLong("timestamp");
                    String cause = resultSet.getString("cause");
                    String killerName = resultSet.getString("killer_name");
                    List<ItemStack> items = deserializeItemList(resultSet.getBytes("items"));
                    ItemStack helmet = deserializeItem(resultSet.getBytes("helmet"));
                    ItemStack chestplate = deserializeItem(resultSet.getBytes("chestplate"));
                    ItemStack leggings = deserializeItem(resultSet.getBytes("leggings"));
                    ItemStack boots = deserializeItem(resultSet.getBytes("boots"));
                    ItemStack offhand = deserializeItem(resultSet.getBytes("offhand"));

                    Death death = new Death(timestamp, cause, killerName, items, helmet, chestplate, leggings, boots, offhand);
                    deaths.add(death);
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
