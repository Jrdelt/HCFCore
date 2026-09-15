package me.vertex.core.resetvault;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for interactions using a '+1 Reset Vault Slot' token.
 * Prevents renaming mobs/vanilla behaviors and consumes the token to grant +1 permanent slot.
 */
public final class ResetVaultTokenListener implements Listener {

    private final ResetVaultManager manager;

    public ResetVaultTokenListener(ResetVaultManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !manager.tokenManager().isToken(item)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        manager.handleTokenConsume(player, item);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (item != null && manager.tokenManager().isToken(item)) {
            // Cancel vanilla name tag naming of entities
            event.setCancelled(true);
            manager.handleTokenConsume(event.getPlayer(), item);
        }
    }
}
