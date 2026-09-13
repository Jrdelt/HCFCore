package me.vertex.core.enchant;

import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class RuneShopMenuListenerTest {
    ServerMock server; PlayerMock player; RuneShopMenuListener listener; EnchantManager manager; Messages messages;
    @BeforeEach void setup(){
        server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();player=server.addPlayer();
        manager=new EnchantManager(plugin,new TrackedItemIds(plugin));manager.load();
        messages=new Messages(plugin,new UserManager(plugin,null));messages.load();listener=new RuneShopMenuListener(manager,messages);
        Economy economy=(Economy)java.lang.reflect.Proxy.newProxyInstance(Economy.class.getClassLoader(),new Class[]{Economy.class},(proxy,method,args)->switch(method.getName()){
            case "getBalance"->1_000_000_000D;
            case "has","isEnabled"->true;
            case "format"->String.valueOf(args[0]);
            default->throw new UnsupportedOperationException(method.getName());
        });
        server.getServicesManager().register(Economy.class,economy,plugin,ServicePriority.Normal);
        RuneShopMenu.open(player,manager,messages);
    }
    @AfterEach void cleanup(){RuneShopMenu.setArenaRunes(null);MockBukkit.unmock();}
    InventoryClickEvent click(int raw,ClickType type,InventoryAction action){
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,raw,type,action);
        listener.onClick(event);return event;
    }
    int productSlot(){
        var holder=(RuneShopMenu.Holder)player.getOpenInventory().getTopInventory().getHolder();
        for(int i=0;i<27;i++)if(holder.productAt(i)==RuneShopMenu.PurchaseProduct.SIMPLE)return i;
        throw new AssertionError("missing simple rune");
    }
    @Test void bottomShiftClickIsCancelledWithoutMovingItems(){
        player.getInventory().setItem(9,new ItemStack(Material.DIAMOND,8));
        assertTrue(click(27,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
        assertEquals(8,player.getInventory().getItem(9).getAmount());
    }
    @Test void bottomCollectIsCancelled(){assertTrue(click(27,ClickType.DOUBLE_CLICK,InventoryAction.COLLECT_TO_CURSOR).isCancelled());}
    @Test void shiftRightOpensMaxConfirmationInsteadOfCatalog(){
        click(productSlot(),ClickType.SHIFT_RIGHT,InventoryAction.MOVE_TO_OTHER_INVENTORY);
        var holder=assertInstanceOf(RuneShopMenu.ConfirmationHolder.class,player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(36*64,holder.quantity());assertEquals(RuneShopMenu.PurchaseProduct.SIMPLE,holder.product());
        assertTrue(click(27,ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
    }
    @Test void ordinaryRightClickStillOpensCatalog(){
        click(productSlot(),ClickType.RIGHT,InventoryAction.PICKUP_HALF);
        assertInstanceOf(RuneCatalogMenu.Holder.class,player.getOpenInventory().getTopInventory().getHolder());
    }
}
