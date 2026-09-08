package me.vertex.core.staff;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu is a snapshot of a player who keeps playing, so it can be showing
 * an item they no longer hold. Acting on that stale view is how items get
 * duplicated, in both directions.
 */
class InvseeMenuTest {

    private static final int SLOT = 5;
    private static final int OTHER_SLOT = 20;

    private ServerMock server;
    private PlayerMock target;
    private Inventory menu;
    private InvseeMenu.Holder holder;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        target = server.addPlayer("Target");

        holder = new InvseeMenu.Holder(target.getUniqueId());
        menu = server.createInventory(holder, InvseeMenu.SIZE);
        holder.inventory = menu;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void openOn(ItemStack... storage) {
        target.getInventory().setStorageContents(new ItemStack[36]);
        for (int slot = 0; slot < storage.length; slot++) {
            target.getInventory().setItem(slot, storage[slot]);
        }
        InvseeMenu.refresh(menu, target, holder);
    }

    private static int countOf(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    @Test
    void appliesAnOrdinaryEditWhenNothingChangedUnderneath() {
        openOn();
        menu.setItem(SLOT, new ItemStack(Material.DIAMOND, 3));

        assertEquals(0, InvseeMenu.writeBack(menu, target, holder));
        assertEquals(3, countOf(target, Material.DIAMOND));
    }

    /**
     * The reported bug: the target moves an item themselves, then a staff
     * edit built on the stale view writes it back, leaving them holding two.
     */
    @Test
    void refusesToWriteBackASlotTheTargetHasSinceChanged() {
        openOn(new ItemStack(Material.DIAMOND_SWORD));

        // The target moves the sword elsewhere while the menu is open.
        target.getInventory().setItem(0, null);
        target.getInventory().setItem(10, new ItemStack(Material.DIAMOND_SWORD));

        // Staff, still seeing it in slot 0, drags that stale copy to slot 20.
        menu.setItem(0, null);
        menu.setItem(OTHER_SLOT, new ItemStack(Material.DIAMOND_SWORD));

        assertTrue(InvseeMenu.writeBack(menu, target, holder) > 0, "the stale slot should be reported as a conflict");
        assertEquals(1, countOf(target, Material.DIAMOND_SWORD), "the sword must not have been duplicated");
    }

    /**
     * The other direction: the target drops an item, then staff take the copy
     * the menu is still showing, creating one that exists nowhere.
     */
    @Test
    void refusesToHandOutAnItemTheTargetNoLongerHas() {
        openOn(new ItemStack(Material.DIAMOND, 5));
        target.getInventory().setItem(0, null);

        assertFalse(InvseeMenu.isSlotFresh(target, holder, 0),
                "a slot the target has emptied must not read as fresh");
    }

    @Test
    void reportsASlotAsFreshWhileItStillMatches() {
        openOn(new ItemStack(Material.DIAMOND, 5));
        assertTrue(InvseeMenu.isSlotFresh(target, holder, 0));
    }

    @Test
    void refreshRestoresTheMenuToTheTargetsRealContents() {
        openOn(new ItemStack(Material.DIAMOND_SWORD));
        target.getInventory().setItem(0, new ItemStack(Material.STONE, 2));

        InvseeMenu.refresh(menu, target, holder);

        assertEquals(Material.STONE, menu.getItem(0).getType());
        assertTrue(InvseeMenu.isSlotFresh(target, holder, 0), "refresh should reset the baseline too");
    }

    /**
     * An untouched slot must never be pushed back: the target's own change to
     * it is the newer truth, and overwriting it would destroy their item.
     */
    @Test
    void pullsConcurrentTargetChangesIntoUntouchedSlots() {
        openOn();
        target.getInventory().setItem(3, new ItemStack(Material.GOLD_INGOT, 2));
        menu.setItem(SLOT, new ItemStack(Material.DIAMOND));

        InvseeMenu.writeBack(menu, target, holder);

        assertEquals(2, countOf(target, Material.GOLD_INGOT), "the target's own pickup must survive");
        assertEquals(Material.GOLD_INGOT, menu.getItem(3).getType(), "and be reflected back into the menu");
    }

    @Test
    void clearingASlotStillWorksWhenItIsFresh() {
        openOn(new ItemStack(Material.DIAMOND, 4));
        menu.setItem(0, null);

        assertEquals(0, InvseeMenu.writeBack(menu, target, holder));
        assertNull(target.getInventory().getItem(0));
    }
}
