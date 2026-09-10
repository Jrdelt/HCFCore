package me.vertex.core.dupe;

import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import me.vertex.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers identity assignment, case resolution transitions, and that no
 * dupe metadata (the instance ID included) is ever written to an item's
 * visible lore or display name -- the framework must stay entirely a PDC
 * concern.
 */
class DupeManagerTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private Database database;
    private DupeStorage storage;
    private DupeManager manager;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new DupeStorage(database);
        storage.init();
        SqlStorage sqlStorage = new SqlStorage(database);
        sqlStorage.init();
        Messages messages = new Messages(plugin, new UserManager(plugin, sqlStorage));
        manager = new DupeManager(plugin, storage, messages, new TrackedItemIds(plugin));
        manager.load();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.awaitWrites();
        }
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void ensureIdentityAssignsAnIdToATrackedMaterialExactlyOnce() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        String first = manager.ensureIdentity(sword);
        assertNotNull(first);
        String second = manager.ensureIdentity(sword);
        assertEquals(first, second, "a second call must not replace an existing identity");
    }

    @Test
    void ensureIdentityIgnoresAnUntrackedMaterial() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        assertNull(manager.ensureIdentity(dirt));
    }

    @Test
    void ensureIdentityNeverWritesVisibleLoreOrDisplayName() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        manager.ensureIdentity(sword);
        var meta = sword.getItemMeta();
        assertFalse(meta.hasLore(), "identity assignment must never touch item lore");
        assertFalse(meta.hasDisplayName(), "identity assignment must never touch the display name");
        // The identity only exists as hidden PDC data.
        assertNotNull(meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, "tracked_item_id"), PersistentDataType.STRING));
    }

    @Test
    void resolveTransitionsAnOpenCaseToResolved() throws Exception {
        DupeCase entry = new DupeCase("case-1", "fp-1", "holder", "Holder", "item", "NETHERITE_SWORD",
                "duplicate-item-id", "[]", DupeCase.STATUS_OPEN, System.currentTimeMillis(), null, 0L, null);
        storage.create(entry);

        boolean changed = manager.resolve("case-1", "Staff", DupeCase.STATUS_RESOLVED, "false positive")
                .get(5, TimeUnit.SECONDS);
        assertTrue(changed);
        DupeCase reloaded = manager.findCase("case-1").get(5, TimeUnit.SECONDS);
        assertEquals(DupeCase.STATUS_RESOLVED, reloaded.status());
    }

    @Test
    void resolveOnAnUnknownCaseReportsNoChange() throws Exception {
        assertFalse(manager.resolve("missing-case", "Staff", DupeCase.STATUS_DISMISSED, "n/a").get(5, TimeUnit.SECONDS));
    }

    @Test
    void confirmTransitionsAnOpenCaseAndLeavesItOutOfTheOpenCount() throws Exception {
        DupeCase entry = new DupeCase("case-1", "fp-1", "holder", "Holder", "item", "NETHERITE_SWORD",
                "duplicate-item-id", "[]", DupeCase.STATUS_OPEN, System.currentTimeMillis(), null, 0L, null);
        storage.create(entry);

        manager.resolve("case-1", "Staff", DupeCase.STATUS_CONFIRMED, "genuine duplicate").get(5, TimeUnit.SECONDS);
        assertEquals(0, manager.openCaseCount().get(5, TimeUnit.SECONDS));
    }

    @Test
    void openCasesOrderingIsDeterministicAcrossReads() throws Exception {
        long sameTimestamp = System.currentTimeMillis();
        storage.create(new DupeCase("case-b", "fp-b", null, null, "item", "NETHERITE_AXE",
                "duplicate-item-id", "[]", DupeCase.STATUS_OPEN, sameTimestamp, null, 0L, null));
        storage.create(new DupeCase("case-a", "fp-a", null, null, "item", "NETHERITE_AXE",
                "duplicate-item-id", "[]", DupeCase.STATUS_OPEN, sameTimestamp, null, 0L, null));

        List<DupeCase> cases = manager.openCases(0).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("case-a", "case-b"), cases.stream().map(DupeCase::id).toList());
    }
}
