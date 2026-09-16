package me.vertex.core.booster;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.enchant.listener.RuneEffectListener;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalBoosterCommandTest {

    private ServerMock server;
    private PluginMock plugin;
    private BoosterService boosterService;
    private RuneEffectListener runeEffectListener;
    private Messages messages;
    private PersonalBoosterCommand xpCommand;
    private PersonalBoosterCommand sellCommand;
    private PlayerMock admin;
    private PlayerMock target;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();

        boosterService = new BoosterService(plugin);
        EnchantManager manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        UserManager users = new UserManager(plugin, null);
        runeEffectListener = new RuneEffectListener(manager, null, users, new RuneCooldownStore(plugin, null));
        messages = new Messages(plugin, users);
        messages.load();

        xpCommand = new PersonalBoosterCommand(runeEffectListener, boosterService, messages, BoosterCategory.EXP);
        sellCommand = new PersonalBoosterCommand(runeEffectListener, boosterService, messages, BoosterCategory.SELL);

        server.getPluginManager().addPermission(new org.bukkit.permissions.Permission("vertex.xpbooster.use", org.bukkit.permissions.PermissionDefault.TRUE));
        server.getPluginManager().addPermission(new org.bukkit.permissions.Permission("vertex.sellbooster.use", org.bukkit.permissions.PermissionDefault.TRUE));

        admin = server.addPlayer("Admin");
        admin.setOp(true);

        target = server.addPlayer("TargetPlayer");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PluginCommand createPluginCommand(String name) throws Exception {
        Constructor<PluginCommand> ctor = PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
        ctor.setAccessible(true);
        return ctor.newInstance(name, plugin);
    }

    @Test
    void testCheckStatusWhenNoBoosterActive() {
        xpCommand.onCommand(target, null, "xpbooster", new String[]{});
        String message = target.nextMessage();
        assertNotNull(message);
        assertTrue(message.contains("do not currently have an active XP Booster"));
    }

    @Test
    void testGiveBoosterExplicitValues() {
        // /xpbooster give TargetPlayer 50 600
        boolean result = xpCommand.onCommand(admin, null, "xpbooster", new String[]{"give", "TargetPlayer", "50", "600"});
        assertTrue(result);

        // Target inventory should contain 1 booster potion
        boolean foundPotion = false;
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null && item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
                foundPotion = true;
                break;
            }
        }
        assertTrue(foundPotion);
    }

    @Test
    void testGiveBoosterRandomValues() {
        // /sellbooster give TargetPlayer
        boolean result = sellCommand.onCommand(admin, null, "sellbooster", new String[]{"give", "TargetPlayer"});
        assertTrue(result);

        boolean foundPotion = false;
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null && item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
                foundPotion = true;
                break;
            }
        }
        assertTrue(foundPotion);
    }

    @Test
    void testGiveNoPermission() {
        // Target doesn't have permission
        target.setOp(false);
        xpCommand.onCommand(target, null, "xpbooster", new String[]{"give", "TargetPlayer", "50", "600"});

        boolean foundPotion = false;
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null) {
                foundPotion = true;
                break;
            }
        }
        assertFalse(foundPotion);
    }

    @Test
    void testTabCompletion() throws Exception {
        PluginCommand cmd = createPluginCommand("xpbooster");
        cmd.setTabCompleter(xpCommand);

        List<String> arg0 = xpCommand.onTabComplete(admin, cmd, "xpbooster", new String[]{""});
        assertTrue(arg0.contains("give"));

        List<String> arg1 = xpCommand.onTabComplete(admin, cmd, "xpbooster", new String[]{"give", ""});
        assertTrue(arg1.contains("TargetPlayer"));
    }
}
