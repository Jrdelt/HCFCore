package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TradeListenerSafetyTest {
    private ServerMock server;
    private PlayerMock player;
    private TradeSession session;
    private TradeListener listener;
    private TradeManager manager;
    @TempDir Path dataFolder;
    private Database database;
    private TradeStorage storage;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();
        player=server.addPlayer();var other=server.addPlayer();
        var messages=new Messages(plugin,new UserManager(plugin,null));messages.load();
        database=new Database(new YamlConfiguration(),dataFolder.toFile());
        storage=new TradeStorage(database); storage.init(); storage.startOwnership();
        manager=new TradeManager(plugin,storage,null,messages);
        var field=TradeManager.class.getDeclaredField("blockedItems");field.setAccessible(true);field.set(manager,Set.of(Material.TNT));
        session=new TradeSession(player.getUniqueId(),other.getUniqueId());
        session.inventory=server.createInventory(new TradeMenu.Holder(session.id),TradeMenu.SIZE);
        field=TradeManager.class.getDeclaredField("sessions");field.setAccessible(true);
        ((Map<UUID,TradeSession>)field.get(manager)).put(player.getUniqueId(),session);
        listener=new TradeListener(plugin,manager,messages);
        player.openInventory(session.inventory);
    }
    @AfterEach void cleanup(){if(manager!=null)manager.awaitWrites();MockBukkit.unmock();if(database!=null)database.close();}

    @Test void healthyOwnerAllowsOrdinaryOfferClicks(){
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,
                TradeMenu.LEFT_SLOTS[0],ClickType.LEFT,InventoryAction.PICKUP_ALL);
        listener.onClick(event);assertFalse(event.isCancelled());
    }

    @Test void lostOwnershipBlocksOfferClicksAndDrags() throws Exception {
        storage.releaseOwnership();
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,
                TradeMenu.LEFT_SLOTS[0],ClickType.LEFT,InventoryAction.PICKUP_ALL);
        listener.onClick(event);assertTrue(event.isCancelled());
        var drag=new InventoryDragEvent(player.getOpenInventory(),new ItemStack(Material.AIR),
                new ItemStack(Material.DIAMOND),false,Map.of(TradeMenu.LEFT_SLOTS[0],new ItemStack(Material.DIAMOND)));
        listener.onDrag(drag);assertTrue(drag.isCancelled());
    }

    @Test void bottomDoubleClickCannotCollectLockedOrOpposingOffers(){
        session.requesterLocked=true;session.targetLocked=true;
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,
                TradeMenu.SIZE,ClickType.DOUBLE_CLICK,InventoryAction.COLLECT_TO_CURSOR);
        listener.onClick(event);assertTrue(event.isCancelled());
    }
    @Test void numberKeyValidatesHotbarItemInsteadOfEmptyCursor(){
        player.getInventory().setItem(2,new ItemStack(Material.TNT));
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,
                TradeMenu.LEFT_SLOTS[0],ClickType.NUMBER_KEY,InventoryAction.HOTBAR_SWAP,2);
        listener.onClick(event);assertTrue(event.isCancelled());
    }
    @Test void offhandSwapValidatesOffhandItem(){
        player.getInventory().setItemInOffHand(new ItemStack(Material.TNT));
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,
                TradeMenu.LEFT_SLOTS[0],ClickType.SWAP_OFFHAND,InventoryAction.HOTBAR_SWAP);
        listener.onClick(event);assertTrue(event.isCancelled());
    }
    @Test void dragUsingWholeBlockedCursorIsRejected(){
        var event=new InventoryDragEvent(player.getOpenInventory(),new ItemStack(Material.AIR),
                new ItemStack(Material.TNT),false,Map.of(TradeMenu.LEFT_SLOTS[0],new ItemStack(Material.TNT)));
        listener.onDrag(event);assertTrue(event.isCancelled());
    }
    @Test void peekInventoryCannotBeModifiedByDragging(){
        player.openInventory(server.createInventory(new TradeMenu.PeekHolder(session.id),27));
        var event=new InventoryDragEvent(player.getOpenInventory(),new ItemStack(Material.AIR),
                new ItemStack(Material.DIAMOND),false,Map.of(0,new ItemStack(Material.DIAMOND)));
        listener.onDrag(event);assertTrue(event.isCancelled());
    }
    @Test @SuppressWarnings("unchecked") void previewTransitionDoesNotCancelActiveTrade() throws Exception {
        var field=TradeListener.class.getDeclaredField("activePeeks");field.setAccessible(true);
        ((Map<UUID,UUID>)field.get(listener)).put(player.getUniqueId(),session.id);
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));
        assertFalse(session.finishing);
        assertSame(session,manager.session(player.getUniqueId()));
    }
}
