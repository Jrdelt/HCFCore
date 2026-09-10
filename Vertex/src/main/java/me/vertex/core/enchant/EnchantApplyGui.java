package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The GUI section 15 requires for applying a physical enchant item: built
 * from {@code gui/enchant-apply.yml} via {@link MenuLayout} for its static
 * cosmetics (background, info, chance display, confirm/cancel), the same
 * "layout.createInventory / layout.place" shape {@code ChunkBusterMenu}/
 * {@code BaseClaimMenu} use -- plus three open (non-filler) slots the
 * player fills with real items, the same "border + open grid" shape
 * {@code CoinflipWagerMenu} uses for its own player-supplied-items GUI,
 * since {@code MenuLayout} alone has no notion of a slot the player, not
 * the config, controls the contents of.
 *
 * <p>Opening this menu physically moves the triggering enchant item out of
 * the player's hand and into {@link #ENCHANT_SLOT} (see {@code
 * RuneListener#openApplyGui}) rather than merely showing a copy of it --
 * otherwise the item would exist twice the moment {@link
 * EnchantApplyGuiListener}'s close handler returns whatever is left in
 * this menu's slots back to the player.
 */
public final class EnchantApplyGui {

    public static final String MENU_ID = "enchant-apply";

    public static final int TARGET_SLOT = 11;
    public static final int ENCHANT_SLOT = 13;
    public static final int GEM_SLOT = 15;

    private EnchantApplyGui() {
    }

    public static void open(Player viewer, EnchantManager manager, Messages messages, MenuRegistry menus,
            ItemStack triggeringEnchantItem) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder();
        Inventory inventory = layout.createInventory(holder, MenuPlaceholders.of());
        holder.inventory = inventory;

        layout.place(inventory, "info", MenuPlaceholders.of());
        layout.place(inventory, "confirm", MenuPlaceholders.of());
        layout.place(inventory, "cancel", MenuPlaceholders.of());

        inventory.setItem(TARGET_SLOT, null);
        inventory.setItem(ENCHANT_SLOT, triggeringEnchantItem);
        inventory.setItem(GEM_SLOT, null);

        refreshChanceDisplay(inventory, layout, manager);
        viewer.openInventory(inventory);
    }

    /** Recomputes and re-renders the live "current success chance" icon from this menu's current slot contents. */
    static void refreshChanceDisplay(Inventory inventory, MenuLayout layout, EnchantManager manager) {
        ItemStack enchantItem = inventory.getItem(ENCHANT_SLOT);
        int gemCount = countGems(inventory, manager);
        double chance = enchantItem == null ? 0D : manager.effectiveChance(enchantItem, gemCount);
        layout.place(inventory, "chance-display", MenuPlaceholders.of().put("chance", formatChance(chance)));
    }

    private static int countGems(Inventory inventory, EnchantManager manager) {
        ItemStack gems = inventory.getItem(GEM_SLOT);
        return gems != null && manager.isLuckyGem(gems) ? gems.getAmount() : 0;
    }

    private static String formatChance(double chance) {
        if (chance == Math.rint(chance)) {
            return String.valueOf((long) chance);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", chance);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        Holder() {
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
