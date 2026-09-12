package me.vertex.core.factions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FactionConfigManagerTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void obsoleteTAliasIsRemovedFromInstalledAndLiveConfiguration()throws Exception{
        MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();
        plugin.saveResource("factions.yml",false);var file=FactionConfigManager.file(plugin);
        var installed=YamlConfiguration.loadConfiguration(file);
        installed.set("factions.command-aliases",List.of("f"," T ","factions"));installed.save(file);
        FactionConfigManager.loadAndApply(plugin);
        assertEquals(List.of("f","factions"),plugin.getConfig().getStringList("factions.command-aliases"));
        assertEquals(List.of("f","factions"),YamlConfiguration.loadConfiguration(file).getStringList("factions.command-aliases"));
    }
}
