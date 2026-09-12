package me.vertex.core.wand;
import me.vertex.core.collector.*;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class WandCollectorSafetyTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void commitUsesLiveCollectorContentsAndPreservesNewUpgrades(){
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();
        var manager=new ChunkCollectorManager(plugin,null,null);manager.load();
        var block=server.addSimpleWorld("world").getBlockAt(0,64,0);block.setType(Material.SHULKER_BOX);
        var initial=new ChunkCollectorData(0,java.util.UUID.randomUUID(),"Faction");initial.setStored(Material.GUNPOWDER,100);
        manager.writeData(block.getLocation(),initial);
        var view=WandContainer.of(block,manager);assertNotNull(view);
        assertEquals(100,view.contents(item->true).get(Material.GUNPOWDER));
        var changed=new ChunkCollectorData(2,initial.ownerUuid(),"Faction");
        changed.setStored(Material.GUNPOWDER,40);changed.setStored(Material.DIAMOND,7);
        manager.writeData(block.getLocation(),changed);
        assertEquals(40,view.contents(item->true).get(Material.GUNPOWDER),"async completion must re-read live PDC");
        view.remove(Material.GUNPOWDER,10,item->true);view.commit();
        var committed=manager.readData(block.getLocation());
        assertEquals(30,committed.stored(Material.GUNPOWDER));
        assertEquals(7,committed.stored(Material.DIAMOND));assertEquals(2,committed.upgradeTier());
    }
}
