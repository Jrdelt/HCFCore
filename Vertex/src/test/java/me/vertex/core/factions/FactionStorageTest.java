package me.vertex.core.factions;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.claims.ClaimStorage;
import me.vertex.core.shield.ShieldStorage;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native faction SQL round-trips, including the compound writes that protect rank integrity. */
class FactionStorageTest {
    @TempDir Path dataFolder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void persistsFullFactionStateAndAtomicMembershipChanges() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        long now = 100L;
        int factionId = storage.createFaction(new FactionData(-1, "Alpha", "desc", false, false,
                        10D, 20D, now, new FactionData.Home("world", 1D, 64D, 2D, 10F, 20F)),
                new FactionMember(leaderId, -1, FactionRole.LEADER, "Leader", now));

        FactionData afterJoin = new FactionData(factionId, "Alpha", "desc", false, false,
                20D, 20D, now, new FactionData.Home("world", 1D, 64D, 2D, 10F, 20F));
        storage.joinFaction(new FactionMember(memberId, factionId, FactionRole.RECRUIT, "Member", now + 1),
                null, afterJoin);
        storage.saveClaim(new ChunkKey("world", 1, 2), factionId);
        storage.saveRelation(factionId, 77, FactionRelation.ALLY);
        storage.savePermission(factionId, "member", "doors", true);
        storage.saveWarp(new FactionWarp(factionId, "home2", new FactionData.Home("world", 3D, 65D, 4D, 0F, 0F)));
        storage.savePlayerSettings(memberId, new FactionStorage.PlayerSettings("FACTION", true, false));

        FactionStorage.LoadedState state = storage.load();
        assertEquals(2, state.members().size());
        assertEquals(20D, state.factions().get(factionId).power());
        assertEquals(factionId, state.claims().get(new ChunkKey("world", 1, 2)));
        assertEquals(FactionRelation.ALLY, state.relations().get(new FactionStorage.RelationKey(factionId, 77)));
        assertTrue(state.permissions().get(new FactionStorage.PermissionKey(factionId, "member", "doors")));
        assertEquals("FACTION", state.players().get(memberId).chatMode());

        FactionMember formerLeader = new FactionMember(leaderId, factionId, FactionRole.COLEADER, "Leader", now);
        FactionMember newLeader = new FactionMember(memberId, factionId, FactionRole.LEADER, "Member", now + 1);
        storage.transferLeadership(formerLeader, newLeader);
        FactionData afterLeave = afterJoin.withPower(10D, 10D);
        storage.removeFactionMember(memberId, afterLeave);

        FactionStorage.LoadedState after = storage.load();
        assertEquals(FactionRole.COLEADER, after.members().get(leaderId).role());
        assertFalse(after.members().containsKey(memberId));
        assertEquals(10D, after.factions().get(factionId).power());
        assertEquals(10D, after.factions().get(factionId).powerMax());
    }

    @Test
    void checkedMembershipMutationsRevalidateLimitsAndRecalculatePower() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        new FactionSocialStorage(database).init();
        UUID leader = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        long now = 10_000L;
        int factionId = storage.createFaction(new FactionData(-1, "Network", "kept", true,
                        false, 60D, 100D, now, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", now));
        storage.savePower(new FactionPowerProfile(leader, 60D, 100D, now));
        storage.savePower(new FactionPowerProfile(first, 40D, 50D, now));
        storage.savePower(new FactionPowerProfile(second, 25D, 30D, now));

        FactionStorage.JoinOutcome joined = storage.joinFactionChecked(
                new FactionMember(first, factionId, FactionRole.RECRUIT, "First", now + 1),
                true, 2, now);
        assertEquals(FactionStorage.JoinWriteResult.OK, joined.result());
        assertEquals(100D, joined.totals().current());
        assertEquals(150D, joined.totals().maximum());
        assertEquals(FactionStorage.JoinWriteResult.MEMBER_LIMIT,
                storage.joinFactionChecked(new FactionMember(second, factionId, FactionRole.RECRUIT,
                        "Second", now + 2), true, 2, now).result());

        assertEquals(FactionStorage.JoinWriteResult.OK,
                storage.joinFactionChecked(new FactionMember(second, factionId, FactionRole.RECRUIT,
                        "Second", now + 2), true, 3, now).result());
        assertEquals(FactionStorage.RoleWriteResult.OK,
                storage.setRoleChecked(first, factionId, FactionRole.RECRUIT, FactionRole.COLEADER));
        assertEquals(FactionStorage.RoleWriteResult.ROLE_LIMIT,
                storage.setRoleChecked(second, factionId, FactionRole.RECRUIT, FactionRole.COLEADER));

        FactionStorage.MemberMutationOutcome removed = storage.removeFactionMemberChecked(second, factionId);
        assertEquals(FactionStorage.MemberWriteResult.OK, removed.result());
        assertEquals(100D, removed.totals().current());
        assertEquals(150D, removed.totals().maximum());
        assertEquals("kept", storage.load().factions().get(factionId).description());
    }

    @Test
    void systemFactionHasNoFakeMember() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        int id = storage.createSystemFaction(new FactionData(-1, "SafeZone", "system", false, true,
                0D, 0D, 1L, null));
        FactionStorage.LoadedState state = storage.load();
        assertTrue(state.factions().get(id).system());
        assertTrue(state.members().isEmpty());
    }

    @Test
    void checkedSecondaryWritesRejectStaleRolesAndDeletedFactions() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        new FactionSocialStorage(database).init();
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        int factionId = storage.createFaction(new FactionData(-1, "Writes", "", false, false,
                        100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        storage.joinFactionChecked(new FactionMember(member, factionId, FactionRole.MEMBER,
                "Member", 2L), true, 16, 2L);
        ChunkKey claimed = new ChunkKey("world", 2, 3);
        storage.saveClaim(claimed, factionId);

        assertEquals(FactionStorage.PermissionWriteResult.OK,
                storage.savePermissionChecked(leader, factionId, "member", "doors", true));
        assertEquals(FactionStorage.PermissionWriteResult.NOT_AUTHORIZED,
                storage.savePermissionChecked(member, factionId, "admin", "doors", true));
        assertEquals(FactionStorage.InviteWriteResult.NOT_AUTHORIZED,
                storage.saveInviteChecked(new FactionStorage.Invite(factionId, target, member, 9_000L),
                        FactionRole.MEMBER, false));
        assertEquals(FactionStorage.WarpWriteResult.OK,
                storage.saveWarpChecked(leader, new FactionWarp(factionId, "base",
                        new FactionData.Home("", "world", 33D, 64D, 49D, 0F, 0F)),
                        1, FactionRole.LEADER, true));
        assertEquals(FactionStorage.WarpWriteResult.LIMIT_REACHED,
                storage.saveWarpChecked(leader, new FactionWarp(factionId, "second",
                        new FactionData.Home("", "world", 33D, 64D, 49D, 0F, 0F)),
                        1, FactionRole.LEADER, true));

        storage.deleteFaction(factionId);
        assertFalse(storage.saveClaimChecked(new ChunkKey("world", 9, 9), factionId));
        assertEquals(FactionStorage.PermissionWriteResult.FACTION_CHANGED,
                storage.savePermissionChecked(leader, factionId, "member", "doors", false));
        assertFalse(storage.updateMemberName(member, factionId, "Resurrected"));
    }

    @Test
    void normalMutationsRejectAnActorDemotedOnAnotherShard() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        new FactionSocialStorage(database).init();
        UUID leader = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        int factionId = storage.createFaction(new FactionData(-1, "Stale", "", true, false,
                        100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        storage.joinFactionChecked(new FactionMember(actor, factionId, FactionRole.RECRUIT,
                "Actor", 2L), true, 16, 2L);
        storage.joinFactionChecked(new FactionMember(target, factionId, FactionRole.RECRUIT,
                "Target", 3L), true, 16, 3L);
        assertEquals(FactionStorage.RoleWriteResult.OK,
                storage.setRoleChecked(actor, factionId, FactionRole.RECRUIT, FactionRole.ADMIN));
        for (String action : java.util.List.of("kick", "promote", "claim", "unclaim",
                "description", "sethome", "setwarp")) {
            storage.savePermission(factionId, "admin", action, true);
        }
        ChunkKey owned = new ChunkKey("world", 2, 3);
        storage.saveClaim(owned, factionId);
        assertEquals(FactionStorage.WarpWriteResult.OK,
                storage.saveWarpChecked(actor, new FactionWarp(factionId, "base",
                        new FactionData.Home("", "world", 33D, 64D, 49D, 0F, 0F)),
                        2, FactionRole.ADMIN, true));
        assertEquals(FactionStorage.HomeWriteResult.OK,
                storage.updateHomeChecked(actor, factionId, FactionRole.ADMIN, true,
                        new FactionData.Home("", "world", 33D, 64D, 49D, 0F, 0F)));

        // Simulate a different backend committing the demotion before this
        // backend receives its invalidation event.
        assertEquals(FactionStorage.RoleWriteResult.OK,
                storage.setRoleChecked(actor, factionId, FactionRole.ADMIN, FactionRole.MEMBER));

        assertEquals(FactionStorage.MemberWriteResult.NOT_AUTHORIZED,
                storage.removeFactionMemberAuthorized(actor, FactionRole.ADMIN, true,
                        target, factionId).result());
        assertEquals(FactionStorage.RoleWriteResult.NOT_AUTHORIZED,
                storage.setRoleAuthorized(actor, FactionRole.ADMIN, true, "promote", target,
                        factionId, FactionRole.RECRUIT, FactionRole.MODERATOR));
        assertEquals(FactionStorage.ClaimWriteResult.NOT_AUTHORIZED,
                storage.claimAuthorized(actor, FactionRole.ADMIN, true,
                        new ChunkKey("world", 8, 9), factionId, null, 250, false));
        assertEquals(FactionStorage.ClaimDeleteResult.NOT_AUTHORIZED,
                storage.deleteClaimChecked(actor, FactionRole.ADMIN, true, owned, factionId));
        assertEquals(FactionStorage.ClaimDeleteResult.NOT_AUTHORIZED,
                storage.deleteClaimsForFactionChecked(actor, FactionRole.ADMIN, true, factionId));
        assertEquals(FactionStorage.FactionFieldWriteResult.NOT_AUTHORIZED,
                storage.updateDescriptionChecked(actor, factionId, FactionRole.ADMIN,
                        true, "should not save"));
        assertEquals(FactionStorage.HomeWriteResult.NOT_AUTHORIZED,
                storage.updateHomeChecked(actor, factionId, FactionRole.ADMIN, true,
                        new FactionData.Home("", "world", 33D, 64D, 49D, 0F, 0F)));
        assertEquals(FactionStorage.WarpDeleteResult.NOT_AUTHORIZED,
                storage.deleteWarpChecked(actor, factionId, FactionRole.ADMIN, true, "base"));
        assertEquals(FactionStorage.RenameWriteResult.NOT_AUTHORIZED,
                storage.renameFactionChecked(actor, FactionRole.ADMIN, factionId,
                        "Renamed", 10L, 20L));
        assertEquals(FactionStorage.DisbandWriteResult.NOT_AUTHORIZED,
                storage.deleteFactionChecked(factionId, actor, FactionRole.ADMIN, 20L));
        assertTrue(storage.load().factions().containsKey(factionId));
        assertTrue(storage.load().members().containsKey(target));
        assertEquals(factionId, storage.load().claims().get(owned));
    }

    @Test
    void claimClassificationAndUnclaimAllMetadataCommitWithOwnership() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        ClaimStorage claimStorage = new ClaimStorage(database);
        claimStorage.init();
        FactionStorage storage = new FactionStorage(database);
        storage.init();

        UUID leader = UUID.randomUUID();
        int factionId = storage.createFaction(new FactionData(-1, "Atomic", "", false, false,
                        100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        ChunkKey first = new ChunkKey("world", 4, 5);
        long expiresAt = System.currentTimeMillis() + 60_000L;

        assertEquals(FactionStorage.ClaimWriteResult.OK,
                storage.claimAuthorized(leader, FactionRole.LEADER, true, first,
                        factionId, null, 250, false, expiresAt));
        assertEquals(1, claimStorage.loadRaidClaims().size());
        assertEquals(expiresAt, claimStorage.loadRaidClaims().getFirst().expiresAtMillis());

        claimStorage.insertPurchasedSlot(factionId, 2, 2L);
        claimStorage.insertBaseClaimRegion(factionId, 1, first, 3L, List.of(first));
        assertTrue(claimStorage.loadRaidClaims().isEmpty());

        assertEquals(FactionStorage.ClaimDeleteResult.OK,
                storage.deleteClaimsForFactionChecked(leader, FactionRole.LEADER, true,
                        factionId, true));
        assertTrue(storage.load().claims().isEmpty());
        assertTrue(claimStorage.loadBaseClaims().isEmpty());
        assertTrue(claimStorage.loadRegionChunks().isEmpty());
        assertTrue(claimStorage.loadRaidClaims().isEmpty());
        assertEquals(java.util.Set.of(2), claimStorage.loadPurchasedSlots(factionId));
    }

    @Test
    void checkedFactionCreationEnforcesDurableMembershipAndDisbandCooldown() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        UUID creator = UUID.randomUUID();
        storage.savePower(new FactionPowerProfile(creator, 100D, 100D, 1L));
        FactionData first = new FactionData(-1, "First", "", false, false,
                100D, 100D, 1L, null);
        FactionMember leader = new FactionMember(creator, -1, FactionRole.LEADER,
                "Creator", 1L);
        FactionStorage.CreateFactionOutcome created = storage.createFactionChecked(first, leader, 1L);
        assertEquals(FactionStorage.CreateFactionWriteResult.OK, created.result());
        assertEquals(FactionStorage.CreateFactionWriteResult.ALREADY_MEMBER,
                storage.createFactionChecked(new FactionData(-1, "Second", "", false, false,
                                100D, 100D, 2L, null),
                        leader, 2L).result());

        assertEquals(FactionStorage.DisbandWriteResult.OK,
                storage.deleteFactionChecked(created.factionId(), creator,
                        FactionRole.LEADER, 1_000L));
        assertEquals(FactionStorage.CreateFactionWriteResult.COOLDOWN,
                storage.createFactionChecked(new FactionData(-1, "Second", "", false, false,
                                100D, 100D, 500L, null),
                        leader, 500L).result());
        assertEquals(FactionStorage.CreateFactionWriteResult.OK,
                storage.createFactionChecked(new FactionData(-1, "Second", "", false, false,
                                100D, 100D, 1_001L, null),
                        leader, 1_001L).result());
    }

    @Test
    void disbandAtomicallyPurgesBaseAndRaidClaimOwnership() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        ClaimStorage claims = new ClaimStorage(database);
        claims.init();
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        UUID leader = UUID.randomUUID();
        int factionId = storage.createFaction(new FactionData(-1, "Alpha", "", false, false,
                        10D, 10D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        claims.insertBaseClaimWithAnchorChunk(factionId, 1, "world", 4, 5, 1L);
        claims.insertPurchasedSlot(factionId, 2, 1L);
        claims.upsertRaidClaim("world", 8, 9, factionId, 1000L);

        storage.deleteFaction(factionId);

        assertTrue(claims.loadBaseClaims().isEmpty());
        assertTrue(claims.loadRegionChunks().isEmpty());
        assertTrue(claims.loadPurchasedSlots(factionId).isEmpty());
        assertTrue(claims.loadRaidClaims().isEmpty());
        assertTrue(storage.load().factions().isEmpty());
    }

    @Test
    void baseClaimMutationsRejectAControllerDemotedOnAnotherShard() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        ClaimStorage claims = new ClaimStorage(database);
        claims.init();
        FactionStorage storage = new FactionStorage(database);
        storage.init();
        new FactionSocialStorage(database).init();
        UUID leader = UUID.randomUUID();
        UUID controller = UUID.randomUUID();
        int factionId = storage.createFaction(new FactionData(-1, "BaseAuth", "", true, false,
                        100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        assertEquals(FactionStorage.JoinWriteResult.OK,
                storage.joinFactionChecked(new FactionMember(controller, factionId,
                        FactionRole.RECRUIT, "Controller", 2L), true, 16, 2L).result());
        assertEquals(FactionStorage.RoleWriteResult.OK,
                storage.setRoleChecked(controller, factionId, FactionRole.RECRUIT,
                        FactionRole.COLEADER));

        ChunkKey first = new ChunkKey("world", 4, 5);
        ChunkKey second = new ChunkKey("world", 4, 6);
        storage.saveClaim(first, factionId);
        storage.saveClaim(second, factionId);
        assertEquals(ClaimStorage.BaseMutationResult.OK,
                claims.insertBaseClaimRegionAuthorized(controller, factionId, 1,
                        first, 10L, List.of(first)));

        // Simulate another backend committing the demotion while this backend
        // still has the old Co-Leader role in memory.
        assertEquals(FactionStorage.RoleWriteResult.OK,
                storage.setRoleChecked(controller, factionId, FactionRole.COLEADER,
                        FactionRole.MEMBER));
        assertEquals(ClaimStorage.BaseMutationResult.NOT_AUTHORIZED,
                claims.insertBaseClaimRegionAuthorized(controller, factionId, 2,
                        second, 11L, List.of(second)));
        assertEquals(ClaimStorage.BaseMutationResult.NOT_AUTHORIZED,
                claims.convertBaseRegionToRaidAuthorized(controller, factionId, 1,
                        List.of(first), 1_000L));
        assertEquals(1, claims.loadBaseClaims().size());

        assertEquals(ClaimStorage.BaseMutationResult.OK,
                claims.convertBaseRegionToRaidAuthorized(leader, factionId, 1,
                        List.of(first), 1_000L));
        assertTrue(claims.loadBaseClaims().isEmpty());
    }

    @Test
    void durableShieldStateBlocksBaseRemovalAcrossShards() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage factions = new FactionStorage(database);
        factions.init();
        new FactionSocialStorage(database).init();
        ShieldStorage shields = new ShieldStorage(database);
        shields.init();
        ClaimStorage claims = new ClaimStorage(database);
        claims.init();
        claims.enableDurableShieldChecks(ZoneId.of("UTC"));

        UUID leader = UUID.randomUUID();
        int factionId = factions.createFaction(new FactionData(-1, "Shielded", "", true, false,
                        100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        ChunkKey chunk = new ChunkKey("world", 8, 9);
        factions.saveClaim(chunk, factionId);
        assertEquals(ClaimStorage.BaseMutationResult.OK,
                claims.insertBaseClaimRegionAuthorized(leader, factionId, 1, chunk, 2L, List.of(chunk)));

        long now = System.currentTimeMillis();
        shields.upsertActivation(new ShieldStorage.ActivationRow(factionId, now + 60_000L,
                now + 120_000L, now, now, leader.toString()));
        assertEquals(ClaimStorage.BaseMutationResult.SHIELDED,
                claims.convertBaseRegionToRaidAuthorized(leader, factionId, 1, List.of(chunk), now + 500_000L));
        assertEquals(1, claims.loadBaseClaims().size());

        // A durable admin-off override intentionally wins over the active row.
        shields.upsertOverride(new ShieldStorage.OverrideRow(factionId, "INACTIVE", 60_000L,
                leader.toString(), now));
        assertEquals(ClaimStorage.BaseMutationResult.OK,
                claims.convertBaseRegionToRaidAuthorized(leader, factionId, 1, List.of(chunk), now + 500_000L));
        assertTrue(claims.loadBaseClaims().isEmpty());
    }
}
