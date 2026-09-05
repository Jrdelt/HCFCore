package me.hcfcore.core.pvp;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Map;
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
        UserManager userManager = new UserManager(plugin, new NoOpStorage());
        Messages messages = new Messages(plugin, userManager);
        messages.load();
        combatManager = new CombatManager(plugin, messages, 15, 5, true, 4,
                "<red><bold>Combat</bold><gray>: <white>server <gray>{seconds} <green>you <white>({your_cps}cps)",
                "<red><bold>Combat</bold><gray>: {health}   <white>{opponent} <gray>{seconds} <green>you "
                        + "<white>({your_cps}cps) <red>them <white>({their_cps}cps)",
                "<red><bold>Combat</bold><gray>: {seconds} <green>you <white>({your_cps}cps)");
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
    void clearOwnTagRemovesOnlyThatPlayersTagWithoutTouchingTheirOpponent() {
        // Regression coverage: clearOwnTag exists specifically so a
        // victim's death doesn't wipe out the killer's own (possibly
        // freshly-shortened post-kill) tag the way clear()'s mutual-clear
        // cascade would.
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        combatManager.tag(alice, bob);

        combatManager.clearOwnTag(alice.getUniqueId());

        assertFalse(combatManager.isTagged(alice.getUniqueId()));
        assertTrue(combatManager.isTagged(bob.getUniqueId()), "Bob's own tag must survive Alice's death");
    }

    @Test
    void retaggingPlayerClearsTheirPreviousOpponent() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock carol = server.addPlayer("Carol");

        combatManager.tag(alice, bob);
        combatManager.tag(alice, carol);

        assertFalse(combatManager.isTagged(bob.getUniqueId()));
        assertNull(combatManager.getOpponentId(bob.getUniqueId()));
        assertEquals(carol.getUniqueId(), combatManager.getOpponentId(alice.getUniqueId()));
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
    void getCpsIsZeroForAPlayerWhoHasNeverClicked() {
        UUID randomId = UUID.randomUUID();

        assertEquals(0, combatManager.getCps(randomId));
    }

    @Test
    void getCpsCountsClicksWithinTheLastSecond() {
        PlayerMock alice = server.addPlayer("Alice");

        combatManager.recordClick(alice.getUniqueId());
        combatManager.recordClick(alice.getUniqueId());
        combatManager.recordClick(alice.getUniqueId());

        assertEquals(3, combatManager.getCps(alice.getUniqueId()));
    }

    @Test
    void getCpsDropsClicksOlderThanOneSecond() throws InterruptedException {
        PlayerMock alice = server.addPlayer("Alice");

        combatManager.recordClick(alice.getUniqueId());
        Thread.sleep(1_100);
        combatManager.recordClick(alice.getUniqueId());

        assertEquals(1, combatManager.getCps(alice.getUniqueId()),
                "the first click is over a second old and should have aged out");
    }

    @Test
    void forgetPlayerDropsClickHistoryButNotAViaClear() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        combatManager.tag(alice, bob);
        combatManager.recordClick(alice.getUniqueId());

        // clear() is combat-tag state only -- click history must survive it.
        combatManager.clear(alice.getUniqueId());
        assertEquals(1, combatManager.getCps(alice.getUniqueId()));

        combatManager.forgetPlayer(alice.getUniqueId());
        assertEquals(0, combatManager.getCps(alice.getUniqueId()));
    }

    @Test
    void applyPostKillCooldownShortensAnAlreadyTaggedPlayersRemainingTime() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        combatManager.tag(alice, bob);

        combatManager.applyPostKillCooldown(alice.getUniqueId());

        long remaining = combatManager.remainingMillis(alice.getUniqueId());
        assertTrue(remaining <= 5_000L && remaining > 0L,
                "expected the configured 5s post-kill duration, got " + remaining + "ms remaining");
        assertTrue(combatManager.isTagged(alice.getUniqueId()));
    }

    @Test
    void applyPostKillCooldownDoesNothingForAPlayerWhoIsNotTagged() {
        UUID randomId = UUID.randomUUID();

        combatManager.applyPostKillCooldown(randomId);

        assertFalse(combatManager.isTagged(randomId));
    }

    @Test
    void reconfigureAppliesNewSettingsToFutureTagsWithoutNeedingARestart() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        combatManager.reconfigure(5, 2, false, 4, "vs-server {seconds} {your_cps}",
                "vs-player {health} {opponent} {seconds} {your_cps} {their_cps}", "vs-unknown {seconds} {your_cps}");
        combatManager.tag(alice, bob);

        assertFalse(combatManager.logoutPenaltyEnabled());
        long remaining = combatManager.remainingMillis(alice.getUniqueId());
        assertTrue(remaining <= 5_000L && remaining > 0L,
                "expected the new 5s duration, got " + remaining + "ms remaining");
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
