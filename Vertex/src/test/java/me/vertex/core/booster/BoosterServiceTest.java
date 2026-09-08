package me.vertex.core.booster;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service is an aggregator, so these cover what it does with whatever
 * sources report -- not the value any particular source produces.
 */
class BoosterServiceTest {

    private ServerMock server;
    private PluginMock plugin;
    private BoosterService service;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        service = new BoosterService(plugin);
        player = server.addPlayer("Miner");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A fixed source, so the assertions are about aggregation only. */
    private record FixedSource(String id, BoosterCategory category, double percent, boolean active)
            implements BoosterSource {
        @Override
        public List<BoosterContribution> contribute(Player player, BoosterCategory requested) {
            if (requested != category) {
                return List.of();
            }
            return List.of(active
                    ? BoosterContribution.active(id, category, percent)
                    : BoosterContribution.inactive(id, category, percent, "test-reason"));
        }
    }

    @Test
    void sumsActiveContributionsAcrossSources() {
        service.register(new FixedSource("a", BoosterCategory.ORE_DROP, 10D, true));
        service.register(new FixedSource("b", BoosterCategory.ORE_DROP, 15D, true));

        assertEquals(25D, service.effectivePercent(player, BoosterCategory.ORE_DROP), 0.0001);
        assertEquals(1.25D, service.multiplier(player, BoosterCategory.ORE_DROP), 0.0001);
    }

    @Test
    void excludesInactiveContributionsFromTheTotal() {
        service.register(new FixedSource("a", BoosterCategory.ORE_DROP, 10D, true));
        service.register(new FixedSource("b", BoosterCategory.ORE_DROP, 90D, false));

        assertEquals(10D, service.effectivePercent(player, BoosterCategory.ORE_DROP), 0.0001);
    }

    /** Inactive entries must still be listed, or the tracker cannot explain a gap. */
    @Test
    void stillReportsInactiveContributionsForDisplay() {
        service.register(new FixedSource("b", BoosterCategory.ORE_DROP, 90D, false));

        List<BoosterContribution> contributions = service.contributions(player, BoosterCategory.ORE_DROP);
        assertEquals(1, contributions.size());
        assertFalse(contributions.getFirst().active());
        assertEquals("test-reason", contributions.getFirst().inactiveReasonKey());
    }

    @Test
    void keepsCategoriesIndependent() {
        service.register(new FixedSource("a", BoosterCategory.ORE_DROP, 10D, true));
        service.register(new FixedSource("b", BoosterCategory.SELL, 40D, true));

        assertEquals(10D, service.effectivePercent(player, BoosterCategory.ORE_DROP), 0.0001);
        assertEquals(40D, service.effectivePercent(player, BoosterCategory.SELL), 0.0001);
        assertEquals(0D, service.effectivePercent(player, BoosterCategory.EXP), 0.0001);
    }

    @Test
    void appliesTheConfiguredSellCap() {
        service.register(new FixedSource("a", BoosterCategory.SELL, 400D, true));
        service.register(new FixedSource("b", BoosterCategory.SELL, 300D, true));

        BoosterStacking.Result result = service.result(player, BoosterCategory.SELL);
        assertEquals(700D, result.raw(), 0.0001);
        assertEquals(500D, result.effective(), 0.0001);
        assertTrue(result.capped());
    }

    @Test
    void leavesOreDropUncappedByDefault() {
        service.register(new FixedSource("a", BoosterCategory.ORE_DROP, 400D, true));
        service.register(new FixedSource("b", BoosterCategory.ORE_DROP, 300D, true));

        assertEquals(700D, service.effectivePercent(player, BoosterCategory.ORE_DROP), 0.0001);
    }

    @Test
    void reportsNoBonusWhenNothingIsRegistered() {
        assertEquals(0D, service.effectivePercent(player, BoosterCategory.MOB_DROP), 0.0001);
        assertEquals(List.of(), service.contributions(player, BoosterCategory.MOB_DROP));
    }
}
