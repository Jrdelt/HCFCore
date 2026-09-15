package me.vertex.core.zone;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;
class ZoneConfigSafetyTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void nonFiniteConfigUsesSafeDefaults() {
        MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var y=new YamlConfiguration();
        for(String key:java.util.List.of("routes.speed","mob-spawning.distance-bias","mob-spawning.cluster-radius",
                "loot-pool.default-item-chance","loot-pool.amplification-cap-percent","riftlands-ticket.default-loot-chance",
                "progression.milestones.100","mobs.zombie.spawn-weight","mobs.zombie.health.min","mobs.zombie.health.max","mobs.zombie.damage"))
            y.set(key,Double.NaN);
        var c=ZoneManager.ZoneConfig.read(ZoneType.HAVEN,y,plugin);
        assertTrue(Double.isFinite(c.routeSpeed()));assertTrue(Double.isFinite(c.clusterRadius()));
        assertTrue(Double.isFinite(c.amplificationCap()));assertTrue(Double.isFinite(c.defaultLootChance()));
        assertTrue(Double.isFinite(c.mobs().getFirst().weight));assertEquals(20,c.mobs().getFirst().maxHealth);
        assertEquals(2,c.mobs().getFirst().profiles.getFirst().damage());
    }
    @Test void mobDefinitionAndProfileBoundEvenDirectInvalidInputs(){
        var mob=new ZoneManager.MobDefinition("zombie",org.bukkit.entity.EntityType.ZOMBIE,true,
                Double.POSITIVE_INFINITY,Double.NaN,Double.MAX_VALUE,java.util.List.of());
        assertEquals(0,mob.weight);assertEquals(20,mob.minHealth);assertEquals(1024,mob.maxHealth);
        assertEquals(2,new ZoneManager.MobProfile("test",Double.NaN).damage());
    }
    @Test void corruptPersistedRouteNumbersAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new ZoneRoute("route","region",true,Double.NaN,false,java.util.List.of()));
        assertThrows(IllegalArgumentException.class,()->new ZoneRoute.Waypoint("world",Double.POSITIVE_INFINITY,64,0,0,0));
    }
    @Test void tinyTicketRegionHasNoSafeInteriorWithoutThrowingOrOverflowing(){
        assertFalse(ZoneManager.hasTicketInterior(0,10,8));
        assertTrue(ZoneManager.hasTicketInterior(0,16,8));
        assertFalse(ZoneManager.hasTicketInterior(100,200,Integer.MAX_VALUE));
    }
}
