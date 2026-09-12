package me.vertex.core.blueprint;

import eu.decentsoftware.holograms.api.DHAPI;
import me.vertex.core.lang.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class BlueprintActivationMenuListener implements Listener {

    private final BlueprintListener listener;
    private final BlueprintManager manager;
    private final Messages messages;
    private final boolean hologramsAvailable;

    public BlueprintActivationMenuListener(BlueprintListener listener, BlueprintManager manager, Messages messages) {
        this.listener = listener;
        this.manager = manager;
        this.messages = messages;
        this.hologramsAvailable = org.bukkit.Bukkit.getPluginManager().getPlugin("DecentHolograms") != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BlueprintActivationMenu.Holder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof BlueprintActivationMenu.Holder)) {
            return;
        }

        int slot = event.getRawSlot();
        Location anchor = holder.anchor();
        String templateName = holder.templateName();
        BlueprintTemplate template = manager.getTemplate(templateName);

        if (anchor == null || template == null) {
            player.closeInventory();
            return;
        }
        if (!listener.canManage(player, anchor)) {
            player.sendMessage(messages.get(player, "blueprint.not-your-faction"));
            player.closeInventory();
            return;
        }

        // 1. Enable / Start Building
        if (slot == BlueprintActivationMenu.ENABLE_SLOT) {
            player.closeInventory();
            listener.activate(player, anchor, templateName);
            return;
        }

        // 2. Cancel Placement
        if (slot == BlueprintActivationMenu.CANCEL_SLOT) {
            player.closeInventory();
            listener.stopParticlePreview(anchor);

            Block block = anchor.getBlock();
            if (block.getType() == Material.BEACON && block.getState() instanceof org.bukkit.block.Beacon beacon) {
                if (beacon.getPersistentDataContainer().has(listener.templateKey(), PersistentDataType.STRING)) {
                    // Remove preview hologram
                    String previewHolo = listener.previewHologramName(anchor);
                    if (hologramsAvailable) {
                        try {
                            DHAPI.removeHologram(previewHolo);
                        } catch (Exception ignored) {
                        }
                    }

                    // Admit the refund before removing the source block. A
                    // failed WAL and SQL inbox therefore leave the preview
                    // beacon in place for a later retry.
                    ItemStack blueprintItem = listener.createBlueprintItem(template);
                    if (!listener.queueOverflow(
                            player, java.util.List.of(blueprintItem), "blueprint-preview-refund")) {
                        player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
                        return;
                    }
                    block.setType(Material.AIR, false);

                    player.sendMessage(messages.get(player, "blueprint.cancelled"));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BlueprintActivationMenu.Holder) {
            event.setCancelled(true);
        }
    }
}
