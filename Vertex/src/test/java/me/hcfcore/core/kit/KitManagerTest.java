package me.hcfcore.core.kit;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitManagerTest {

    private ServerMock server;
    private PluginMock plugin;
    private UserManager userManager;
    private KitManager kitManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        userManager = new UserManager(plugin, new InMemoryStorage());
        kitManager = new KitManager(plugin, new InMemoryStorage(), userManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void saveThenDeletePersistsAcrossAFreshLoad() {
        // Regression test: save()/delete() moved their kits.yml write onto a
        // dedicated IO thread. shutdown() must flush that write before this
        // assertion re-reads the file, or it'll see stale/no data.
        PlayerMock player = server.addPlayer("Alice");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));

        kitManager.save("Starter", player, "hcfcore.kit.starter", 30);
        kitManager.shutdown();

        KitManager reloaded = new KitManager(plugin, new InMemoryStorage(), userManager);
        reloaded.load();
        Kit kit = reloaded.get("starter");
        assertEquals("Starter", kit.getName());
        assertEquals("hcfcore.kit.starter", kit.getPermission());
        assertEquals(30, kit.getCooldownSeconds());

        reloaded.delete("Starter");
        reloaded.shutdown();

        KitManager reloadedAgain = new KitManager(plugin, new InMemoryStorage(), userManager);
        reloadedAgain.load();
        assertNull(reloadedAgain.get("starter"));
    }

    @Test
    void applyRespectsTheKitsOwnPermission() {
        PlayerMock player = server.addPlayer("Bob");
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("VIP", "hcfcore.kit.vip", 0, new ItemStack[0], new ItemStack[0]);

        kitManager.apply(player, kit);

        assertTrue(player.nextMessage().contains("permission"));
    }

    @Test
    void applyEnforcesCooldownUntilItExpiresOrIsBypassed() {
        PlayerMock player = server.addPlayer("Carol");
        player.addAttachment(plugin, "hcfcore.kit.bypasscooldown", false);
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("Grinder", "", 60, new ItemStack[0], new ItemStack[0]);

        kitManager.apply(player, kit);
        player.nextMessage(); // consume "Applied kit ..."

        // Second attempt, still on cooldown.
        kitManager.apply(player, kit);
        assertTrue(player.nextMessage().contains("again in"));

        // A player with the bypass permission skips the cooldown entirely.
        player.addAttachment(plugin, "hcfcore.kit.bypasscooldown", true);
        kitManager.apply(player, kit);
        assertFalse(player.nextMessage().contains("again in"));
    }

    private static final class InMemoryStorage implements Storage {
        private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return cooldowns.getOrDefault(uuid, Map.of());
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
            cooldowns.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>()).put(kitName, availableAt);
        }

        @Override
        public void close() {
        }
    }
}
