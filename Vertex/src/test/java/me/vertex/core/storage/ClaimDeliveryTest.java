package me.vertex.core.storage;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClaimDeliveryTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void partiallyRecoveredStackAddsOnlyTheMissingQuantity(){
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        var expected=ClaimDelivery.tagged(plugin,"delivery","token","row",0,new ItemStack(Material.DIAMOND,64));
        var partial=expected.item().clone();partial.setAmount(17);player.getInventory().setItem(0,partial);
        var missing=ClaimDelivery.missing(player,plugin,List.of(expected));
        assertEquals(47,missing.getFirst().item().getAmount());
        assertTrue(ClaimDelivery.add(player,missing));
        assertTrue(ClaimDelivery.missing(player,plugin,List.of(expected)).isEmpty());
        assertEquals(64,player.getInventory().getItem(0).getAmount());
    }
    @Test void fullInventoryRetainsExistingItemsWhenAddFails(){
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(Material.STONE,64));
        var item=ClaimDelivery.tagged(plugin,"delivery","token","row",0,new ItemStack(Material.DIAMOND));
        assertFalse(ClaimDelivery.canFit(player,List.of(item)));
        assertFalse(ClaimDelivery.add(player,List.of(item)));
        for(var stack:player.getInventory().getStorageContents())assertEquals(Material.STONE,stack.getType());
        assertTrue(player.getWorld().getEntities().stream().noneMatch(org.bukkit.entity.Item.class::isInstance));
    }
}
