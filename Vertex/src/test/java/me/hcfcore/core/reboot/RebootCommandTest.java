package me.hcfcore.core.reboot;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RebootCommandTest {

    private ServerMock server;
    private PluginMock plugin;
    private RebootManager rebootManager;
    private RebootCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Messages messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        rebootManager = new RebootManager(plugin, messages);
        command = new RebootCommand(rebootManager, messages);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void successfulScheduleConfirmsDirectlyToTheSender() {
        PlayerMock player = authorizedPlayer("Alice");

        command.onCommand(player, null, "reboot", new String[0]);

        assertTrue(player.nextMessage().contains("server reboot has been scheduled"));
        assertTrue(player.nextMessage().contains("server reboot has been scheduled"));
    }

    @Test
    void successfulCancelConfirmsDirectlyToTheSender() {
        PlayerMock player = authorizedPlayer("Bob");
        rebootManager.schedule();
        player.nextMessage();

        command.onCommand(player, null, "reboot", new String[]{"cancel"});

        assertTrue(player.nextMessage().contains("scheduled reboot was cancelled"));
        assertTrue(player.nextMessage().contains("scheduled reboot was cancelled"));
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
        public void close() {
        }
    }
}
