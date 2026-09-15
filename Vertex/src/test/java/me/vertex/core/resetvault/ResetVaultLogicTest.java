package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetVaultLogicTest {

    private PluginMock plugin;
    private Messages messages;
    private ResetVaultToken tokenManager;
    private ItemDisplayNameResolver nameResolver;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        UserManager userManager = new UserManager(plugin, null);
        messages = new Messages(plugin, userManager);
        tokenManager = new ResetVaultToken(plugin, messages);
        nameResolver = new ItemDisplayNameResolver();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testTokenCreationAndValidation() {
        ItemStack token = tokenManager.createToken(MockBukkit.getMock().getConsoleSender());
        assertNotNull(token);
        assertEquals(Material.NAME_TAG, token.getType());
        assertTrue(tokenManager.isToken(token));

        // Ordinary or renamed name tag must be rejected
        ItemStack fakeTag = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = fakeTag.getItemMeta();
        meta.displayName(Component.text("+1 Reset Vault Slot"));
        fakeTag.setItemMeta(meta);

        assertFalse(tokenManager.isToken(fakeTag));
    }

    @Test
    void testTitleCaseItemNameResolver() {
        assertEquals("Diamond Sword", ItemDisplayNameResolver.titleCase(Material.DIAMOND_SWORD));
        assertEquals("Golden Apple", ItemDisplayNameResolver.titleCase(Material.GOLDEN_APPLE));
        assertEquals("Bow", ItemDisplayNameResolver.titleCase(Material.BOW));
    }

    @Test
    void testCustomDisplayNameResolution() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Excalibur"));
        item.setItemMeta(meta);

        assertEquals("Excalibur", nameResolver.resolve(item));
    }
}
