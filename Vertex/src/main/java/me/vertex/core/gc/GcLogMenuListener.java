package me.vertex.core.gc;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** {@link GcLogMenu} is read-only except for the Back button and pagination. */
public final class GcLogMenuListener implements Listener {

    private final GcManager manager;
    private final GcMenu gcMenu;
    private final Messages messages;

    public GcLogMenuListener(GcManager manager, GcMenu gcMenu, Messages messages) {
        this.manager = manager;
        this.gcMenu = gcMenu;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GcLogMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GcLogMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof GcLogMenu.Holder;
        if (!clickedTop) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == GcLogMenu.SLOT_BACK) {
            gcMenu.open(player);
        } else if (slot == GcLogMenu.SLOT_PREV_PAGE) {
            GcLogMenu.open(player, manager, messages, holder.page() - 1);
        } else if (slot == GcLogMenu.SLOT_NEXT_PAGE) {
            GcLogMenu.open(player, manager, messages, holder.page() + 1);
        }
    }
}
