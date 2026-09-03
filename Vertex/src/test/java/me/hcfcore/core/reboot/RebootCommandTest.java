package me.hcfcore.core.reboot;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import me.hcfcore.core.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Map;
import java.util.UUID;

import static me.hcfcore.core.lang.MessageAssertions.isChatMessage;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebootCommandTest {

    private ServerMock server;
    private PluginMock plugin;
    private Messages messages;
    private RebootManager rebootManager;
    private RebootCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        rebootManager = new RebootManager(plugin, messages);
        command = new RebootCommand(rebootManager, messages);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void successfulScheduleBroadcastsExactlyOnce() {
        // Regression test: RebootManager.schedule() already broadcasts
        // reboot.started to every online player (including the sender);
        // RebootCommand used to also send it directly to the sender,
        // doubling it up for whoever ran the command.
        PlayerMock player = authorizedPlayer("Alice");

        command.onCommand(player, null, "reboot", new String[0]);

        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "reboot.started"));
        assertNull(player.nextComponentMessage(), "the sender should only see the broadcast once");
    }

    @Test
    void successfulCancelBroadcastsExactlyOnce() {
        PlayerMock player = authorizedPlayer("Bob");
        rebootManager.schedule();
        player.nextComponentMessage();

        command.onCommand(player, null, "reboot", new String[]{"cancel"});

        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "reboot.cancelled"));
        assertNull(player.nextComponentMessage(), "the sender should only see the broadcast once");
    }

    private PlayerMock authorizedPlayer(String name) {
        PlayerMock player = server.addPlayer(name);
        player.addAttachment(plugin, "hcfcore.reboot.start", true);
        return player;
    }

    private static final class NoOpStorage implements me.hcfcore.core.storage.Storage {
        @Override
        public void init() {
        }

        @Override
        public java.util.Map<String, Long> loadCooldowns(java.util.UUID uuid) {
            return java.util.Map.of();
        }

        @Override
        public void saveCooldown(java.util.UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public java.util.Map<String, Long> loadAbilityCooldowns(java.util.UUID uuid) {
            return java.util.Map.of();
        }

        @Override
        public void saveAbilityCooldown(java.util.UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(java.util.UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(java.util.UUID uuid, String locale) {
        }
        @Override
        public void saveDeath(UUID uuid, me.hcfcore.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.hcfcore.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
