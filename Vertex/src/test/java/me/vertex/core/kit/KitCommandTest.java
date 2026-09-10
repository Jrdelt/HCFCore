package me.vertex.core.kit;

import me.vertex.core.ability.AbilityManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;

import static me.vertex.core.lang.MessageAssertions.isChatMessage;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitCommandTest {

    private ServerMock server;
    private PluginMock plugin;
    private Messages messages;
    private KitManager kitManager;
    private KitCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        UserManager userManager = new UserManager(plugin, new NoOpStorage());
        messages = new Messages(plugin, userManager);
        messages.load();
        AbilityManager abilityManager = new AbilityManager(plugin, new NoOpStorage());
        abilityManager.load();
        kitManager = new KitManager(plugin, new NoOpStorage(), userManager, messages, abilityManager);
        command = new KitCommand(plugin, kitManager, messages);
    }

    @AfterEach
    void tearDown() {
        if (kitManager != null) {
            kitManager.shutdown();
        }
        MockBukkit.unmock();
    }

    // Regression test: an out-of-range or non-numeric cooldown/cost used to
    // NPE inside a ternary's implicit Integer->int unboxing instead of
    // falling back to the usage message.
    @Test
    void createWithAnOutOfRangeCooldownDoesNotCrashAndShowsUsage() {
        PlayerMock player = server.addPlayer("Alice");
        player.addAttachment(plugin, "vertex.kit.create", true);

        assertDoesNotThrow(() ->
                command.onCommand(player, null, "kit", new String[]{"create", "Test", "", "999999999999"}));

        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.usage-create"));
    }

    @Test
    void createWithANonNumericCooldownDoesNotCrashAndShowsUsage() {
        PlayerMock player = server.addPlayer("Bob");
        player.addAttachment(plugin, "vertex.kit.create", true);

        assertDoesNotThrow(() ->
                command.onCommand(player, null, "kit", new String[]{"create", "Test", "", "notanumber"}));

        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.usage-create"));
    }

    @Test
    void createWithAnInvalidCostItemDoesNotCrashAndShowsUsage() {
        PlayerMock player = server.addPlayer("Carol");
        player.addAttachment(plugin, "vertex.kit.create", true);

        assertDoesNotThrow(() -> command.onCommand(player, null, "kit",
                new String[]{"create", "Test", "", "0", "0", "DIAMOND:999999999"}));

        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.usage-create"));
    }

    @Test
    void previewOpensReadOnlyKitContentsWithoutClaimingTheKit() {
        PlayerMock player = server.addPlayer("Diana");
        player.addAttachment(plugin, "vertex.kit.create", true);
        player.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND, 3));
        command.onCommand(player, null, "kit", new String[]{"create", "Scout"});
        player.getInventory().clear();

        command.onCommand(player, null, "kit", new String[]{"preview", "Scout"});

        assertTrue(player.getOpenInventory().getTopInventory().getHolder() instanceof KitPreviewMenu.Holder);
        assertTrue(player.getOpenInventory().getTopInventory().contains(Material.DIAMOND));
        assertTrue(player.getInventory().isEmpty(), "Previewing must not grant the kit's items.");
    }

    private static final class NoOpStorage implements Storage {
        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }
        @Override
        public void saveDeath(UUID uuid, me.vertex.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.vertex.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
