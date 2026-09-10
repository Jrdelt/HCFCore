package me.vertex.core.claims;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Base Claim info + removal confirmation, built entirely from {@code
 * gui/baseclaim.yml} via {@link MenuLayout} -- see {@code EventsMenu} for
 * the same "layout.createInventory / layout.place" usage pattern.
 *
 * <p>Green confirm / red cancel / closing the inventory without clicking
 * either both count as cancel -- {@link BaseClaimMenuListener} enforces that.
 */
public final class BaseClaimMenu {

    public static final String MENU_ID = "baseclaim";

    private BaseClaimMenu() {
    }

    public static void open(Player viewer, BaseClaimManager manager, Messages messages, MenuRegistry menus,
            BaseClaimManager.Region region) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder(region.factionId(), region.slotIndex());
        Inventory inventory = layout.createInventory(holder, MenuPlaceholders.of());
        holder.inventory = inventory;

        MenuPlaceholders placeholders = MenuPlaceholders.of()
                .put("faction", FactionsHook.getFactionName(region.factionId()))
                .put("world", region.anchor().world())
                .put("chunk-x", region.anchor().x())
                .put("chunk-z", region.anchor().z())
                .put("chunks", region.members().size());
        layout.place(inventory, "info", placeholders);
        layout.place(inventory, "confirm-remove", placeholders);
        layout.place(inventory, "cancel-remove", placeholders);
        viewer.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        private final int factionId;
        private final int slotIndex;
        private Inventory inventory;

        Holder(int factionId, int slotIndex) {
            this.factionId = factionId;
            this.slotIndex = slotIndex;
        }

        public int factionId() {
            return factionId;
        }

        public int slotIndex() {
            return slotIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
