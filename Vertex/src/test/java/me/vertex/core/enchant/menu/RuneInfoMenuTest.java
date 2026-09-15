package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /runeinfo must work identically for every rune tier, seasonal included.
 * Seasonal enchants never appear in any roll table (by design -- they're
 * never rollable), which used to make {@code tierGuess} always fall back to
 * SIMPLE for them; that showed as the wrong small-caps rendering instead of
 * Seasonal's normal-case gold gradient.
 */
class RuneInfoMenuTest {
    private PlayerMock player;
    private EnchantManager manager;
    private Messages messages;
    private RuneCooldownStore cooldowns;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        messages = new Messages(plugin, new UserManager(plugin, null));
        messages.load();
        cooldowns = new RuneCooldownStore(plugin, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void runeInfoRendersASeasonalEntryInNormalCaseNotSmallCaps() {
        List<RuneInfoMenu.Entry> entries = List.of(new RuneInfoMenu.Entry("talon_rend", 1));
        RuneInfoMenu.openHome(player, manager, cooldowns, null, messages, entries);

        boolean foundNormalCaseName = Arrays.stream(player.getOpenInventory().getTopInventory().getContents())
                .filter(item -> item != null && manager.isEnchantItem(item))
                .anyMatch(item -> PlainTextComponentSerializer.plainText()
                        .serialize(item.getItemMeta().displayName()).contains("Talon Rend"));
        assertTrue(foundNormalCaseName,
                "a Seasonal entry must render with its normal-case gold-gradient title, not fall back to SIMPLE's small caps");
    }
}
