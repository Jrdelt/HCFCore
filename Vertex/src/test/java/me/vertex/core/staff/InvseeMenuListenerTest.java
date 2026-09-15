package me.vertex.core.staff;

import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class InvseeMenuListenerTest {
    ServerMock server; PlayerMock viewer, target; InvseeMenuListener listener;
    Inventory menu; InvseeMenu.Holder holder;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); var plugin = MockBukkit.createMockPlugin();
        viewer = server.addPlayer("Staff"); viewer.setOp(true); target = server.addPlayer("Target");
        listener = new InvseeMenuListener(plugin, null);
        holder = new InvseeMenu.Holder(target.getUniqueId());
        menu = server.createInventory(holder, InvseeMenu.SIZE); holder.inventory = menu;
        target.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 8));
        InvseeMenu.refresh(menu, target, holder); viewer.openInventory(menu);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    InventoryClickEvent click(int raw, ClickType click, InventoryAction action) {
        var e = new InventoryClickEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER, raw, click, action);
        listener.onClick(e); return e;
    }
    int count(PlayerMock p) {
        int n=0; for(var i:p.getInventory().getContents()) if(i!=null&&i.getType()==Material.DIAMOND)n+=i.getAmount();
        var c=p.getItemOnCursor(); if(c!=null&&c.getType()==Material.DIAMOND)n+=c.getAmount(); return n;
    }
    @Test void staleShiftClickNeverCreditsStaff() {
        target.getInventory().setItem(0,null); target.getInventory().setItem(10,new ItemStack(Material.DIAMOND,8));
        assertTrue(click(0,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
        assertEquals(0,count(viewer)); assertEquals(8,count(target)); assertNull(menu.getItem(0));
    }
    @Test void liveShiftClickDebitsAndCreditsSynchronously() {
        assertTrue(click(0,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
        assertEquals(8,count(viewer)); assertEquals(0,count(target));
        click(0,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertEquals(8,count(viewer));
    }
    @Test void pickupRemovesFromTargetBeforeReachingCursor() {
        click(0,ClickType.RIGHT,InventoryAction.PICKUP_HALF);
        assertEquals(4,viewer.getItemOnCursor().getAmount()); assertEquals(4,count(target));
        viewer.closeInventory(); assertEquals(4,count(target));
    }
    @Test void fullStaffInventoryLeavesSourceIntact() {
        for(int i=0;i<36;i++)viewer.getInventory().setItem(i,new ItemStack(Material.STONE,64));
        click(0,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertEquals(8,count(target)); assertEquals(0,count(viewer));
    }
    @Test void collectCannotStealFromSnapshot() {
        assertTrue(click(45,ClickType.DOUBLE_CLICK,InventoryAction.COLLECT_TO_CURSOR).isCancelled());
        assertEquals(8,count(target)); assertEquals(0,count(viewer));
    }
    @Test void changingLiveItemAmountInvalidatesSnapshot() {
        target.getInventory().getItem(0).setAmount(3);
        click(0,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        assertEquals(0,count(viewer)); assertEquals(3,count(target));
    }
    @Test void dragConservesCursorAndTargetItemsInSameOperation(){
        playerCursor(4);
        var e=new InventoryDragEvent(viewer.getOpenInventory(),new ItemStack(Material.DIAMOND,2),
                new ItemStack(Material.DIAMOND,4),false,java.util.Map.of(1,new ItemStack(Material.DIAMOND,2)));
        listener.onDrag(e);assertTrue(e.isCancelled());assertEquals(10,count(target));assertEquals(2,count(viewer));
    }
    private void playerCursor(int amount){viewer.setItemOnCursor(new ItemStack(Material.DIAMOND,amount));}
}
