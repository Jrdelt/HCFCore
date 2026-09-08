package me.vertex.core.staff;

import me.vertex.core.lang.Messages;
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
 *
 * <p>That one-tick gap, plus the fact that the target keeps playing while
 * the menu is open, means the menu can be showing an item the target no
 * longer has. Acting on that stale view duplicates items in both
 * directions: the staff member picks up a copy of something already
 * dropped, or writes a stale item back into an inventory that has since
 * moved the real one elsewhere. So a slot is validated against the target's
 * live contents twice -- before the click is allowed to resolve, and again
 * before the write-back applies it.
 */
public final class InvseeMenuListener implements Listener {

    private final Plugin plugin;
    private final Messages messages;

    public InvseeMenuListener(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof InvseeMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= InvseeMenu.SIZE) {
            // A click in the viewer's own inventory (bottom half) -- still
            // needs a sync, since e.g. shift-click can move an item into
            // the menu from there.
            scheduleSync(event.getInventory(), holder, viewer);
            return;
        }
        if (InvseeMenu.isFillerSlot(slot)) {
            event.setCancelled(true);
            return;
        }

        Player target = Bukkit.getPlayer(holder.targetId());
        if (target == null) {
            event.setCancelled(true);
            viewer.closeInventory();
            return;
        }
        if (!InvseeMenu.isSlotFresh(target, holder, slot)) {
            rejectStale(event, holder, viewer, target);
            return;
        }

        if (InvseeMenu.isArmorSlot(slot)) {
            ItemStack incoming = event.getCursor();
            if (incoming != null && !incoming.getType().isAir() && !InvseeMenu.fitsArmorSlot(incoming.getType(), slot)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleSync(event.getInventory(), holder, viewer);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof InvseeMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        // A drag spanning armor slots could drop mismatched gear into
        // one -- simplest safe answer is to disallow drags that touch
        // the armor row at all rather than validate each covered slot.
        boolean touchesArmor = event.getRawSlots().stream().anyMatch(InvseeMenu::isArmorSlot);
        boolean touchesFiller = event.getRawSlots().stream().anyMatch(InvseeMenu::isFillerSlot);
        if (touchesArmor || touchesFiller) {
            event.setCancelled(true);
            return;
        }

        Player target = Bukkit.getPlayer(holder.targetId());
        if (target == null) {
            event.setCancelled(true);
            viewer.closeInventory();
            return;
        }
        // A drag writes several slots at once, so every one it covers has to
        // be current -- one stale slot is enough to duplicate an item.
        boolean anyStale = event.getRawSlots().stream()
                .anyMatch(slot -> !InvseeMenu.isSlotFresh(target, holder, slot));
        if (anyStale) {
            rejectStale(event, holder, viewer, target);
            return;
        }
        scheduleSync(event.getInventory(), holder, viewer);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof InvseeMenu.Holder holder
                && event.getPlayer() instanceof Player viewer) {
            sync(event.getInventory(), holder, viewer);
        }
    }

    private void rejectStale(org.bukkit.event.Cancellable event, InvseeMenu.Holder holder, Player viewer,
            Player target) {
        event.setCancelled(true);
        Inventory inventory = holder.getInventory();
        InvseeMenu.refresh(inventory, target, holder);
        viewer.sendMessage(messages.get(viewer, "staff.invsee-stale", "player", target.getName()));
        plugin.getLogger().info("Refused a stale /invsee edit by " + viewer.getName() + " on " + target.getName()
                + "; the slot had changed since the menu last read it.");
    }

    private void scheduleSync(Inventory inventory, InvseeMenu.Holder holder, Player viewer) {
        Bukkit.getScheduler().runTask(plugin, () -> sync(inventory, holder, viewer));
    }

    private void sync(Inventory inventory, InvseeMenu.Holder holder, Player viewer) {
        Player target = Bukkit.getPlayer(holder.targetId());
        if (target == null) {
            return;
        }
        int conflicts = InvseeMenu.writeBack(inventory, target, holder);
        if (conflicts > 0 && viewer.isOnline()) {
            viewer.sendMessage(messages.get(viewer, "staff.invsee-stale", "player", target.getName()));
            plugin.getLogger().info("Discarded " + conflicts + " stale /invsee slot edit(s) by " + viewer.getName()
                    + " on " + target.getName() + "; those slots had changed since the menu last read them.");
        }
    }
}
