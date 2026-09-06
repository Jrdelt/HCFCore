package me.vertex.core.blueprint;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Handles active-build controls and the pre-build activation confirmation. */
public final class BlueprintMenuListener implements Listener {

    private final BlueprintManager manager;
    private final BlueprintListener blueprintListener;
    private final Messages messages;

    public BlueprintMenuListener(BlueprintManager manager, BlueprintListener blueprintListener, Messages messages) {
        this.manager = manager;
        this.blueprintListener = blueprintListener;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BlueprintMenu.Holder
                || event.getInventory().getHolder() instanceof BlueprintActivationMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();
        if (rawHolder instanceof BlueprintActivationMenu.Holder activationHolder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null
                    && event.getClickedInventory().getHolder() instanceof BlueprintActivationMenu.Holder
                    && event.getWhoClicked() instanceof Player player
                    && event.getRawSlot() == BlueprintActivationMenu.ENABLE_SLOT) {
                blueprintListener.activate(player, activationHolder.anchor(), activationHolder.templateName());
                player.closeInventory();
            }
            return;
        }
        if (!(rawHolder instanceof BlueprintMenu.Holder holder)) {
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
