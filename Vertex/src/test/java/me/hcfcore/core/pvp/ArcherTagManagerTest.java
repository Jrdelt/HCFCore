package me.hcfcore.core.pvp;

import me.hcfcore.core.factions.FactionsHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcherTagManagerTest {

    private static final int FACTION_A = 7;
    private static final int FACTION_B = 9;
    private static final int DURATION = 10;
    private static final int MAX_STACKS = 4;

    private ArcherTagManager manager;
    private UUID victim;

    @BeforeEach
    void setUp() {
        manager = new ArcherTagManager();
        victim = UUID.randomUUID();
    }

    @Test
    void arrowsFromDifferentArchersStackOnTheSameVictim() {
        assertEquals(1, manager.tag(victim, FACTION_A, DURATION, MAX_STACKS));
        assertEquals(2, manager.tag(victim, FACTION_B, DURATION, MAX_STACKS));
        assertEquals(3, manager.tag(victim, FACTION_A, DURATION, MAX_STACKS));
        assertEquals(3, manager.stacks(victim));
    }

    @Test
    void stacksAreCapped() {
        for (int i = 0; i < 10; i++) {
            manager.tag(victim, FACTION_A, DURATION, MAX_STACKS);
        }
        assertEquals(MAX_STACKS, manager.stacks(victim));
    }

    @Test
    void onlyTheTaggingFactionsGetTheMeleeBonus() {
        manager.tag(victim, FACTION_A, DURATION, MAX_STACKS);

        assertEquals(1, manager.stacksFor(victim, FACTION_A));
        assertEquals(0, manager.stacksFor(victim, FACTION_B), "a rival faction gets no melee bonus");
    }

    @Test
    void aFactionlessAttackerNeverGetsTheMeleeBonus() {
        // NO_FACTION is "no faction", not one every loner shares -- an
        // archer without a faction must not arm every factionless player.
        manager.tag(victim, FactionsHook.NO_FACTION, DURATION, MAX_STACKS);

        assertEquals(1, manager.stacks(victim), "the arrow bonus still applies");
        assertEquals(0, manager.stacksFor(victim, FactionsHook.NO_FACTION));
    }

    @Test
    void anExpiredTagIsGone() {
        manager.tag(victim, FACTION_A, 0, MAX_STACKS);

        assertEquals(0, manager.stacks(victim));
        assertEquals(0, manager.stacksFor(victim, FACTION_A));
        assertEquals(0L, manager.remainingMillis(victim));
    }

    @Test
    void reTaggingRefreshesTheRemainingTime() {
        manager.tag(victim, FACTION_A, 1, MAX_STACKS);
        long afterShort = manager.remainingMillis(victim);
        manager.tag(victim, FACTION_A, DURATION, MAX_STACKS);

        assertTrue(manager.remainingMillis(victim) > afterShort);
    }

    @Test
    void deathClearsTheTagButQuittingDoesNot() {
        manager.tag(victim, FACTION_A, DURATION, MAX_STACKS);

        manager.clearIfExpired(victim);
        assertEquals(1, manager.stacks(victim), "relogging must not shed a live archer tag");

        manager.clear(victim);
        assertEquals(0, manager.stacks(victim));
    }
}
