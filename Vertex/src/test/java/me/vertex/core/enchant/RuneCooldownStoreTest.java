package me.vertex.core.enchant;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import me.vertex.core.user.User;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms a rune cooldown survives a fresh {@link User} load from persisted
 * storage -- the whole point of {@link RuneCooldownStore} over a plain
 * in-memory map cleared on logout.
 */
class RuneCooldownStoreTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private Database database;
    private SqlStorage storage;
    private RuneCooldownStore cooldowns;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new SqlStorage(database);
        storage.init();
        cooldowns = new RuneCooldownStore(plugin, storage);
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    @Test
    void cooldownSurvivesAFreshUserLoadFromPersistedStorage() throws Exception {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        User firstSession = new User(player.getUniqueId(), Map.of(), null);
        assertFalse(cooldowns.isOnCooldown(firstSession, "dasher"));

        cooldowns.start(player, firstSession, "dasher", 60_000L);
        assertTrue(cooldowns.isOnCooldown(firstSession, "dasher"));
        cooldowns.awaitWrites();

        // Rebuild User exactly as UserManager.load() does: merge the persisted
        // ability_cooldowns rows under the "ability:" namespace -- simulating
        // the player logging back in on a fresh session.
        Map<String, Long> reloaded = new HashMap<>();
        for (Map.Entry<String, Long> entry : storage.loadAbilityCooldowns(player.getUniqueId()).entrySet()) {
            reloaded.put("ability:" + entry.getKey(), entry.getValue());
        }
        User secondSession = new User(player.getUniqueId(), reloaded, null);

        assertTrue(cooldowns.isOnCooldown(secondSession, "dasher"),
                "the cooldown must still be active after simulating a relogin, not reset by the new session");
        assertTrue(cooldowns.remainingMillis(secondSession, "dasher") > 0L);
    }

    @Test
    void differentRunesHaveIndependentCooldowns() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        User user = new User(player.getUniqueId(), Map.of(), null);

        cooldowns.start(player, user, "dasher", 60_000L);

        assertTrue(cooldowns.isOnCooldown(user, "dasher"));
        assertFalse(cooldowns.isOnCooldown(user, "sky_stepper"));
    }
}
