package me.vertex.core.enchant.menu;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the Seasonal browser (list of sets -> one set's double-chest detail grid) opened from {@link RuneShopMenu}. */
class SeasonalMenuTest {
    private PlayerMock player;
    private EnchantManager manager;
    private BackpackManager backpackManager;
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
        backpackManager = new BackpackManager(plugin, messages);
        backpackManager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void shopMovesEveryTierButSimpleLeftAndAddsASeasonalButtonRightOfArena() {
        RuneShopMenu.open(player, manager, messages);
        var holder = assertInstanceOf(RuneShopMenu.Holder.class, player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(RuneShopMenu.PurchaseProduct.SIMPLE, holder.productAt(10), "Simple must stay at its original slot");
        assertEquals(RuneShopMenu.PurchaseProduct.ELITE, holder.productAt(11));
        assertEquals(RuneShopMenu.PurchaseProduct.RARE, holder.productAt(12));
        assertEquals(RuneShopMenu.PurchaseProduct.LEGENDARY, holder.productAt(13));
        assertEquals(RuneShopMenu.PurchaseProduct.ARENA, holder.productAt(15));
        assertEquals(Material.NETHER_STAR, player.getOpenInventory().getTopInventory()
                .getItem(RuneShopMenu.SEASONAL_SLOT).getType());
    }

    @Test
    void seasonalMenuListsTheSetAndOpensItsDoubleChestDetailGrid() {
        SeasonalMenu.open(player, manager, messages);
        assertInstanceOf(SeasonalMenu.Holder.class, player.getOpenInventory().getTopInventory().getHolder());

        SeasonalSetMenu.open(player, manager, backpackManager, messages);
        var holder = assertInstanceOf(SeasonalSetMenu.Holder.class, player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(54, player.getOpenInventory().getTopInventory().getSize(), "the set detail view must be double-chest sized");

        long borderPanes = Arrays.stream(player.getOpenInventory().getTopInventory().getContents())
                .filter(item -> item != null && item.getType() == Material.BLACK_STAINED_GLASS_PANE)
                .count();
        assertTrue(borderPanes > 0, "the detail grid must be bordered with black stained glass");

        long pieceIcons = Arrays.stream(player.getOpenInventory().getTopInventory().getContents())
                .filter(item -> item != null && manager.isEnchantItem(item))
                .count();
        assertEquals(10, pieceIcons, "all 10 gear-applied seasonal items must appear in the set's detail grid");
    }

    @Test
    void seasonalItemIconsCarryTheirCorrectOriginTier() {
        ItemStack talonRend = manager.createEnchantItem("talon_rend", 1, RuneTier.SEASONAL);
        assertEquals(RuneTier.SEASONAL, manager.enchantItemInfo(talonRend).originTier());
    }

    /**
     * Mirrors exactly what {@code EnchantCommand}'s {@code seasonalset}
     * give-subcommand does: one identified item per registered seasonal id,
     * each with the enchant/level info still intact after an ItemsAdder
     * customModelData override is stamped on top -- confirming the override
     * never disturbs the PDC the rest of the plugin reads the item by.
     */
    @Test
    void oneOfEachSeasonalItemCanCarryACustomModelDataOverrideWithoutLosingItsEnchantData() {
        int overrideModelData = 20001;
        int created = 0;
        for (String id : manager.seasonalIds()) {
            ItemStack item = manager.createEnchantItem(id, 1, RuneTier.SEASONAL);
            var meta = item.getItemMeta();
            meta.setCustomModelData(overrideModelData);
            item.setItemMeta(meta);

            assertEquals(overrideModelData, item.getItemMeta().getCustomModelData());
            var info = manager.enchantItemInfo(item);
            assertEquals(id, info.enchantId(), "the override must not disturb which enchant this item carries");
            assertEquals(1, info.level());
            created++;
        }
        assertEquals(10, created, "4 armor + 3 weapon + 3 farming/mining tools");
    }
}
