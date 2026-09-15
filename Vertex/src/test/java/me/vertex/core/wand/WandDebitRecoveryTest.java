package me.vertex.core.wand;

import me.vertex.core.collector.ChunkCollectorData;
import me.vertex.core.collector.ChunkCollectorManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISS-11: a TNT Wand conversion's bank credit is committed to SQL --
 * irrevocably -- before the source materials are ever removed from the
 * container. These tests simulate a hard crash in that exact gap by journaling
 * the debit exactly as {@code WandListener.settleTnt} would, but WITHOUT
 * performing the removal (standing in for "the process died before the
 * container's own state -- a chunk autosave for a chest, a PDC write for a
 * collector -- ever reached disk"), then driving {@code WandManager}'s chunk-
 * load reconciliation the way a restart would.
 */
class WandDebitRecoveryTest {
    private ServerMock server;
    private PluginMock plugin;
    private WorldMock world;
    private WandManager wands;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("wand-debit-recovery-world");
        wands = new WandManager(plugin);
        wands.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void survivedDebitIsRemovedFromAChestOnChunkReconcile() {
        var block = world.getBlockAt(10, 64, 10);
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        chest.getInventory().addItem(new ItemStack(Material.GUNPOWDER, 40));
        chest.getInventory().addItem(new ItemStack(Material.SAND, 20));

        // The crash: a conversion already banked its TNT and journaled that
        // 25 gunpowder / 5 sand are owed here, but never removed them.
        wands.journalDebit(block.getLocation(), 25, 5);

        // The restart: the chunk (already "loaded" in this test world) must
        // be forced to reconcile against the journal.
        wands.reconcileChunk(block.getChunk(), null);

        WandContainer container = WandContainer.of(block, null);
        var contents = container.contents(item -> true);
        assertEquals(15, contents.getOrDefault(Material.GUNPOWDER, 0), "owed gunpowder must be force-removed");
        assertEquals(15, contents.getOrDefault(Material.SAND, 0), "owed sand must be force-removed");
    }

    @Test
    void alreadyRemovedDebitIsNotDoubleAppliedOnReconcile() {
        var block = world.getBlockAt(11, 64, 10);
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        // Nothing of that material is left -- as if the removal had already
        // happened before the (very narrow, synchronous) crash window
        // between the container write and the journal clear.
        chest.getInventory().addItem(new ItemStack(Material.DIRT, 1));

        wands.journalDebit(block.getLocation(), 25, 0);
        wands.reconcileChunk(block.getChunk(), null);

        WandContainer container = WandContainer.of(block, null);
        assertEquals(0, container.contents(item -> true).getOrDefault(Material.GUNPOWDER, 0),
                "reconcile must never remove more than the container actually has");
        assertEquals(1, container.contents(item -> true).getOrDefault(Material.DIRT, 0),
                "unrelated contents must be untouched");
    }

    @Test
    void survivedDebitIsRemovedFromACollectorOnChunkReconcile() {
        ChunkCollectorManager collectors = new ChunkCollectorManager(plugin, null, null);
        collectors.load();
        var block = world.getBlockAt(12, 64, 10);
        block.setType(Material.SHULKER_BOX);
        Location location = block.getLocation();
        ChunkCollectorData data = new ChunkCollectorData(0, UUID.randomUUID(), "Faction");
        data.setStored(Material.GUNPOWDER, 40);
        collectors.writeData(location, data);

        wands.journalDebit(location, 25, 0);
        wands.reconcileChunk(block.getChunk(), collectors);

        assertEquals(15, collectors.readData(location).stored(Material.GUNPOWDER));
    }

    @Test
    void reconciledEntryIsClearedAndNotReplayedAgain() {
        var block = world.getBlockAt(13, 64, 10);
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        chest.getInventory().addItem(new ItemStack(Material.GUNPOWDER, 40));

        wands.journalDebit(block.getLocation(), 25, 0);
        wands.reconcileChunk(block.getChunk(), null);
        // A second reconcile of the same chunk must be a no-op: the entry is
        // already cleared, so nothing further is removed.
        wands.reconcileChunk(block.getChunk(), null);

        WandContainer container = WandContainer.of(block, null);
        assertEquals(15, container.contents(item -> true).getOrDefault(Material.GUNPOWDER, 0));
    }

    @Test
    void journaledDebitSurvivingARestartIsReconciledOnLoad() throws Exception {
        var block = world.getBlockAt(14, 64, 10);
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        chest.getInventory().addItem(new ItemStack(Material.GUNPOWDER, 40));
        world.loadChunk(block.getChunk().getX(), block.getChunk().getZ());

        wands.journalDebit(block.getLocation(), 25, 0);

        // Simulate a restart: a fresh manager reading the same data folder.
        WandManager restarted = new WandManager(plugin);
        restarted.load();
        restarted.loadDebitJournal(null);

        WandContainer container = WandContainer.of(block, null);
        assertEquals(15, container.contents(item -> true).getOrDefault(Material.GUNPOWDER, 0),
                "loadDebitJournal must reconcile an already-loaded chunk immediately");
        assertTrue(new WandDebitWal(plugin.getDataFolder()).load().isEmpty());
    }
}
