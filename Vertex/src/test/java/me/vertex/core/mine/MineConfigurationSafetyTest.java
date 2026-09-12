package me.vertex.core.mine;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import java.io.File;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MineConfigurationSafetyTest {
    ServerMock server;PluginMock plugin;MineManager mines;File file;
    @BeforeEach void setup()throws Exception{
        server=MockBukkit.mock();plugin=MockBukkit.createMockPlugin();
        var messages=new Messages(plugin,new UserManager(plugin,null));messages.load();
        mines=new MineManager(plugin,messages);mines.load();file=new File(plugin.getDataFolder(),"mines.yml");
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void eachMinesHologramSettingsRefreshIndependently()throws Exception{
        var config=YamlConfiguration.loadConfiguration(file);
        config.set("mines.stonewake.koth.hologram.enabled",false);
        config.set("mines.bloodvein.koth.hologram.enabled",true);
        config.set("mines.bloodvein.koth.hologram.lines",List.of("Bloodvein-specific"));config.save(file);mines.load();
        assertFalse(mines.kothHologramsEnabled("stonewake"));assertTrue(mines.kothHologramsEnabled("bloodvein"));
        assertEquals(List.of("Bloodvein-specific"),mines.kothHologramLines("bloodvein"));
        config.set("mines.bloodvein.koth.hologram.lines",List.of("Reloaded"));config.save(file);mines.load();
        assertEquals(List.of("Reloaded"),mines.kothHologramLines("bloodvein"));
    }
    @Test void selectionSaveKeepsOtherModulesAndOwnerEdits()throws Exception{
        var config=YamlConfiguration.loadConfiguration(file);
        String world=mines.region("stonewake").world();var mineWorld=server.addSimpleWorld(world);
        var player=server.addPlayer();assertTrue(mines.beginSelection(player,"stonewake",true));
        config.set("hotzones.audit-marker","preserve-me");config.set("mines.bloodvein.audit-marker","owner-edit");config.save(file);
        mines.setCorner(player,new Location(mineWorld,1,64,1),true);mines.setCorner(player,new Location(mineWorld,3,66,3),false);
        mines.completeSelection(player);var saved=YamlConfiguration.loadConfiguration(file);
        assertEquals("preserve-me",saved.getString("hotzones.audit-marker"));
        assertEquals("owner-edit",saved.getString("mines.bloodvein.audit-marker"));
        assertEquals(1,saved.getInt("mines.stonewake.koth.minimum.x"));
    }
    @Test void kothCannotBeSelectedInAnotherWorld()throws Exception{
        var other=server.addSimpleWorld("wrong-world");var player=server.addPlayer();
        String before=YamlConfiguration.loadConfiguration(file).saveToString();
        assertTrue(mines.beginSelection(player,"stonewake",true));
        mines.setCorner(player,new Location(other,1,64,1),true);mines.setCorner(player,new Location(other,3,66,3),false);
        mines.completeSelection(player);
        assertEquals(before,YamlConfiguration.loadConfiguration(file).saveToString());
    }
    @Test void preannouncedMineIsTheOneActuallyStarted()throws Exception{
        var config=YamlConfiguration.loadConfiguration(file);
        for(String id:List.of("stonewake","bloodvein")){
            config.set("mines."+id+".world",id);
            for(String axis:List.of("x","y","z")){
                config.set("mines."+id+".minimum."+axis,1);
                config.set("mines."+id+".maximum."+axis,5);
            }
        }
        config.save(file);mines.load();
        var database=new me.vertex.core.storage.Database(new YamlConfiguration(),plugin.getDataFolder());
        var storage=new HotZoneStorage(database);storage.init();
        var messages=new Messages(plugin,new UserManager(plugin,null));messages.load();
        var hotzones=new HotZoneManager(plugin,mines,storage,messages);hotzones.load();
        try{
            var peek=HotZoneManager.class.getDeclaredMethod("peekNextMine");peek.setAccessible(true);
            String announced=(String)peek.invoke(hotzones);assertNotNull(announced);
            for(int i=0;i<20;i++)assertEquals(announced,peek.invoke(hotzones));
            var start=HotZoneManager.class.getDeclaredMethod("start");start.setAccessible(true);start.invoke(hotzones);
            assertTrue(hotzones.isActive(announced));
        }finally{hotzones.shutdown();database.close();}
    }
}
