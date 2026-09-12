package me.vertex.core.network;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkDeploymentTest {
    @Test void defaultsToOneLocalServer() {
        assertEquals(new NetworkDeployment(false, "standalone", Database.Dialect.SQLITE),
                NetworkDeployment.read(new YamlConfiguration()));
    }

    @Test void oneServerMayUseDedicatedMysql() {
        var config = new YamlConfiguration(); config.set("storage.type", "mysql");
        assertEquals(new NetworkDeployment(false, "standalone", Database.Dialect.MYSQL), NetworkDeployment.read(config));
    }

    @Test void networkRequiresMysqlInsteadOfSilentlyFallingBack() {
        var config = new YamlConfiguration(); config.set("network.enabled", true); config.set("network.shard-id", "spawn");
        assertThrows(IllegalArgumentException.class, () -> NetworkDeployment.read(config));
        config.set("storage.type", "myslq");
        assertThrows(IllegalArgumentException.class, () -> NetworkDeployment.read(config));
    }

    @Test void networkRequiresExplicitSafeShardIdentity() {
        var config = new YamlConfiguration(); config.set("network.enabled", true); config.set("storage.type", "mysql");
        for (String bad : new String[]{"standalone", "", "bad::id", "has spaces", "x".repeat(65)}) {
            config.set("network.shard-id", bad);
            assertThrows(IllegalArgumentException.class, () -> NetworkDeployment.read(config), bad);
        }
        config.set("network.shard-id", "  Factions-1 ");
        assertEquals("factions-1", NetworkDeployment.read(config).shardId());
    }

    @Test void reloadPinPreservesIdentityButNotUnrelatedSettings() {
        var original = new NetworkDeployment(true, "factions", Database.Dialect.MYSQL);
        var config = new YamlConfiguration(); config.set("network.max-players", 180);
        config.set("network.shard-id", "different"); config.set("storage.type", "local");
        original.apply(config);
        assertEquals(original, NetworkDeployment.read(config));
        assertEquals(180, config.getInt("network.max-players"));
    }
}
