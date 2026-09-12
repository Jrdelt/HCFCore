package me.vertex.core.faction;

import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.factions.FactionSocialStorage;
import me.vertex.core.factions.FactionStorage;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionVaultStorageTest {
    @TempDir Path dataFolder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void leaseAcquisitionRechecksTheDurableMemberRole() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage factions = new FactionStorage(database);
        factions.init();
        new FactionSocialStorage(database).init();
        FactionVaultStorage vaults = new FactionVaultStorage(database);
        vaults.init();

        UUID leader = UUID.randomUUID();
        UUID controller = UUID.randomUUID();
        int factionId = factions.createFaction(new FactionData(-1, "VaultAuth", "", true,
                        false, 100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        factions.joinFactionChecked(new FactionMember(controller, factionId, FactionRole.RECRUIT,
                "Controller", 2L), true, 16, 2L);
        factions.setRoleChecked(controller, factionId, FactionRole.RECRUIT, FactionRole.COLEADER);

        UUID firstToken = UUID.randomUUID();
        FactionVaultStorage.AcquireResult first = vaults.acquire(factionId, controller,
                "Controller", firstToken, 10L, 10_000L, FactionRole.COLEADER, true);
        assertTrue(first.authorized());
        assertTrue(first.lease().acquired());
        vaults.release(factionId, controller, firstToken);

        // Represents a role change committed by another shard before the
        // local in-memory faction cache receives its invalidation event.
        factions.setRoleChecked(controller, factionId, FactionRole.COLEADER, FactionRole.MEMBER);
        FactionVaultStorage.AcquireResult stale = vaults.acquire(factionId, controller,
                "Controller", UUID.randomUUID(), 20L, 10_020L, FactionRole.COLEADER, true);
        assertFalse(stale.authorized());

        // /fa's explicit bypass still participates in the same exclusive lease.
        FactionVaultStorage.AcquireResult admin = vaults.acquire(factionId, leader,
                "Admin", UUID.randomUUID(), 30L, 10_030L, null, true);
        assertTrue(admin.authorized());
        assertTrue(admin.lease().acquired());
    }

    @Test
    void bankMutationRechecksTheDurableRoleAndPermission() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        FactionStorage factions = new FactionStorage(database);
        factions.init();
        new FactionSocialStorage(database).init();
        FactionBankStorage banks = new FactionBankStorage(database);
        banks.init();

        UUID leader = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        int factionId = factions.createFaction(new FactionData(-1, "BankAuth", "", true,
                        false, 100D, 100D, 1L, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", 1L));
        factions.joinFactionChecked(new FactionMember(actor, factionId, FactionRole.RECRUIT,
                "Actor", 2L), true, 16, 2L);
        factions.setRoleChecked(actor, factionId, FactionRole.RECRUIT, FactionRole.ADMIN);
        factions.savePermission(factionId, "admin", "bank-withdraw", true);
        banks.mutate(factionId, current -> new FactionBankStorage.StoredBank(
                factionId, 100D, current.experience(), current.tnt()));

        assertTrue(banks.mutateAuthorized(factionId, actor, FactionRole.ADMIN,
                "bank-withdraw", false, current -> new FactionBankStorage.StoredBank(
                        factionId, current.money() - 10D, current.experience(), current.tnt())).isPresent());

        factions.setRoleChecked(actor, factionId, FactionRole.ADMIN, FactionRole.MEMBER);
        assertTrue(banks.mutateAuthorized(factionId, actor, FactionRole.ADMIN,
                "bank-withdraw", false, current -> new FactionBankStorage.StoredBank(
                        factionId, current.money() - 10D, current.experience(), current.tnt())).isEmpty());
        assertTrue(Math.abs(banks.loadAll().getFirst().money() - 90D) < 0.000001D);
    }
}
