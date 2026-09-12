package me.vertex.core.preferences;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncementPreferenceManagerTest {
    @TempDir Path folder;
    private Database database;
    private PluginMock plugin;
    private AnnouncementPreferenceStorage storage;
    private AnnouncementPreferenceManager manager;
    private final UUID player = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), folder.toFile());
        storage = new AnnouncementPreferenceStorage(database); storage.init();
        manager = new AnnouncementPreferenceManager(plugin, storage, null);
    }
    @AfterEach void cleanup() { manager.awaitWrites(); database.close(); MockBukkit.unmock(); }

    @Test void earlyToggleDoesNotDiscardOtherStoredDisabledSettings() throws Exception {
        storage.save(player, AnnouncementCategory.KOTH, false);
        storage.save(player, AnnouncementCategory.PAYMENTS, false);
        try (var held = database.getConnection()) {
            manager.loadPlayer(player);
            manager.toggle(player, AnnouncementCategory.TRADE_REQUESTS);
        }
        manager.awaitWrites();
        assertFalse(manager.isEnabled(player, AnnouncementCategory.KOTH));
        assertFalse(manager.isEnabled(player, AnnouncementCategory.PAYMENTS));
        assertFalse(manager.isEnabled(player, AnnouncementCategory.TRADE_REQUESTS));
    }

    @Test void rapidTogglesPersistTheLastChoice() throws Exception {
        try (var held = database.getConnection()) {
            for (int index = 0; index < 30; index++) manager.toggle(player, AnnouncementCategory.PRIVATE_MESSAGES);
        }
        manager.awaitWrites();
        assertTrue(manager.isEnabled(player, AnnouncementCategory.PRIVATE_MESSAGES));
        assertFalse(storage.loadDisabled(player).contains(AnnouncementCategory.PRIVATE_MESSAGES));
    }

    @Test void reconnectRefreshesChangesWrittenByAnotherServer() throws Exception {
        manager.loadPlayer(player); manager.awaitWrites();
        assertTrue(manager.isEnabled(player, AnnouncementCategory.PAYMENTS));
        storage.save(player, AnnouncementCategory.PAYMENTS, false);
        manager.loadPlayer(player); manager.awaitWrites();
        assertFalse(manager.isEnabled(player, AnnouncementCategory.PAYMENTS));
    }
}
