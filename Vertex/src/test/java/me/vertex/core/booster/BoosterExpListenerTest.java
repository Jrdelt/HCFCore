package me.vertex.core.booster;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoosterExpListenerTest {

    private ServerMock server;
    private PluginMock plugin;
    private BoosterService service;
    private BoosterExpListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        service = new BoosterService(plugin);
        listener = new BoosterExpListener(service);
        player = server.addPlayer("ExpTester");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private record TestExpSource(double percent) implements BoosterSource {
        @Override
        public String id() {
            return "test_exp";
        }

        @Override
        public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
            if (category == BoosterCategory.EXP) {
                return List.of(BoosterContribution.active("test_exp", BoosterCategory.EXP, percent));
            }
            return List.of();
        }
    }

    @Test
    void testNoBoosterLeavesExpUnchanged() {
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 100);
        listener.onPlayerExpChange(event);
        assertEquals(100, event.getAmount());
    }

    @Test
    void testExpBoosterMultipliesExp() {
        // Register 50% EXP boost
        service.register(new TestExpSource(50.0));

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 100);
        listener.onPlayerExpChange(event);
        assertEquals(150, event.getAmount());
    }

    @Test
    void testZeroExpRemainsZero() {
        service.register(new TestExpSource(100.0));

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 0);
        listener.onPlayerExpChange(event);
        assertEquals(0, event.getAmount());
    }
}
