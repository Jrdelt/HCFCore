package me.vertex.core.storage;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryAccessTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void onlyOnePendingInventoryOperationCanOwnPlayer(){
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        assertTrue(InventoryAccess.reserve(plugin,player));
        try{assertFalse(InventoryAccess.reserve(plugin,player));assertFalse(InventoryAccess.ready(plugin,player));}
        finally{InventoryAccess.release(player);}
        assertTrue(InventoryAccess.ready(plugin,player));
    }

    @Test void markedDeliveryBlocksOrdinaryMutationsButNotItsOwnRecovery(){
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        player.getInventory().setItem(0,ClaimDelivery.tagged(plugin,"test","token",1L,0,
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND)).item());
        assertFalse(InventoryAccess.ready(plugin,player));
        assertTrue(InventoryAccess.readyForHandoff(plugin,player));
    }
}
