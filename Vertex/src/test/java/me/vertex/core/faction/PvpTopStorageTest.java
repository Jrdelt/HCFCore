package me.vertex.core.faction;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpTopStorageTest {
    @TempDir Path dataFolder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void repeatedOperationIsCreditedExactlyOnce() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        PvpTopStorage storage = new PvpTopStorage(database);
        storage.init();
        storage.init();

        PvpTopStorage.AwardResult first = storage.award(7, 10L, "koth:daily",
                "koth:daily:activation-1", null, 100L);
        PvpTopStorage.AwardResult duplicate = storage.award(7, 10L, "koth:daily",
                "koth:daily:activation-1", null, 101L);

        assertTrue(first.awarded());
        assertEquals(10L, first.total());
        assertFalse(duplicate.awarded());
        assertEquals(10L, duplicate.total());
        assertEquals(1, count("pvptop_log"));
    }

    @Test
    void overflowRollsBackScoreAndAuditTogether() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        PvpTopStorage storage = new PvpTopStorage(database);
        storage.init();

        storage.award(9, Long.MAX_VALUE, "outpost:one", "outpost:one:1", null, 100L);
        assertThrows(java.sql.SQLException.class, () -> storage.award(
                9, 1L, "outpost:two", "outpost:two:1", null, 101L));

        assertEquals(Long.MAX_VALUE, storage.load().get(9));
        assertEquals(1, count("pvptop_log"));
    }

    private int count(String table) throws Exception {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getInt(1);
        }
    }
}
