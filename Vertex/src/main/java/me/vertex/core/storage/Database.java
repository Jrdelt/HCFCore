package me.vertex.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public final class Database {

    /** Which SQL dialect the storage classes should write, not just which driver is loaded. */
    public enum Dialect {
        SQLITE,
        MYSQL
    }

    private final HikariDataSource dataSource;
    private final Dialect dialect;

    public Database(FileConfiguration config, File dataFolder) {
        this(config, dataFolder, dialectOf(config.getString("storage.type", "local")));
    }

    /**
     * Opens a connection to a specific backend regardless of what
     * {@code storage.type} currently says -- used when migrating data from
     * one backend to the other, where both must be open at once.
     */
    public Database(FileConfiguration config, File dataFolder, Dialect dialect) {
        this.dialect = dialect;
        HikariConfig hikariConfig = dialect == Dialect.MYSQL
                ? mysqlConfig(config)
                : sqliteConfig(dataFolder);
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    /**
     * Only the literal value "mysql" opts out of the zero-setup local
     * database -- anything else (missing, blank, a typo, "local",
     * "sqlite") falls back to local rather than failing to start, per the
     * "defaults to local unless told otherwise" rule.
     */
    public static Dialect dialectOf(String type) {
        return "mysql".equalsIgnoreCase(type == null ? "" : type.trim())
                ? Dialect.MYSQL
                : Dialect.SQLITE;
    }

    private static HikariConfig mysqlConfig(FileConfiguration config) {
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "vertex");
        String username = config.getString("mysql.username", "root");
        String password = config.getString("mysql.password", "");
        int poolSize = config.getInt("mysql.pool-size", 10);
        boolean useSsl = config.getBoolean("mysql.use-ssl", false);
        boolean allowPublicKeyRetrieval = config.getBoolean(
            "mysql.allow-public-key-retrieval", !useSsl);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useSSL=" + useSsl
            + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
            + "&autoReconnect=true&characterEncoding=utf8");
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(Math.max(2, poolSize / 3));
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setPoolName("Vertex-Pool");
        return hikariConfig;
    }

    private static HikariConfig sqliteConfig(File dataFolder) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "vertex.db");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/'));
        hikariConfig.setPoolName("Vertex-Pool");
        // SQLite serializes writes at the file level -- a single pooled
        // connection avoids "database is locked" errors under concurrent
        // access entirely, rather than working around them after the fact.
        // Everything this plugin does against the database is small,
        // async, and off the main thread already, so the extra
        // serialization has no meaningful impact on a single-server setup.
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.addDataSourceProperty("foreign_keys", "true");
        return hikariConfig;
    }

    public Dialect dialect() {
        return dialect;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
