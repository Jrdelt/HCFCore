package me.vertex.core.storage;

import me.vertex.core.blueprint.BlueprintStorage;
import me.vertex.core.collector.ChunkCollectorStorage;
import me.vertex.core.spawner.SpawnerStorage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies every row this plugin owns from one database backend to the
 * other, so switching between the local SQLite file and a MySQL server
 * doesn't mean abandoning the data already in the old one.
 *
 * <p>Auto-generated id columns are deliberately not copied -- the target
 * assigns its own, and nothing outside a single running build references
 * a blueprint id, so regenerating them is safe. Everything else is copied
 * verbatim, including the serialized ItemStack blobs in the death
 * history, which are engine-independent bytes.
 */
public final class StorageMigrator {

    /**
     * Every table, with the columns worth copying, in an order that could
     * carry foreign keys if any were ever added.
     */
    private static final Map<String, List<String>> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("kit_cooldowns", List.of("uuid", "kit_name", "available_at"));
        TABLES.put("ability_cooldowns", List.of("uuid", "ability_id", "available_at"));
        TABLES.put("user_locale", List.of("uuid", "locale"));
        TABLES.put("player_deaths", List.of("uuid", "timestamp", "cause", "killer_name",
                "items", "helmet", "chestplate", "leggings", "boots", "offhand"));
        TABLES.put("spawners", List.of("world", "x", "y", "z", "mob_type", "stack_size", "owner_faction"));
        TABLES.put("chunk_collectors", List.of("world", "x", "y", "z", "owner_faction", "owner_uuid"));
        TABLES.put("blueprint_builds", List.of("world", "x", "y", "z", "template", "owner_uuid",
                "owner_faction", "current_index", "started_at"));
    }

    private StorageMigrator() {
    }

    /** Row counts per table, plus the total, as actually copied. */
    public record Result(Map<String, Integer> rowsPerTable, int total) {
    }

    /** @return how many rows the target database already holds, across every table. */
    public static int countRows(Database database) throws SQLException {
        ensureSchema(database);
        int total = 0;
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : TABLES.keySet()) {
                try (ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    if (results.next()) {
                        total += results.getInt(1);
                    }
                }
            }
        }
        return total;
    }

    /**
     * Copies everything from {@code source} into {@code target},
     * replacing whatever the target held. Both databases must already be
     * reachable; the target's schema is created if it doesn't exist yet.
     */
    public static Result migrate(Database source, Database target) throws SQLException {
        ensureSchema(source);
        ensureSchema(target);

        Map<String, Integer> copied = new LinkedHashMap<>();
        int total = 0;

        try (Connection from = source.getConnection();
             Connection to = target.getConnection()) {
            boolean previousAutoCommit = to.getAutoCommit();
            to.setAutoCommit(false);
            try {
                // Wipe first so a re-run is idempotent rather than
                // duplicating every row it already copied last time.
                try (Statement wipe = to.createStatement()) {
                    List<String> reversed = TABLES.keySet().stream().toList().reversed();
                    for (String table : reversed) {
                        wipe.executeUpdate("DELETE FROM " + table);
                    }
                }

                for (Map.Entry<String, List<String>> entry : TABLES.entrySet()) {
                    int rows = copyTable(from, to, entry.getKey(), entry.getValue());
                    copied.put(entry.getKey(), rows);
                    total += rows;
                }
                to.commit();
            } catch (SQLException e) {
                to.rollback();
                throw e;
            } finally {
                to.setAutoCommit(previousAutoCommit);
            }
        }
        return new Result(copied, total);
    }

    private static int copyTable(Connection from, Connection to, String table, List<String> columns)
            throws SQLException {
        String columnList = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        String select = "SELECT " + columnList + " FROM " + table;
        String insert = "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")";

        int rows = 0;
        try (PreparedStatement read = from.prepareStatement(select);
             ResultSet results = read.executeQuery();
             PreparedStatement write = to.prepareStatement(insert)) {
            while (results.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    // getObject/setObject round-trips every type these
                    // tables use -- including the byte[] blobs -- the same
                    // way on both drivers.
                    write.setObject(i, results.getObject(i));
                }
                write.addBatch();
                rows++;
                if (rows % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
        }
        return rows;
    }

    /** Creates any missing tables, using whichever dialect that database speaks. */
    private static void ensureSchema(Database database) throws SQLException {
        new SqlStorage(database).init();
        new SpawnerStorage(database).init();
        new ChunkCollectorStorage(database).init();
        new BlueprintStorage(database).init();
    }

    /**
     * Rewrites just the {@code storage.type} value in config.yml, in
     * place. Deliberately a line edit rather than
     * {@code plugin.saveConfig()}: Bukkit's own writer drops every comment
     * in the file, and this config's comments are most of its
     * documentation.
     */
    public static void writeStorageType(File configFile, String type) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));

        boolean inStorageSection = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String withoutComment = line.split("#", 2)[0];

            if (inStorageSection) {
                // A new top-level key ends the storage block.
                if (!withoutComment.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                java.util.regex.Matcher matcher = TYPE_LINE.matcher(line);
                if (matcher.matches()) {
                    lines.set(i, matcher.group(1) + "type: " + type + matcher.group(3));
                    Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
                    return;
                }
            } else if (withoutComment.stripTrailing().equals("storage:")) {
                inStorageSection = true;
            }
        }

        // No storage.type present (an older config) -- add the block.
        lines.add("");
        lines.add("storage:");
        lines.add("  type: " + type);
        Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static final java.util.regex.Pattern TYPE_LINE =
            java.util.regex.Pattern.compile("^(\\s+)type:\\s*[^#]*?(\\s*)(#.*)?$");
}
