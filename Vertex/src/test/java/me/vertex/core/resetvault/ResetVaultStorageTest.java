package me.vertex.core.resetvault;

import me.vertex.core.storage.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetVaultStorageTest {

    @TempDir Path dataFolder;
    private Database database;
    private ResetVaultStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ResetVaultStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
        MockBukkit.unmock();
    }

    @Test
    void testPhasePersistence() throws Exception {
        assertEquals(ResetVaultPhase.CLOSED, storage.loadPhase());

        storage.savePhase(ResetVaultPhase.DEPOSIT);
        assertEquals(ResetVaultPhase.DEPOSIT, storage.loadPhase());

        storage.savePhase(ResetVaultPhase.WITHDRAW);
        assertEquals(ResetVaultPhase.WITHDRAW, storage.loadPhase());
    }

    @Test
    void testPlayerDataSaveAndLoad() throws Exception {
        UUID uuid = UUID.randomUUID();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
        ResetVaultData data = new ResetVaultData(uuid, "Steve", 3, List.of(sword));

        storage.savePlayerData(data);

        ResetVaultData loaded = storage.loadPlayerData(uuid);
        assertNotNull(loaded);
        assertEquals(uuid, loaded.uuid());
        assertEquals("Steve", loaded.lastKnownIgn());
        assertEquals(3, loaded.permanentBonusSlots());
        assertEquals(1, loaded.itemCount());
        assertEquals(Material.DIAMOND_SWORD, loaded.items().getFirst().getType());
    }

    @Test
    void testAllPlayerDataOperations() throws Exception {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        ResetVaultData d1 = new ResetVaultData(u1, "User1", 1, List.of(new ItemStack(Material.EMERALD, 1)));
        ResetVaultData d2 = new ResetVaultData(u2, "User2", 2, List.of(new ItemStack(Material.GOLD_INGOT, 1)));

        storage.saveAllPlayerData(Map.of(u1, d1, u2, d2));

        Map<UUID, ResetVaultData> all = storage.loadAllPlayerData();
        assertEquals(2, all.size());
        assertEquals("User1", all.get(u1).lastKnownIgn());
        assertEquals("User2", all.get(u2).lastKnownIgn());
    }

    @Test
    void testAccessBlocksCrud() throws Exception {
        int id = storage.saveAccessBlock("world", 100, 64, 200, "rv_test_holo");
        assertTrue(id > 0);

        List<ResetVaultStorage.AccessBlockRow> rows = storage.loadAccessBlocks();
        assertEquals(1, rows.size());
        assertEquals("world", rows.getFirst().world());
        assertEquals(100, rows.getFirst().x());
        assertEquals(64, rows.getFirst().y());
        assertEquals(200, rows.getFirst().z());
        assertEquals("rv_test_holo", rows.getFirst().hologramId());

        storage.deleteAccessBlock(id);
        assertTrue(storage.loadAccessBlocks().isEmpty());
    }

    @Test
    void testBackupsAndHistory() throws Exception {
        byte[] fakeData = new byte[]{1, 2, 3, 4, 5};
        int backupId = storage.saveBackup(System.currentTimeMillis(), "Season 1", "Admin",
                10, fakeData.length, "dummy_checksum", "COMPLETE", fakeData);
        assertTrue(backupId > 0);

        List<ResetVaultStorage.BackupRecord> history = storage.loadBackupHistory();
        assertEquals(1, history.size());
        assertEquals("Season 1", history.getFirst().mapLabel());
        assertEquals("Admin", history.getFirst().initiator());
        assertTrue(history.getFirst().isSelectable());

        byte[] loadedData = storage.loadBackupData(backupId);
        assertNotNull(loadedData);
        assertEquals(fakeData.length, loadedData.length);
    }

    @Test
    void testBlacklistCrud() throws Exception {
        int id1 = storage.addBlacklistEntry("MATERIAL", "BEDROCK", "custom.rejection");
        int id2 = storage.addBlacklistEntry("CUSTOM_ITEM", "backpack", "");

        List<ResetVaultStorage.BlacklistEntry> entries = storage.loadBlacklist();
        assertEquals(2, entries.size());

        storage.removeBlacklistEntry(id1);
        entries = storage.loadBlacklist();
        assertEquals(1, entries.size());
        assertEquals("backpack", entries.getFirst().entryKey());
    }
}
