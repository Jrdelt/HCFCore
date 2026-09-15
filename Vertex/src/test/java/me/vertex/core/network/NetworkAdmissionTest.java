package me.vertex.core.network;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class NetworkAdmissionTest {
    @TempDir Path directory;
    Database database;
    @AfterEach void cleanup(){if(database!=null)database.close();MockBukkit.unmock();}
    @Test void joiningInventoryIsFrozenBeforeAsyncLookupCompletes() throws Exception {
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        database=new Database(new YamlConfiguration(),directory.toFile());
        var network=new NetworkManager(plugin,database,null,null);network.init();
        // Exercise admission against an isolated SQLite fixture; deployment validation remains unchanged.
        var enabled=NetworkManager.class.getDeclaredField("enabled");enabled.setAccessible(true);enabled.set(network,true);
        player.openInventory(server.createInventory(null,27));
        network.onJoin(new PlayerJoinEvent(player,net.kyori.adventure.text.Component.empty()));
        assertFalse(network.inventoryReady(player));
        var e=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,0,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        network.onFrozenInventoryClick(e);assertTrue(e.isCancelled());
        for(int i=0;i<100&&!network.inventoryReady(player);i++){server.getScheduler().performOneTick();Thread.sleep(2);}
        assertTrue(network.inventoryReady(player));enabled.set(network,false);network.shutdown();
    }
}
