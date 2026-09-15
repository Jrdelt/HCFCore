package me.vertex.core.storage;

import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.plugin.Plugin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryMarkerCleanupListenerTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}

    @Test void droppingAMarkedItemClearsItsMarker(){
        ServerMock server=MockBukkit.mock();Plugin plugin=MockBukkit.createMockPlugin();
        PlayerMock player=server.addPlayer();
        DeliveryMarkerCleanupListener listener=new DeliveryMarkerCleanupListener(plugin);
        var marked=ClaimDelivery.tagged(plugin,"delivery","token","row",0,new ItemStack(Material.DIAMOND)).item();
        Item ground=player.getWorld().dropItem(player.getLocation(),marked);
        assertTrue(ClaimDelivery.isMarked(plugin,ground.getItemStack()));
        listener.onDrop(new PlayerDropItemEvent(player,ground));
        assertFalse(ClaimDelivery.isMarked(plugin,ground.getItemStack()));
    }

    @Test void deathDropsLoseTheirMarkerSoAPickupIsNeverBlocked(){
        ServerMock server=MockBukkit.mock();Plugin plugin=MockBukkit.createMockPlugin();
        PlayerMock player=server.addPlayer();
        DeliveryMarkerCleanupListener listener=new DeliveryMarkerCleanupListener(plugin);
        var marked=ClaimDelivery.tagged(plugin,"delivery","token","row",0,new ItemStack(Material.DIAMOND)).item();
        List<ItemStack> drops=new java.util.ArrayList<>(List.of(marked));
        PlayerDeathEvent event=new PlayerDeathEvent(player,DamageSource.builder(DamageType.GENERIC).build(),
                drops,0,null,false);
        listener.onDeath(event);
        assertFalse(ClaimDelivery.isMarked(plugin,event.getDrops().getFirst()));
    }

    @Test void itemsKeptOnDeathAreNotTouched(){
        // Confirms the listener only strips markers from actual ground drops,
        // never from an item that keepInventory (or a no-drop rule) retains --
        // that item is still pending delivery and must stay marked so a
        // restart can't pay the same claim twice.
        ServerMock server=MockBukkit.mock();Plugin plugin=MockBukkit.createMockPlugin();
        PlayerMock player=server.addPlayer();
        DeliveryMarkerCleanupListener listener=new DeliveryMarkerCleanupListener(plugin);
        var kept=ClaimDelivery.tagged(plugin,"delivery","token","row",0,new ItemStack(Material.DIAMOND)).item();
        PlayerDeathEvent event=new PlayerDeathEvent(player,DamageSource.builder(DamageType.GENERIC).build(),
                List.of(),0,null,false);
        listener.onDeath(event);
        assertTrue(ClaimDelivery.isMarked(plugin,kept));
    }
}
