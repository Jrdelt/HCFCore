package me.vertex.core.spawner;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import static org.junit.jupiter.api.Assertions.*;
class MobStackListenerTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void withinRadiusMobsTwoCellsApartAreMerged() {
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var world=server.addSimpleWorld("farm");
        var manager=new SpawnerManager(plugin,null);manager.load();double radius=manager.mergeRadiusBlocks();
        Zombie a=world.spawn(new Location(world,radius*.49,64,0),Zombie.class);
        Zombie b=world.spawn(new Location(world,radius*1.01,64,0),Zombie.class);
        var key=new NamespacedKey(plugin,"mob_stack_count");
        a.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,1);
        b.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,1);
        new MobStackListener(plugin,manager).consolidateStacks();
        assertEquals(1,world.getEntitiesByClass(Zombie.class).size());
        assertEquals(2,world.getEntitiesByClass(Zombie.class).iterator().next().getPersistentDataContainer().get(key,PersistentDataType.INTEGER));
    }
    @Test void nearbyCellsDoNotMergeMobsOutsideRadius() {
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var world=server.addSimpleWorld("farm");
        var manager=new SpawnerManager(plugin,null);manager.load();double radius=manager.mergeRadiusBlocks();
        Zombie a=world.spawn(new Location(world,0,64,0),Zombie.class),b=world.spawn(new Location(world,radius*1.2,64,0),Zombie.class);
        var key=new NamespacedKey(plugin,"mob_stack_count");a.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,1);b.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,1);
        new MobStackListener(plugin,manager).consolidateStacks();assertEquals(2,world.getEntitiesByClass(Zombie.class).size());
    }
}
