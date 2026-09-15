package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.IncineratorEligibility;
import me.vertex.core.enchant.RunePreferenceManager;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot -- taken once, at open time, never live-refreshed -- of every
 * currently-eligible-and-unprotected standalone rune in the player's
 * hotbar/main inventory, one icon per source slot. GUI items are pure
 * display; every destructive action re-validates against the live
 * inventory and current filters regardless of what this snapshot shows.
 */
public final class IncineratorMenu {

    private static final List<Integer> CONTENT_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35);
    public static final int INCINERATE_ALL_SLOT = 49;
    public static final int FILTERS_SLOT = 53;

    private IncineratorMenu() {
    }

    public static void open(Player player, EnchantManager manager, RunePreferenceManager preferences, Messages messages) {
        List<IncineratorEligibility.Entry> eligible = IncineratorEligibility.unprotectedStandaloneRunes(player, manager, preferences);
        Map<Integer, Integer> guiSlotToInventorySlot = new LinkedHashMap<>();
        Holder holder = new Holder(guiSlotToInventorySlot);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "rune.incinerator-title"));
        holder.inventory = inventory;
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of()));
        }
        for (int index = 0; index < eligible.size() && index < CONTENT_SLOTS.size(); index++) {
            IncineratorEligibility.Entry entry = eligible.get(index);
            int guiSlot = CONTENT_SLOTS.get(index);
            guiSlotToInventorySlot.put(guiSlot, entry.slotIndex());
            inventory.setItem(guiSlot, displayIcon(player, messages, entry.item()));
        }
        inventory.setItem(INCINERATE_ALL_SLOT, button(Material.TNT,
                messages.getGui(player, "rune.incinerator-all"),
                List.of(messages.getGui(player, "rune.incinerator-all-lore"))));
        inventory.setItem(FILTERS_SLOT, button(Material.HOPPER,
                messages.getGui(player, "rune.incinerator-filters"),
                List.of(messages.getGui(player, "rune.incinerator-filters-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack displayIcon(Player player, Messages messages, ItemStack source) {
        ItemStack display = source.clone();
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(messages.getGui(player, "rune.incinerator-click-hint"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, Integer> guiSlotToInventorySlot;
        private Inventory inventory;

        private Holder(Map<Integer, Integer> guiSlotToInventorySlot) {
            this.guiSlotToInventorySlot = guiSlotToInventorySlot;
        }

        /** @return the real inventory slot index a clicked GUI icon represents, or null. */
        public Integer inventorySlotAt(int guiSlot) {
            return guiSlotToInventorySlot.get(guiSlot);
        }

        public List<Integer> allInventorySlots() {
            return List.copyOf(guiSlotToInventorySlot.values());
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
