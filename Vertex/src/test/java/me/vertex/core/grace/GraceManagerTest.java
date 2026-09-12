package me.vertex.core.grace;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraceManagerTest {
    @TempDir Path folder;
    private Database database;
    private GraceStorage storage;
    private PluginMock plugin;

    @BeforeEach void setUp() throws Exception {
        MockBukkit.mock(); plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), folder.toFile());
        storage = new GraceStorage(database); storage.init();
    }

    @AfterEach void tearDown() { database.close(); MockBukkit.unmock(); }

    @Test void enableAndDisablePersistAcrossReload() {
        GraceManager manager = new GraceManager(plugin, storage); manager.load();
        assertEquals(GraceManager.Result.OK, manager.enable(60, UUID.randomUUID()));
        GraceManager reloaded = new GraceManager(plugin, storage); reloaded.load();
        assertTrue(reloaded.isActive());
        assertEquals(GraceManager.Result.OK, reloaded.disable(UUID.randomUUID()));
        GraceManager disabled = new GraceManager(plugin, storage); disabled.load();
        assertFalse(disabled.isActive());
    }

    @Test void rejectsZeroAndExcessiveDurationsWithoutChangingState() {
        GraceManager manager = new GraceManager(plugin, storage); manager.load();
        assertEquals(GraceManager.Result.INVALID_DURATION, manager.enable(0, null));
        assertEquals(GraceManager.Result.INVALID_DURATION, manager.enable(31L * 86_400L, null));
        assertFalse(manager.isActive());
    }
}
