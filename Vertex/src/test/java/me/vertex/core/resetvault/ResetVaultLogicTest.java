package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        messages.load();
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
        assertEquals("Excalibur", me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(nameResolver.resolveComponent(item))));
    }

    @Test
    void testSpreadHexDisplayNameResolution() {
        String spreadHexName = "&x&d&4&a&f&3&7&lF&x&c&8&a&3&3&6&la&x&b&6&9&2&3&0&lll&x&a&5&8&1&2&b&le&x&9&3&7&0&2&5&ln Axe";
        ItemStack item = new ItemStack(Material.DIAMOND_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(spreadHexName));
        item.setItemMeta(meta);

        Component resolvedComp = nameResolver.resolveComponent(item);
        assertNotNull(resolvedComp);
        // The visible plain text should be clean "Fallen Axe" without raw &x codes
        assertEquals("Fallen Axe", me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(resolvedComp)));

        // Broadcast placeholder with item name should retain styling, not raw &x
        Component broadcast = messages.get(MockBukkit.getMock().getConsoleSender(), "reset-vault.broadcast.deposit",
                "player", "Volos_",
                "rank", "owner",
                "item", spreadHexName);
        String plainBroadcast = me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(broadcast));
        assertTrue(plainBroadcast.contains("Fallen Axe"));
        assertFalse(plainBroadcast.contains("&x"));
        assertFalse(plainBroadcast.contains("&​x"));
    }

    @Test
    void testSectionSpreadHexDisplayNameResolution() {
        String sectionHexName = "§x§d§4§a§f§3§7§lF§x§c§8§a§3§3§6§la§x§b§6§9§2§3§0§lll§x§a§5§8§1§2§b§le§x§9§3§7§0§2§5§ln Axe";
        ItemStack item = new ItemStack(Material.DIAMOND_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(sectionHexName));
        item.setItemMeta(meta);

        Component resolvedComp = nameResolver.resolveComponent(item);
        assertEquals("Fallen Axe", me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(resolvedComp)));
    }

    @Test
    void testHealedEscapedSpreadHex() {
        // Even if zero-width space \u200B was previously inserted between & and code characters
        String escapedSpreadHex = "&​x&​d&​4&​a&​f&​3&​7&​lF&​x&​c&​8&​a&​3&​3&​6&​la&​x&​b&​6&​9&​2&​3&​0&​lll&​x&​a&​5&​8&​1&​2&​b&​le&​x&​9&​3&​7&​0&​2&​5&​ln Axe";
        Component comp = me.vertex.core.lang.MessageFormatter.deserialize(escapedSpreadHex);
        assertEquals("Fallen Axe", me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(comp)));
    }

    @Test
    void testMiniMessageAndLegacyCodes() {
        ItemStack item1 = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta1 = item1.getItemMeta();
        meta1.displayName(Component.text("&cFire Sword"));
        item1.setItemMeta(meta1);
        assertEquals("Fire Sword", me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(nameResolver.resolveComponent(item1))));

        ItemStack item2 = new ItemStack(Material.BOW);
        ItemMeta meta2 = item2.getItemMeta();
        meta2.displayName(Component.text("<gradient:#ff0000:#0000ff>Spectral Bow</gradient>"));
        item2.setItemMeta(meta2);
        assertEquals("Spectral Bow", me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(nameResolver.resolveComponent(item2))));
    }

    @Test
    void testGetWithComponents() {
        Component itemComp = me.vertex.core.lang.MessageFormatter.deserialize("<red><bold>Flaming Edge</bold></red>");
        Component result = messages.getWithComponents(MockBukkit.getMock().getConsoleSender(), "reset-vault.broadcast.deposit",
                java.util.Map.of("item", itemComp),
                "player", "Volos_",
                "rank", "owner");
        String plain = me.vertex.core.lang.MessageFormatter.plain(
                PlainTextComponentSerializer.plainText().serialize(result));
        assertTrue(plain.contains("Volos_"));
        assertTrue(plain.contains("owner"));
        assertTrue(plain.contains("Flaming Edge"));
    }

    @Test
    void testAllowlistAndApprovedPlayers() {
        ResetVaultManager manager = new ResetVaultManager(plugin, null, messages, null, null, null);
        plugin.getConfig().set("reset-vault.approved-players", java.util.List.of("Volos_", "cesardeltorojr"));
        manager.reloadConfig();

        assertTrue(manager.isGiveAllowed("Volos_"));
        assertTrue(manager.isGiveAllowed("volos_"));
        assertTrue(manager.isGiveAllowed("VOLOS_"));
        assertTrue(manager.isGiveAllowed("cesardeltorojr"));
        assertFalse(manager.isGiveAllowed("UnknownPlayer"));

        // Test wildcard
        plugin.getConfig().set("reset-vault.give-allowlist", java.util.List.of("*"));
        manager.reloadConfig();
        assertTrue(manager.isGiveAllowed("RandomStranger"));
    }
}
