package me.vertex.core.booster;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** {@link BoostersMenu} is read-only: it navigates, it never grants anything. */
public final class BoostersMenuListener implements Listener {

    private final BoosterService service;
    private final Messages messages;

    public BoostersMenuListener(BoosterService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BoostersMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BoostersMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        // Boosters are live state, so the subject must still be online for
        // the next screen to say anything truthful.
        Player subject = Bukkit.getPlayer(holder.subjectUuid());
        if (subject == null) {
            viewer.closeInventory();
            viewer.sendMessage(messages.get(viewer, "boosters.subject-offline", "player", holder.subjectName()));
            return;
        }

        if (holder.category() != null) {
            if (event.getRawSlot() == 22) {
                BoostersMenu.openOverview(viewer, subject, service, messages);
            }
            return;
        }
        BoosterCategory clicked = holder.categoryAt(event.getRawSlot());
        if (clicked != null) {
            BoostersMenu.openDetail(viewer, subject, service, messages, clicked);
        }
    }
}
