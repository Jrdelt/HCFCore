package me.vertex.core.dupe;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DupeStorage} directly against a real local (SQLite)
 * database -- the same class of bug that previously slipped through
 * (an {@code ORDER BY} with no tiebreaker on a column that can legitimately
 * tie) is checked explicitly here with rows sharing the exact same
 * {@code created_at} millisecond.
 */
class DupeStorageTest {

    @TempDir
    Path dataFolder;

    private Database database;
    private DupeStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new DupeStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private static DupeCase caseFor(String id, String fingerprint, long createdAt) {
        return new DupeCase(id, fingerprint, "holder-uuid", "HolderName", "item-1", "NETHERITE_SWORD",
                "duplicate-item-id", "[]", DupeCase.STATUS_OPEN, createdAt, null, 0L, null);
    }

    @Test
    void createInsertsANewOpenCase() throws Exception {
        assertTrue(storage.create(caseFor("case-1", "fp-1", 1000L)));
        DupeCase loaded = storage.find("case-1");
        assertEquals(DupeCase.STATUS_OPEN, loaded.status());
        assertTrue(loaded.unresolved());
    }

    /**
     * The fingerprint UNIQUE constraint plus ON CONFLICT/INSERT IGNORE is
     * what makes case creation atomic and race-safe: two concurrent
     * detections of the exact same evidence set must never open two cases.
     */
    @Test
    void creatingTheSameFingerprintTwiceOnlyOpensOneCase() throws Exception {
        assertTrue(storage.create(caseFor("case-1", "fp-shared", 1000L)));
        assertFalse(storage.create(caseFor("case-2", "fp-shared", 1000L)),
                "a duplicate fingerprint must not open a second case");
        assertEquals(1, storage.unresolvedCount());
    }

    @Test
    void openCasesOrderingIsDeterministicWhenCreatedAtTies() throws Exception {
        // All three rows share the exact same created_at millisecond -- the
        // scenario that previously exposed a missing secondary sort key.
        storage.create(caseFor("case-c", "fp-c", 5000L));
        storage.create(caseFor("case-a", "fp-a", 5000L));
        storage.create(caseFor("case-b", "fp-b", 5000L));

        List<DupeCase> first = storage.openCases(0, 10);
        List<DupeCase> second = storage.openCases(0, 10);

        assertEquals(3, first.size());
        assertEquals(first.stream().map(DupeCase::id).toList(), second.stream().map(DupeCase::id).toList(),
                "repeated reads of tied timestamps must return the same order every time");
        // With id as the secondary sort key the order is also alphabetical.
        assertEquals(List.of("case-a", "case-b", "case-c"), first.stream().map(DupeCase::id).toList());
    }

    @Test
    void resolveOnlyTransitionsAnOpenCase() throws Exception {
        storage.create(caseFor("case-1", "fp-1", 1000L));
        assertTrue(storage.resolve("case-1", "Staff", DupeCase.STATUS_RESOLVED, "false positive", 2000L));
        DupeCase loaded = storage.find("case-1");
        assertEquals(DupeCase.STATUS_RESOLVED, loaded.status());
        assertEquals("Staff", loaded.resolvedBy());
        assertFalse(loaded.unresolved());
    }

    @Test
    void resolveFailsWhenTheCaseIsAlreadyClosed() throws Exception {
        storage.create(caseFor("case-1", "fp-1", 1000L));
        assertTrue(storage.resolve("case-1", "StaffOne", DupeCase.STATUS_DISMISSED, "not a dupe", 2000L));
        assertFalse(storage.resolve("case-1", "StaffTwo", DupeCase.STATUS_RESOLVED, "too late", 3000L),
                "a second resolution attempt on an already-closed case must not silently overwrite it");
        DupeCase loaded = storage.find("case-1");
        assertEquals(DupeCase.STATUS_DISMISSED, loaded.status());
        assertEquals("StaffOne", loaded.resolvedBy());
    }

    @Test
    void confirmIsATrackedTerminalStatus() throws Exception {
        storage.create(caseFor("case-1", "fp-1", 1000L));
        assertTrue(storage.resolve("case-1", "Staff", DupeCase.STATUS_CONFIRMED, "genuine duplicate", 2000L));
        DupeCase loaded = storage.find("case-1");
        assertEquals(DupeCase.STATUS_CONFIRMED, loaded.status());
        assertEquals(0, storage.unresolvedCount());
    }

    @Test
    void unresolvedCountOnlyCountsOpenCases() throws Exception {
        storage.create(caseFor("case-1", "fp-1", 1000L));
        storage.create(caseFor("case-2", "fp-2", 1000L));
        storage.resolve("case-1", "Staff", DupeCase.STATUS_RESOLVED, "reviewed", 2000L);
        assertEquals(1, storage.unresolvedCount());
    }
}
