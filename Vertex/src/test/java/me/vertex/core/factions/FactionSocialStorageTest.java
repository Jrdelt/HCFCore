package me.vertex.core.factions;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-shard relation/focus limits must be decided from durable state, not a local cache. */
class FactionSocialStorageTest {
    @TempDir Path dataFolder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void relationRequestsAllyLimitsAndFocusLimitsAreAuthoritative() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage factions = new FactionStorage(database);
        factions.init();
        FactionSocialStorage social = new FactionSocialStorage(database);
        social.init();
        int alpha = faction(factions, "Alpha");
        int bravo = faction(factions, "Bravo");
        int charlie = faction(factions, "Charlie");
        UUID alphaLeader = leader(factions, alpha);
        UUID bravoLeader = leader(factions, bravo);
        long now = 10_000L;

        assertEquals(FactionSocialStorage.RelationWriteResult.REQUEST_SENT,
                social.mutateRelation(alpha, bravo, FactionRelation.ALLY, 1,
                        now, now + 60_000L, alphaLeader, FactionRole.LEADER, true, true).result());
        assertEquals(FactionSocialStorage.RelationWriteResult.ACCEPTED,
                social.mutateRelation(bravo, alpha, FactionRelation.ALLY, 1,
                        now + 1, now + 60_001L, bravoLeader, FactionRole.LEADER, true, true).result());
        assertEquals(FactionSocialStorage.RelationWriteResult.ALLY_LIMIT,
                social.mutateRelation(alpha, charlie, FactionRelation.ALLY, 1,
                        now + 2, now + 60_002L, alphaLeader, FactionRole.LEADER, true, true).result());

        assertEquals(FactionSocialStorage.RelationWriteResult.OK,
                social.mutateRelation(alpha, bravo, FactionRelation.ENEMY, 1,
                        now + 3, now + 60_003L, alphaLeader, FactionRole.LEADER, true, true).result());
        assertEquals(FactionSocialStorage.RelationWriteResult.OK,
                social.mutateRelation(alpha, charlie, FactionRelation.ENEMY, 1,
                        now + 4, now + 60_004L, alphaLeader, FactionRole.LEADER, true, true).result());
        assertEquals(FactionSocialStorage.FocusWriteResult.OK,
                social.mutateFocus(alpha, bravo, false, 1, now + 5,
                        now + 120_000L, alphaLeader).result());
        assertEquals(FactionSocialStorage.FocusWriteResult.LIMIT_REACHED,
                social.mutateFocus(alpha, charlie, false, 1, now + 6,
                        now + 120_001L, alphaLeader).result());
        assertEquals(FactionSocialStorage.FocusWriteResult.REMOVED,
                social.mutateFocus(alpha, bravo, true, 1, now + 7,
                        now + 120_002L, alphaLeader).result());
        assertEquals(FactionSocialStorage.FocusWriteResult.OK,
                social.mutateFocus(alpha, charlie, false, 1, now + 8,
                        now + 120_003L, alphaLeader).result());

        assertTrue(social.forceRelation(alpha, charlie, FactionRelation.NEUTRAL));
        assertTrue(social.loadFocuses().isEmpty(),
                "an admin relation override must clear invalid focus state atomically");
        FactionStorage.LoadedState reloaded = factions.load();
        assertTrue(reloaded.relations().get(new FactionStorage.RelationKey(alpha, charlie)) == null);
    }

    private static int faction(FactionStorage storage, String tag) throws Exception {
        UUID leader = UUID.randomUUID();
        return storage.createFaction(new FactionData(-1, tag, "", false, false,
                        10D, 10D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, tag + "Leader", 1L));
    }

    private static UUID leader(FactionStorage storage, int factionId) throws Exception {
        return storage.load().members().values().stream()
                .filter(member -> member.factionId() == factionId && member.role() == FactionRole.LEADER)
                .map(FactionMember::playerUuid).findFirst().orElseThrow();
    }
}
