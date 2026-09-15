package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The top-level Seasonal browser, opened from {@link RuneShopMenu}'s
 * Seasonal button. Lists every seasonal set as one icon each -- today that
 * is exactly one (the black-and-gold eagle set) -- in a black-stained-glass
 * bordered 54-slot inventory sized to grow: a future second set is another
 * icon in {@link #CONTENT_SLOTS} away, with no redesign needed.
 *
 * <p>There is deliberately no "set" grouping key in {@code runes.yml} yet:
 * every currently configured seasonal enchant belongs to the one set this
 * menu shows. That grouping only needs to be built once a second set
 * actually exists to distinguish itself from the first.
 */
public final class SeasonalMenu {

    private static final List<Integer> CONTENT_SLOTS = List.of(
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);
    public static final int BACK_SLOT = 49;

    private SeasonalMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "rune.seasonal-title"));
        holder.inventory = inventory;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler());
        }
        if (!manager.seasonalIds().isEmpty()) {
            inventory.setItem(CONTENT_SLOTS.get(0), eagleSetIcon(player, manager, messages));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "rune.seasonal-back"),
                List.of(messages.getGui(player, "rune.seasonal-back-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack eagleSetIcon(Player player, EnchantManager manager, Messages messages) {
        // The Seasonal tier's own configured cosmetic (material/glow/custom
        // model data, from runes.seasonal in runes.yml) is reused as this
        // set's icon rather than a second, separate icon config -- one
        // source of truth for "what a Seasonal item looks like."
        ItemStack item = manager.createRune(RuneTier.SEASONAL);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.getGui(player, "rune.seasonal-set-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isSetSlot(int slot) {
        return CONTENT_SLOTS.indexOf(slot) == 0;
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        private Holder() {
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
