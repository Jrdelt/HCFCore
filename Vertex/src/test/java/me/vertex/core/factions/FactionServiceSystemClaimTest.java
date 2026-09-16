package me.vertex.core.factions;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /f admin unclaimall}'s actual plumbing -- {@link
 * FactionService#systemClaimChunks} and {@link
 * FactionService#forceUnclaimAllSystemClaims} -- against real, populated
 * SafeZone and WarZone system claims saved through the same {@link
 * FactionStorage} the live server uses, not a mock. Existed only as
 * command-layer wiring with no dedicated test until this one.
 */
class FactionServiceSystemClaimTest {

    @TempDir
    Path dataFolder;

    private Database database;
    private PluginMock plugin;
    private FactionStorage storage;
    private FactionService factions;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new FactionStorage(database);
        factions = new FactionService(plugin, storage);
        factions.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    @Test
    void unclaimAllReleasesEveryChunkForBothSafezoneAndWarzoneIndependently() throws Exception {
        FactionData safezone = factions.ensureSystemFaction("SafeZone");
        FactionData warzone = factions.ensureSystemFaction("WarZone");

        storage.saveClaim(new ChunkKey("world", 1, 1), safezone.id());
        storage.saveClaim(new ChunkKey("world", 1, 2), safezone.id());
        storage.saveClaim(new ChunkKey("world", 5, 5), warzone.id());
        storage.saveClaim(new ChunkKey("world", 5, 6), warzone.id());
        storage.saveClaim(new ChunkKey("world", 5, 7), warzone.id());
        factions.load();

        assertEquals(2, factions.systemClaimChunks("safezone").size());
        assertEquals(3, factions.systemClaimChunks("warzone").size());

        int safezoneReleased = factions.forceUnclaimAllSystemClaims("safezone").get(5, TimeUnit.SECONDS);
        assertEquals(2, safezoneReleased, "every SafeZone chunk should be released");
        assertTrue(factions.systemClaimChunks("safezone").isEmpty());
        // Releasing SafeZone must never touch WarZone's separate claims.
        assertEquals(3, factions.systemClaimChunks("warzone").size());

        int warzoneReleased = factions.forceUnclaimAllSystemClaims("warzone").get(5, TimeUnit.SECONDS);
        assertEquals(3, warzoneReleased, "every WarZone chunk should be released");
        assertTrue(factions.systemClaimChunks("warzone").isEmpty());

        // Reload straight from the database to prove the release was
        // durably committed, not just cleared from the in-memory cache.
        factions.load();
        assertTrue(factions.systemClaimChunks("safezone").isEmpty());
        assertTrue(factions.systemClaimChunks("warzone").isEmpty());
    }

    @Test
    void unclaimAllOnAnEmptySystemFactionReleasesNothingWithoutError() throws Exception {
        factions.ensureSystemFaction("SafeZone");

        int released = factions.forceUnclaimAllSystemClaims("safezone").get(5, TimeUnit.SECONDS);

        assertEquals(0, released);
    }

    @Test
    void unclaimAllOnATagWithNoSystemFactionYetReleasesNothingWithoutError() throws Exception {
        // WarZone has never been claimed into existence via ensureSystemFaction
        // -- systemFactionId(...) must return null and short-circuit rather
        // than throwing.
        int released = factions.forceUnclaimAllSystemClaims("warzone").get(5, TimeUnit.SECONDS);

        assertEquals(0, released);
    }
}
