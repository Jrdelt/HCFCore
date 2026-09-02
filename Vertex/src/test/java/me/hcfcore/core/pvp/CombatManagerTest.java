package me.hcfcore.core.pvp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatManagerTest {

    private ServerMock server;
    private PluginMock plugin;
    private CombatManager combatManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        combatManager = new CombatManager(plugin, 15, true, 4);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void tagMarksBothPlayersAsTaggedAgainstEachOther() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        combatManager.tag(alice, bob);

        assertTrue(combatManager.isTagged(alice.getUniqueId()));
        assertTrue(combatManager.isTagged(bob.getUniqueId()));
        assertEquals(bob.getUniqueId(), combatManager.getOpponentId(alice.getUniqueId()));
        assertEquals(alice.getUniqueId(), combatManager.getOpponentId(bob.getUniqueId()));
    }

    @Test
    void clearOnOneTaggedPlayerAlsoClearsTheirMutualOpponent() {
        // Regression test: clear() used to only remove the given player's
        // own entries, leaving their opponent stuck showing as tagged.
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        combatManager.tag(alice, bob);

        combatManager.clear(alice.getUniqueId());

        assertFalse(combatManager.isTagged(alice.getUniqueId()));
        assertFalse(combatManager.isTagged(bob.getUniqueId()));
        assertNull(combatManager.getOpponentId(bob.getUniqueId()));
    }

    @Test
    void clearOnPlayerTaggedAgainstServerDoesNotTouchOtherPlayers() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        combatManager.tagAgainstServer(alice);
        combatManager.tag(bob, server.addPlayer("Carol"));

        combatManager.clear(alice.getUniqueId());

        assertFalse(combatManager.isTagged(alice.getUniqueId()));
        assertTrue(combatManager.isTagged(bob.getUniqueId()), "unrelated tag should be untouched");
    }

    @Test
    void clearDoesNotClobberAnOpponentWhoHasSinceBeenRetaggedElsewhere() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock carol = server.addPlayer("Carol");
        combatManager.tag(alice, bob);
        // Bob gets re-tagged against Carol before Alice's tag is cleared.
        combatManager.tag(bob, carol);

        combatManager.clear(alice.getUniqueId());

        assertFalse(combatManager.isTagged(alice.getUniqueId()));
        assertTrue(combatManager.isTagged(bob.getUniqueId()), "Bob's newer tag with Carol must survive");
        assertEquals(carol.getUniqueId(), combatManager.getOpponentId(bob.getUniqueId()));
    }

    @Test
    void isTaggedAndRemainingMillisAreFalseAndZeroForAnUntaggedPlayer() {
        UUID randomId = UUID.randomUUID();

        assertFalse(combatManager.isTagged(randomId));
        assertEquals(0L, combatManager.remainingMillis(randomId));
    }

    @Test
    void reconfigureAppliesNewSettingsToFutureTagsWithoutNeedingARestart() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        combatManager.reconfigure(5, false, 4);
        combatManager.tag(alice, bob);

        assertFalse(combatManager.logoutPenaltyEnabled());
        long remaining = combatManager.remainingMillis(alice.getUniqueId());
        assertTrue(remaining <= 5_000L && remaining > 0L,
                "expected the new 5s duration, got " + remaining + "ms remaining");
    }
}
