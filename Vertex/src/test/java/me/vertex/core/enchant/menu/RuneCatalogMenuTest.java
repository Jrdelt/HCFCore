package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneCatalogMenuTest {
    private PlayerMock player;
    private EnchantManager manager;
    private Messages messages;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        messages = new Messages(plugin, new UserManager(plugin, null));
        messages.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void catalogGroupsAllRollableLevelsIntoOneEntryPerEnchant() {
        RuneCatalogMenu.open(player, manager, messages, RuneTier.SIMPLE);

        List<ItemStack> entries = Arrays.stream(player.getOpenInventory().getTopInventory().getContents())
                .filter(item -> item != null && manager.isEnchantItem(item))
                .toList();
        assertEquals(4, entries.size(), "Simple has four configured enchant types, not one icon per level");
        assertTrue(entries.stream().allMatch(item -> item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(line -> line.contains("III"))));
        assertFalse(entries.stream().anyMatch(item -> item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(line -> line.contains("Arena Runes"))));
    }

    @Test
    void catalogLoreShowsAPerLevelProcChanceAndValueBreakdownButNoSuccessFailOdds() {
        RuneCatalogMenu.open(player, manager, messages, RuneTier.LEGENDARY);

        List<ItemStack> entries = Arrays.stream(player.getOpenInventory().getTopInventory().getContents())
                .filter(item -> item != null && manager.isEnchantItem(item))
                .toList();
        assertTrue(entries.stream().allMatch(item -> item.getItemMeta().lore().size() >= 5),
                "description + one line per level (Legendary is 3 levels) + applies-to, at minimum");
        assertTrue(entries.stream().allMatch(item -> item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(line -> line.contains("ᴘʀᴏᴄ"))),
                "each level's real proc chance must be shown, not just a min-max range");
        assertFalse(entries.stream().anyMatch(item -> item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(line -> line.contains("Success") || line.contains("Fail"))),
                "success/fail odds must only ever appear on the identified item itself");
    }

    @Test
    void catalogSupportsSeasonalTier() {
        RuneCatalogMenu.open(player, manager, messages, RuneTier.SEASONAL);

        List<ItemStack> entries = Arrays.stream(player.getOpenInventory().getTopInventory().getContents())
                .filter(item -> item != null && manager.isEnchantItem(item))
                .toList();
        assertEquals(11, entries.size(), "Seasonal has ten Fallen Set gear types plus Ghost (Halloween)");
    }
}
