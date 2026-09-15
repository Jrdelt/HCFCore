package me.vertex.core.network;

import me.vertex.core.storage.ClaimDelivery;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotInventoryPreparationTest {
    ServerMock server; PlayerMock player; Plugin plugin;
    @BeforeEach void setup(){
        server=MockBukkit.mock();plugin=MockBukkit.createMockPlugin();player=server.addPlayer();
        player.openInventory(server.createInventory(null,org.bukkit.event.inventory.InventoryType.WORKBENCH));
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void cursorFitsWithoutDrops(){
        player.setItemOnCursor(new ItemStack(Material.DIAMOND,7));
        assertTrue(SnapshotInventoryPreparation.prepare(player,plugin));
        assertTrue(player.getItemOnCursor().isEmpty()); assertEquals(7,player.getInventory().getItem(0).getAmount());
    }
    @Test void fullInventoryDoesNotLoseCursor(){
        for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(Material.STONE,64));
        player.setItemOnCursor(new ItemStack(Material.DIAMOND,7));
        assertFalse(SnapshotInventoryPreparation.prepare(player,plugin)); assertEquals(7,player.getItemOnCursor().getAmount());
    }
    @Test void customMenuMustFinishBeforeSnapshot(){
        var inv=server.createInventory(null,27);inv.setItem(0,new ItemStack(Material.DIAMOND));player.openInventory(inv);
        assertFalse(SnapshotInventoryPreparation.prepare(player,plugin));assertEquals(inv,player.getOpenInventory().getTopInventory());
    }
    @Test void markedCursorBlocksSnapshot(){
        player.setItemOnCursor(ClaimDelivery.tagged(plugin,"test","token",1L,0,new ItemStack(Material.DIAMOND)).item());
        assertFalse(SnapshotInventoryPreparation.prepare(player,plugin));
    }
    @Test void craftingInputsReturnButPreviewResultIsNotDuplicated(){
        var crafting=assertInstanceOf(org.bukkit.inventory.CraftingInventory.class,player.getOpenInventory().getTopInventory());
        ItemStack[] matrix=new ItemStack[9];matrix[0]=new ItemStack(Material.DIAMOND,2);crafting.setMatrix(matrix);
        crafting.setResult(new ItemStack(Material.DIAMOND_BLOCK));
        assertTrue(SnapshotInventoryPreparation.prepare(player,plugin));
        assertEquals(2,player.getInventory().getItem(0).getAmount());
        assertFalse(player.getInventory().contains(Material.DIAMOND_BLOCK));
    }
}
