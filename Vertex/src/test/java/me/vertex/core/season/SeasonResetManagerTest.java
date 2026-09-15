package me.vertex.core.season;

import me.vertex.core.factions.FactionStorage;
import me.vertex.core.resetvault.ResetVaultData;
import me.vertex.core.resetvault.ResetVaultPhase;
import me.vertex.core.resetvault.ResetVaultStorage;
import me.vertex.core.spawner.SpawnerData;
import me.vertex.core.spawner.SpawnerStorage;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SeasonResetManagerTest {

    @TempDir
    Path tempFolder;

    private ServerMock server;
    private Plugin plugin;
    private Database database;
    private SeasonResetManager seasonResetManager;
    private ResetVaultStorage resetVaultStorage;

    @BeforeEach
    void setUp() throws SQLException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        YamlConfiguration config = new YamlConfiguration();
        database = new Database(config, tempFolder.toFile(), Database.Dialect.SQLITE);

        me.vertex.core.storage.StorageMigrator.prepare(database, database);
        resetVaultStorage = new ResetVaultStorage(database);

        seasonResetManager = new SeasonResetManager(plugin, database, null);
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    @Test
    void resetsSeasonalTablesAndAccessBlocksWhilePreservingVaultsAndBackups() throws SQLException {
        // 1. Seed seasonal tables
        org.bukkit.World world = server.addSimpleWorld("world");
        SpawnerStorage spawners = new SpawnerStorage(database);
        spawners.init();
        spawners.save(new Location(world, 10, 64, 10), new SpawnerData(EntityType.BLAZE, List.of(100L), "FactionA"));

        // 2. Seed Reset Vault access blocks (tied to old map)
        int blockId = resetVaultStorage.saveAccessBlock("world", 100, 64, -200, "rv_block_1");

        // 3. Set Reset Vault phase to DEPOSIT (e.g. EOTW deposit phase before reset)
        resetVaultStorage.savePhase(ResetVaultPhase.DEPOSIT);

        // 4. Seed persistent player vaults, backups, audit logs, and blacklist
        UUID playerUuid = UUID.randomUUID();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        resetVaultStorage.savePlayerData(new ResetVaultData(playerUuid, "VortexPlayer", 3, List.of(sword)));

        int backupId = resetVaultStorage.saveBackup(
                System.currentTimeMillis(), "Season 1 Final", "Console", 1, 100L, "checksum123", "COMPLETE", new byte[] {42, 43, 44});
        resetVaultStorage.saveRestoreHistory(backupId, "Admin", System.currentTimeMillis());
        resetVaultStorage.appendAuditLog(System.currentTimeMillis(), "DEPOSIT", "VortexPlayer", playerUuid.toString(), "Deposited sword");
        int blacklistId = resetVaultStorage.addBlacklistEntry("MATERIAL", "BEDROCK", "rejected.blacklisted");

        // Verify pre-reset counts
        assertEquals(1, countRows("spawners"));
        assertEquals(1, countRows("rv_access_blocks"));
        assertEquals(ResetVaultPhase.DEPOSIT, resetVaultStorage.loadPhase());

        // Execute season reset transaction
        Map<String, Integer> affected = seasonResetManager.resetTransaction();

        // 5. Verify seasonal tables and old access blocks are wiped
        assertEquals(0, countRows("spawners"));
        assertEquals(0, countRows("rv_access_blocks"));

        // 6. Verify Reset Vault phase is reset to CLOSED for the start of the new season
        assertEquals(ResetVaultPhase.CLOSED, resetVaultStorage.loadPhase());

        // 7. Verify player vault data, backups, audit log, and blacklist survive completely intact
        assertEquals(1, countRows("rv_player_data"));
        ResetVaultData playerVault = resetVaultStorage.loadPlayerData(playerUuid);
        assertNotNull(playerVault);
        assertEquals("VortexPlayer", playerVault.lastKnownIgn());
        assertEquals(3, playerVault.permanentBonusSlots());
        assertEquals(1, playerVault.itemCount());
        assertEquals(Material.DIAMOND_SWORD, playerVault.items().get(0).getType());

        assertEquals(1, countRows("rv_backups"));
        assertEquals(1, countRows("rv_backup_data"));
        assertEquals(1, countRows("rv_restore_history"));
        assertArrayEquals(new byte[] {42, 43, 44}, resetVaultStorage.loadBackupData(backupId));

        assertEquals(1, countRows("rv_audit_log"));
        assertEquals(1, countRows("rv_blacklist"));
    }

    @Test
    void resetsInMemoryResetVaultManagerOnReset() {
        me.vertex.core.user.UserManager userManager = new me.vertex.core.user.UserManager(plugin, null);
        me.vertex.core.lang.Messages messages = new me.vertex.core.lang.Messages(plugin, userManager);
        me.vertex.core.resetvault.ResetVaultManager manager =
                new me.vertex.core.resetvault.ResetVaultManager(plugin, resetVaultStorage, messages, null, null, null);

        manager.setPhase(ResetVaultPhase.DEPOSIT);
        assertEquals(ResetVaultPhase.DEPOSIT, manager.phase());

        seasonResetManager.setResetVaultManager(manager);

        // Execute reset directly via onSeasonReset()
        manager.onSeasonReset();

        assertEquals(ResetVaultPhase.CLOSED, manager.phase());
    }

    private int countRows(String table) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = s.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
