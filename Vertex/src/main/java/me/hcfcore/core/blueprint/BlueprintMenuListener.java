package me.hcfcore.core.blueprint;

import me.hcfcore.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Handles the cancel-build click in {@link BlueprintMenu}. */
public final class BlueprintMenuListener implements Listener {

    private final BlueprintManager manager;
    private final Messages messages;

    public BlueprintMenuListener(BlueprintManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BlueprintMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BlueprintMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof BlueprintMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || event.getSlot() != BlueprintMenu.CANCEL_SLOT) {
            return;
        }
        ActiveBuild build = manager.activeBuilds().get(holder.buildId());
        if (build != null) {
            build.cancel();
            player.sendMessage(messages.get(player, "blueprint.cancelled"));
        }
        player.closeInventory();
    }
}
