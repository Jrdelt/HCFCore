package me.vertex.core.chunkbuster;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The confirmation GUI every Chunk Buster use requires, built entirely
 * from {@code gui/chunkbuster.yml} via {@link MenuLayout} -- see {@code
 * BaseClaimMenu} for the same "layout.createInventory / layout.place"
 * usage shape.
 *
 * <p>Green confirm / red cancel / closing the inventory without clicking
 * either all leave the world untouched -- {@link ChunkBusterMenuListener}
 * enforces that, and only "confirm" triggers {@link
 * ChunkBusterManager#confirmAndExecute} (which fully revalidates before
 * doing anything, since the world may have changed while this menu sat
 * open).
 *
 * <p>When {@code hasSpawners} is true, a distinct, more noticeable
 * variant is shown instead: a different GUI title ({@code gui/
 * chunkbuster.yml}'s {@code titles.spawner-warning}), an extra warning
 * banner item, and a differently-materialed/labeled confirm button --
 * all config-driven, no changes to the menu framework itself.
 */
public final class ChunkBusterMenu {

    public static final String MENU_ID = "chunkbuster";

    private ChunkBusterMenu() {
    }

    public static void open(Player viewer, ChunkBusterManager manager, Messages messages, MenuRegistry menus,
            ChunkBusterType type, Location target, boolean hasSpawners) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder(type, target.clone(), hasSpawners);

        MenuPlaceholders placeholders = MenuPlaceholders.of()
                // Type names come from admin-controlled chunkbuster.yml and
                // may intentionally contain MiniMessage tags such as <red>.
                // A normal placeholder escapes those tags and showed them as
                // raw lore text, so this one must be rendered as trusted text.
                .putTrusted("type", manager.displayName(type))
                .put("world", target.getWorld().getName())
                .put("x", target.getBlockX())
                .put("y", target.getBlockY())
                .put("z", target.getBlockZ());

        String titleVariant = hasSpawners ? "spawner-warning" : null;
        Inventory inventory = layout.createInventory(holder, titleVariant, placeholders);
        holder.inventory = inventory;

        layout.place(inventory, "info", placeholders);
        if (hasSpawners) {
            layout.place(inventory, "spawner-warning-banner", placeholders);
            layout.place(inventory, "confirm-spawner-warning", placeholders);
        } else {
            layout.place(inventory, "confirm", placeholders);
        }
        layout.place(inventory, "cancel", placeholders);
        viewer.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        private final ChunkBusterType type;
        private final Location target;
        private final boolean hasSpawners;
        private Inventory inventory;

        Holder(ChunkBusterType type, Location target, boolean hasSpawners) {
            this.type = type;
            this.target = target;
            this.hasSpawners = hasSpawners;
        }

        public ChunkBusterType type() {
            return type;
        }

        public Location target() {
            return target;
        }

        public boolean hasSpawners() {
            return hasSpawners;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
