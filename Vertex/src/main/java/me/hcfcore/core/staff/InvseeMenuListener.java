package me.hcfcore.core.staff;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Keeps an open {@link InvseeMenu} in sync with the target it's viewing.
 * Not a live shared reference (see that class's doc) -- every edit is
 * written back to the target one tick after the click/drag that made it,
 * so the click has already resolved and the menu's slots reflect the
 * final result rather than an in-progress one.
 */
public final class InvseeMenuListener implements Listener {

    private final Plugin plugin;

    public InvseeMenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof InvseeMenu.Holder holder)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= InvseeMenu.SIZE) {
            // A click in the viewer's own inventory (bottom half) -- still
            // needs a sync, since e.g. shift-click can move an item into
            // the menu from there.
            scheduleSync(event.getInventory(), holder);
            return;
        }
        if (InvseeMenu.isFillerSlot(slot)) {
            event.setCancelled(true);
            return;
        }
        if (InvseeMenu.isArmorSlot(slot)) {
            ItemStack incoming = event.getCursor();
            if (incoming != null && !incoming.getType().isAir() && !InvseeMenu.fitsArmorSlot(incoming.getType(), slot)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleSync(event.getInventory(), holder);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof InvseeMenu.Holder holder) {
            // A drag spanning armor slots could drop mismatched gear into
            // one -- simplest safe answer is to disallow drags that touch
            // the armor row at all rather than validate each covered slot.
            boolean touchesArmor = event.getRawSlots().stream().anyMatch(InvseeMenu::isArmorSlot);
            boolean touchesFiller = event.getRawSlots().stream().anyMatch(InvseeMenu::isFillerSlot);
            if (touchesArmor || touchesFiller) {
                event.setCancelled(true);
                return;
            }
            scheduleSync(event.getInventory(), holder);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof InvseeMenu.Holder holder) {
            sync(event.getInventory(), holder);
        }
    }

    private void scheduleSync(Inventory inventory, InvseeMenu.Holder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> sync(inventory, holder));
    }

    private void sync(Inventory inventory, InvseeMenu.Holder holder) {
        Player target = Bukkit.getPlayer(holder.targetId());
        if (target != null) {
            InvseeMenu.writeBack(inventory, target);
        }
    }
}
