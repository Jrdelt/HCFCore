package me.vertex.core.ability;

import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Map;
import java.util.UUID;

import static me.vertex.core.lang.MessageAssertions.isChatMessage;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetItemCommandTest {

    private ServerMock server;
    private PluginMock plugin;
    private Messages messages;
    private GetItemCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        AbilityManager abilityManager = new AbilityManager(plugin, new NoOpStorage());
        abilityManager.load();
        command = new GetItemCommand(plugin, abilityManager, messages);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // Regression test: an out-of-range/non-numeric amount used to NPE inside
    // a ternary's implicit Integer->int unboxing instead of showing usage.
    @Test
    void outOfRangeAmountDoesNotCrashAndShowsUsage() {
        PlayerMock sender = server.addPlayer("Admin");
        sender.addAttachment(plugin, "vertex.ability.give", true);
        server.addPlayer("Target");

        assertDoesNotThrow(() ->
                command.onCommand(sender, null, "getitem", new String[]{"Target", "leap", "999999999"}));

        assertTrue(isChatMessage(messages, sender, sender.nextComponentMessage(), "ability.getitem-usage"));
    }

    @Test
    void nonNumericAmountDoesNotCrashAndShowsUsage() {
        PlayerMock sender = server.addPlayer("Admin2");
        sender.addAttachment(plugin, "vertex.ability.give", true);
        server.addPlayer("Target2");

        assertDoesNotThrow(() ->
                command.onCommand(sender, null, "getitem", new String[]{"Target2", "leap", "abc"}));

        assertTrue(isChatMessage(messages, sender, sender.nextComponentMessage(), "ability.getitem-usage"));
    }

    private static final class NoOpStorage implements Storage {
        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }

        @Override
        public void saveDeath(UUID uuid, me.vertex.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.vertex.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
