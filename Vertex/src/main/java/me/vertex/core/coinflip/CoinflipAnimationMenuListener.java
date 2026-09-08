package me.vertex.core.coinflip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** {@link CoinflipAnimationMenu} is read-only -- nothing placed in it is ever a real item. */
public final class CoinflipAnimationMenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof CoinflipAnimationMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CoinflipAnimationMenu.Holder) {
            event.setCancelled(true);
        }
    }
}
