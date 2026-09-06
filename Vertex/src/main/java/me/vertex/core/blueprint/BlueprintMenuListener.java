package me.vertex.core.blueprint;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Handles active-build controls and completed blueprint repair clicks. */
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
        if (event.getInventory().getHolder() instanceof BlueprintMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();

        if (!(rawHolder instanceof BlueprintMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof BlueprintMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!holder.isCompletedView() && event.getSlot() == BlueprintMenu.CANCEL_SLOT) {
            ActiveBuild build = manager.activeBuilds().get(holder.buildId());
            if (build != null) {
                if (!blueprintListener.canManage(player, build.anchor())) {
                    player.sendMessage(messages.get(player, "blueprint.not-your-faction"));
                    player.closeInventory();
                    return;
                }
                build.cancel();
                player.sendMessage(messages.get(player, "blueprint.cancelled"));
            }
            player.closeInventory();
            return;
        }

        if (holder.isCompletedView() && event.getSlot() == BlueprintMenu.REPAIR_SLOT) {
            if (!blueprintListener.canManage(player, holder.anchor())) {
                player.sendMessage(messages.get(player, "blueprint.not-your-faction"));
                player.closeInventory();
                return;
            }
            if (holder.missingBlocks() == null || holder.missingBlocks().isEmpty()) {
                player.sendMessage(messages.get(player, "blueprint.repair-not-needed"));
                player.closeInventory();
                return;
            }

            blueprintListener.startRepair(player, holder.anchor(), holder.templateName(), holder.missingBlocks());
            player.closeInventory();
        }
    }
}
