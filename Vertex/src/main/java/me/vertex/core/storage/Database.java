package me.vertex.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;

public final class Database {

    /** Which SQL dialect the storage classes should write, not just which driver is loaded. */
    public enum Dialect {
        SQLITE,
        MYSQL
    }

    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final StampedLock connectionGate = new StampedLock();
    private final AtomicBoolean maintenanceRequested = new AtomicBoolean();

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
        if (maintenanceRequested.get()) {
            throw new SQLException("Vertex storage is in exclusive maintenance mode");
        }
        long stamp = connectionGate.tryReadLock();
        if (stamp == 0L || maintenanceRequested.get()) {
            if (stamp != 0L) {
                connectionGate.unlockRead(stamp);
            }
            throw new SQLException("Vertex storage is in exclusive maintenance mode");
        }
        try {
            return guardedConnection(dataSource.getConnection(), stamp);
        } catch (SQLException | RuntimeException error) {
            connectionGate.unlockRead(stamp);
            throw error;
        }
    }

    /**
     * Stops new SQL and waits for every checked-out connection to close.
     * The lease can open the source snapshot connection while normal storage
     * calls remain blocked.
     */
    public ExclusiveLease beginExclusiveMaintenance(long timeout, TimeUnit unit) throws SQLException {
        if (!maintenanceRequested.compareAndSet(false, true)) {
            throw new SQLException("Vertex storage is already in exclusive maintenance mode");
        }
        long stamp;
        try {
            stamp = connectionGate.tryWriteLock(timeout, unit);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            maintenanceRequested.set(false);
            throw new SQLException("Interrupted while waiting for active storage work to finish", error);
        }
        if (stamp == 0L) {
            maintenanceRequested.set(false);
            throw new SQLException("Timed out waiting for active storage work to finish");
        }
        return new ExclusiveLease(this, stamp);
    }

    public boolean maintenanceActive() {
        return maintenanceRequested.get();
    }

    private Connection guardedConnection(Connection delegate, long stamp) {
        AtomicBoolean released = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(
                Database.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("close") || name.equals("abort")) {
                        if (!released.compareAndSet(false, true)) {
                            return null;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        } finally {
                            connectionGate.unlockRead(stamp);
                        }
                    }
                    if (name.equals("isClosed") && released.get()) {
                        return true;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    public static final class ExclusiveLease implements AutoCloseable {
        private final Database database;
        private final long stamp;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private ExclusiveLease(Database database, long stamp) {
            this.database = database;
            this.stamp = stamp;
        }

        public Connection openConnection() throws SQLException {
            if (!active.get()) {
                throw new SQLException("Storage maintenance lease is no longer active");
            }
            return database.dataSource.getConnection();
        }

        public boolean belongsTo(Database candidate) {
            return active.get() && database == candidate;
        }

        /** Permanently closes this backend before a successful cutover. */
        public void closeBackend() {
            if (!active.get()) {
                throw new IllegalStateException("Storage maintenance lease is no longer active");
            }
            database.close();
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            database.connectionGate.unlockWrite(stamp);
            database.maintenanceRequested.set(false);
        }
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
