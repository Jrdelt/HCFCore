package me.hcfcore.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;

public final class Database {

    private final HikariDataSource dataSource;

    public Database(FileConfiguration config) {
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "hcfcore");
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
        hikariConfig.setPoolName("HCFCore-Pool");

        this.dataSource = new HikariDataSource(hikariConfig);
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
