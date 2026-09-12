package me.vertex.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Small, dialect-neutral helpers for additive schema migrations. */
public final class SqlSchema {
    private SqlSchema() {
    }

    /** Adds a column only when a zero-row probe confirms it is absent. */
    public static void ensureColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        try (PreparedStatement probe = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE 1 = 0")) {
            probe.executeQuery();
            return;
        } catch (SQLException missing) {
            try (Statement alter = connection.createStatement()) {
                alter.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException racedOrFailed) {
                // Shared MySQL shards can execute startup migrations at the
                // same time. If the other shard won the race, the column is
                // now present and this initializer can continue safely.
                try (PreparedStatement verify = connection.prepareStatement(
                        "SELECT " + column + " FROM " + table + " WHERE 1 = 0")) {
                    verify.executeQuery();
                    return;
                } catch (SQLException stillMissing) {
                    racedOrFailed.addSuppressed(stillMissing);
                    throw racedOrFailed;
                }
            }
        }
    }

    /** Metadata-safe table probe shared by optional modules and migrations. */
    public static boolean tableExists(Connection connection, String table) throws SQLException {
        String catalog = connection.getCatalog();
        try (ResultSet rows = connection.getMetaData().getTables(catalog, null, table,
                new String[]{"TABLE"})) {
            if (rows.next()) return true;
        }
        try (ResultSet rows = connection.getMetaData().getTables(catalog, null, "%",
                new String[]{"TABLE"})) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    /** Creates a named index without relying on dialect-specific IF NOT EXISTS syntax. */
    public static void ensureIndex(Connection connection, String table, String index,
            boolean unique, String... columns) throws SQLException {
        String catalog = connection.getCatalog();
        if (hasIndex(connection, catalog, table, index)
                || hasIndex(connection, catalog, "%", index)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + index
                    + " ON " + table + " (" + String.join(", ", columns) + ")");
        } catch (SQLException racedOrFailed) {
            if (hasIndex(connection, catalog, table, index)
                    || hasIndex(connection, catalog, "%", index)) {
                return;
            }
            throw racedOrFailed;
        }
    }

    private static boolean hasIndex(Connection connection, String catalog, String table, String index)
            throws SQLException {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(catalog, null, table,
                false, false)) {
            while (rows.next()) {
                String found = rows.getString("INDEX_NAME");
                if (found != null && index.equalsIgnoreCase(found)) return true;
            }
        }
        return false;
    }

    /** Upgrades an existing MySQL binary payload column without rebuilding it every boot. */
    public static void ensureLongBlob(Connection connection, Database.Dialect dialect,
            String table, String column, boolean nullable) throws SQLException {
        if (dialect != Database.Dialect.MYSQL) return;
        ColumnInfo info = columnInfo(connection, table, column);
        if (info != null && "LONGBLOB".equalsIgnoreCase(info.typeName())) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " MODIFY " + column
                    + " LONGBLOB " + (nullable ? "NULL" : "NOT NULL"));
        }
    }

    /** Expands a MySQL VARCHAR column used by shard-qualified identifiers. */
    public static void ensureVarcharSize(Connection connection, Database.Dialect dialect,
            String table, String column, int minimumSize, boolean nullable) throws SQLException {
        if (dialect != Database.Dialect.MYSQL) return;
        int size = Math.max(1, minimumSize);
        ColumnInfo info = columnInfo(connection, table, column);
        if (info != null && info.size() >= size) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " MODIFY " + column
                    + " VARCHAR(" + size + ") " + (nullable ? "NULL" : "NOT NULL"));
        }
    }

    private static ColumnInfo columnInfo(Connection connection, String table, String column)
            throws SQLException {
        String catalog = connection.getCatalog();
        try (ResultSet rows = connection.getMetaData().getColumns(catalog, null, table, column)) {
            if (rows.next()) return new ColumnInfo(rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE"));
        }
        try (ResultSet rows = connection.getMetaData().getColumns(catalog, null, "%", "%")) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                    return new ColumnInfo(rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE"));
                }
            }
        }
        return null;
    }

    private record ColumnInfo(String typeName, int size) { }

    /**
     * Locks a faction's lifecycle row when native factions are installed.
     * Standalone storage tests created before native factions remain valid,
     * but a live server can never recreate module data after disband.
     */
    public static boolean lockFactionIfPresent(Connection connection, Database.Dialect dialect,
            int factionId) throws SQLException {
        if (!tableExists(connection, "vertex_factions")) return true;
        String sql = "SELECT id FROM vertex_factions WHERE id=?"
                + (dialect == Database.Dialect.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }
}
