package me.vertex.core.resetvault;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetVaultDataTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testBasicPropertiesAndImmutability() {
        UUID uuid = UUID.randomUUID();
        ItemStack diamond = new ItemStack(Material.DIAMOND, 1);
        ResetVaultData data = new ResetVaultData(uuid, "Steve", 2, List.of(diamond));

        assertEquals(uuid, data.uuid());
        assertEquals("Steve", data.lastKnownIgn());
        assertEquals(2, data.permanentBonusSlots());
        assertEquals(1, data.itemCount());
        assertEquals(Material.DIAMOND, data.items().getFirst().getType());

        // Modifying returned list is not supported
        assertNotSame(diamond, data.items().getFirst());
    }

    @Test
    void testAddItemAppendsAndPreservesOrder() {
        UUID uuid = UUID.randomUUID();
        ResetVaultData data = new ResetVaultData(uuid, "Alex", 0, List.of());

        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
        ItemStack bow = new ItemStack(Material.BOW, 1);

        data = data.addItem(sword);
        assertEquals(1, data.itemCount());
        assertEquals(Material.DIAMOND_SWORD, data.items().get(0).getType());

        data = data.addItem(bow);
        assertEquals(2, data.itemCount());
        assertEquals(Material.DIAMOND_SWORD, data.items().get(0).getType());
        assertEquals(Material.BOW, data.items().get(1).getType());
    }

    @Test
    void testRemoveItemCompactsRemaining() {
        UUID uuid = UUID.randomUUID();
        ItemStack item1 = new ItemStack(Material.GOLD_INGOT, 1);
        ItemStack item2 = new ItemStack(Material.IRON_INGOT, 1);
        ItemStack item3 = new ItemStack(Material.COPPER_INGOT, 1);

        ResetVaultData data = new ResetVaultData(uuid, "Player", 1, List.of(item1, item2, item3));
        assertEquals(3, data.itemCount());

        // Remove middle item (iron)
        data = data.removeItem(1);
        assertEquals(2, data.itemCount());
        assertEquals(Material.GOLD_INGOT, data.items().get(0).getType());
        assertEquals(Material.COPPER_INGOT, data.items().get(1).getType());
    }

    @Test
    void testWithBonusSlots() {
        UUID uuid = UUID.randomUUID();
        ResetVaultData data = new ResetVaultData(uuid, "Player", 0, List.of());
        assertEquals(0, data.permanentBonusSlots());

        data = data.withBonusSlots(5);
        assertEquals(5, data.permanentBonusSlots());
    }
}
