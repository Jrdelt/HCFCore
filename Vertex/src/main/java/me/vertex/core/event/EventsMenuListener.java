package me.vertex.core.event;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.entity.Player;

/** The Events Hub is informational; no item can be moved through it. */
public final class EventsMenuListener implements Listener {

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EventsMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EventsMenu.Holder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                    || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            EventsMenu.ClickAction action = holder.action(event.getRawSlot());
            if (action == null) return;
            player.closeInventory();
            player.performCommand(action == EventsMenu.ClickAction.HAVEN ? "haven" : "riftlands");
        }
    }
}
