package me.hcfcore.core.staff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

/**
 * The GUI /invsee opens. A plain {@code openInventory(target.getInventory())}
 * (what this used to be) only shows the target's 36 hotbar/storage slots --
 * there's no vanilla container type for someone else's armor and offhand,
 * so this builds a real 45-slot menu: slots 0-35 mirror storage, 36-39 are
 * the four armor pieces, 40 is offhand, and 41-44 are inert filler so the
 * bottom row doesn't look broken.
 *
 * <p>Not a live shared reference like the old approach -- it's a snapshot
 * synced back to the target after every click ({@link InvseeMenuListener}),
 * so a change the target makes to their own gear while the menu is open
 * won't appear until it's reopened. Trading that for safety: syncing
 * continuously in both directions is how these menus lose items.
 */
public final class InvseeMenu {

    static final int SIZE = 45;
    static final int SLOT_HELMET = 36;
    static final int SLOT_CHESTPLATE = 37;
    static final int SLOT_LEGGINGS = 38;
    static final int SLOT_BOOTS = 39;
    static final int SLOT_OFFHAND = 40;
    private static final int FILLER_START = 41;

    private InvseeMenu() {
    }

    public static void open(Player viewer, Player target) {
        Holder holder = new Holder(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text(target.getName() + "'s Inventory", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        populate(inventory, target);
        for (int slot = FILLER_START; slot < SIZE; slot++) {
            inventory.setItem(slot, filler());
        }
        viewer.openInventory(inventory);
    }

    /** Copies the target's live gear into the menu -- called on open and after every synced edit. */
    static void populate(Inventory inventory, Player target) {
        ItemStack[] storage = target.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            inventory.setItem(i, storage[i]);
        }
        ItemStack[] armor = target.getInventory().getArmorContents(); // [boots, leggings, chestplate, helmet]
        inventory.setItem(SLOT_BOOTS, armor[0]);
        inventory.setItem(SLOT_LEGGINGS, armor[1]);
        inventory.setItem(SLOT_CHESTPLATE, armor[2]);
        inventory.setItem(SLOT_HELMET, armor[3]);
        inventory.setItem(SLOT_OFFHAND, target.getInventory().getItemInOffHand());
    }

    /** Writes the menu's current contents back onto the target's live gear. */
    static void writeBack(Inventory inventory, Player target) {
        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < storage.length; i++) {
            storage[i] = inventory.getItem(i);
        }
        target.getInventory().setStorageContents(storage);
        target.getInventory().setHelmet(inventory.getItem(SLOT_HELMET));
        target.getInventory().setChestplate(inventory.getItem(SLOT_CHESTPLATE));
        target.getInventory().setLeggings(inventory.getItem(SLOT_LEGGINGS));
        target.getInventory().setBoots(inventory.getItem(SLOT_BOOTS));
        target.getInventory().setItemInOffHand(inventory.getItem(SLOT_OFFHAND));
    }

    static boolean isFillerSlot(int slot) {
        return slot >= FILLER_START && slot < SIZE;
    }

    static boolean isArmorSlot(int slot) {
        return slot >= SLOT_HELMET && slot <= SLOT_BOOTS;
    }

    /** Whether `material` actually belongs in the given armor slot -- vanilla's own restriction, replicated here since this GUI isn't a real armor slot. */
    static boolean fitsArmorSlot(Material material, int slot) {
        String name = material.name();
        return switch (slot) {
            case SLOT_HELMET -> name.endsWith("_HELMET") || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                    || material == Material.CARVED_PUMPKIN || material == Material.TURTLE_HELMET;
            case SLOT_CHESTPLATE -> name.endsWith("_CHESTPLATE") || material == Material.ELYTRA;
            case SLOT_LEGGINGS -> name.endsWith("_LEGGINGS");
            case SLOT_BOOTS -> name.endsWith("_BOOTS");
            default -> true;
        };
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final UUID targetId;
        private Inventory inventory;

        Holder(UUID targetId) {
            this.targetId = targetId;
        }

        public UUID targetId() {
            return targetId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
