package me.vertex.core.staff;

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
 * synced back to the target after every click ({@link InvseeMenuListener}).
 * Unlike the naive version of this (blindly push all 41 tracked slots to
 * the target on every sync), {@link #writeBack} only pushes the specific
 * slots that actually changed *in the menu* since the last sync -- any
 * slot the staff member didn't touch is left alone, re-read fresh from the
 * target's own current live value instead of overwritten with a stale
 * snapshot. Without this, any independent change to the target's gear
 * (they pick something up, are given an item, etc.) made while the menu is
 * open gets silently destroyed by the next click's writeback, even one
 * that only touched the staff member's own inventory pane.
 */
public final class InvseeMenu {

    static final int SIZE = 45;
    static final int SLOT_HELMET = 36;
    static final int SLOT_CHESTPLATE = 37;
    static final int SLOT_LEGGINGS = 38;
    static final int SLOT_BOOTS = 39;
    static final int SLOT_OFFHAND = 40;
    private static final int FILLER_START = 41;
    /** Number of slots writeBack/populate actually track (storage + armor + offhand). */
    private static final int TRACKED_SLOTS = SLOT_OFFHAND + 1;

    private InvseeMenu() {
    }

    public static void open(Player viewer, Player target) {
        Holder holder = new Holder(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text(target.getName() + "'s Inventory", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        populate(inventory, target);
        holder.lastKnownSnapshot = snapshot(inventory);
        for (int slot = FILLER_START; slot < SIZE; slot++) {
            inventory.setItem(slot, filler());
        }
        viewer.openInventory(inventory);
    }

    /** Copies the target's live gear into the menu -- called on open and to refresh untouched slots on sync. */
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

    /** A copy of the menu's tracked slots (0-40), for diffing against on the next sync. */
    static ItemStack[] snapshot(Inventory inventory) {
        ItemStack[] snapshot = new ItemStack[TRACKED_SLOTS];
        for (int slot = 0; slot < TRACKED_SLOTS; slot++) {
            snapshot[slot] = inventory.getItem(slot);
        }
        return snapshot;
    }

    /**
     * Writes only the menu slots that actually changed since
     * `holder.lastKnownSnapshot` onto the target's live gear -- any
     * untouched slot is instead refreshed in the menu from the target's
     * current live value, so a concurrent change on their end survives.
     * Updates `holder.lastKnownSnapshot` to the result either way.
     */
    static void writeBack(Inventory inventory, Player target, Holder holder) {
        ItemStack[] previous = holder.lastKnownSnapshot;
        ItemStack[] current = snapshot(inventory);
        boolean[] changed = new boolean[TRACKED_SLOTS];
        for (int slot = 0; slot < TRACKED_SLOTS; slot++) {
            changed[slot] = !itemsEqual(previous == null ? null : previous[slot], current[slot]);
        }

        for (int slot = 0; slot < TRACKED_SLOTS; slot++) {
            if (!changed[slot]) {
                // Not touched by this sync -- pull the target's current
                // live value into the menu instead of pushing our stale copy.
                inventory.setItem(slot, liveSlot(target, slot));
            }
        }
        for (int slot = 0; slot < TRACKED_SLOTS; slot++) {
            if (changed[slot]) {
                setLiveSlot(target, slot, current[slot]);
            }
        }
        holder.lastKnownSnapshot = snapshot(inventory);
    }

    private static ItemStack liveSlot(Player target, int slot) {
        if (slot < 36) {
            return target.getInventory().getStorageContents()[slot];
        }
        return switch (slot) {
            case SLOT_HELMET -> target.getInventory().getHelmet();
            case SLOT_CHESTPLATE -> target.getInventory().getChestplate();
            case SLOT_LEGGINGS -> target.getInventory().getLeggings();
            case SLOT_BOOTS -> target.getInventory().getBoots();
            case SLOT_OFFHAND -> target.getInventory().getItemInOffHand();
            default -> null;
        };
    }

    private static void setLiveSlot(Player target, int slot, ItemStack value) {
        if (slot < 36) {
            ItemStack[] storage = target.getInventory().getStorageContents();
            storage[slot] = value;
            target.getInventory().setStorageContents(storage);
            return;
        }
        switch (slot) {
            case SLOT_HELMET -> target.getInventory().setHelmet(value);
            case SLOT_CHESTPLATE -> target.getInventory().setChestplate(value);
            case SLOT_LEGGINGS -> target.getInventory().setLeggings(value);
            case SLOT_BOOTS -> target.getInventory().setBoots(value);
            case SLOT_OFFHAND -> target.getInventory().setItemInOffHand(value);
            default -> { }
        }
    }

    private static boolean itemsEqual(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.getType().isAir();
        boolean bEmpty = b == null || b.getType().isAir();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty;
        }
        return a.isSimilar(b) && a.getAmount() == b.getAmount();
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
        /** The tracked slots as of the last populate/writeBack, for diffing the next sync against. */
        ItemStack[] lastKnownSnapshot;

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
