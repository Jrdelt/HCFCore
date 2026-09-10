package me.vertex.core.claims;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Handles clicks in the Base Claim removal GUI. Confirm actually removes
 * the Base Claim; cancel (or simply closing the inventory, which needs no
 * handler at all) leaves it untouched.
 */
public final class BaseClaimMenuListener implements Listener {

    private final BaseClaimManager manager;
    private final Messages messages;
    private final MenuRegistry menus;

    public BaseClaimMenuListener(BaseClaimManager manager, Messages messages, MenuRegistry menus) {
        this.manager = manager;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BaseClaimMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BaseClaimMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        MenuLayout layout = menus.layout(BaseClaimMenu.MENU_ID);
        if (matches(layout, "confirm-remove", event.getSlot())) {
            layout.playSound(player, "confirm-remove");
            player.closeInventory();
            if (!player.hasPermission("vertex.baseclaim.remove") || !me.vertex.core.factions.FactionsHook.isLeader(player)) {
                player.sendMessage(messages.get(player, "baseclaim.leader-only"));
                return;
            }
            BaseClaimManager.RemoveResult result = manager.removeAnchor(holder.factionId(), holder.slotIndex());
            switch (result) {
                case OK -> player.sendMessage(messages.get(player, "baseclaim.removed"));
                case SHIELDED -> player.sendMessage(messages.get(player, "baseclaim.remove-shielded"));
                case HAS_SPAWNERS -> player.sendMessage(messages.get(player, "baseclaim.remove-has-spawners"));
                case NOT_FOUND -> player.sendMessage(messages.get(player, "baseclaim.not-found"));
            }
        } else if (matches(layout, "cancel-remove", event.getSlot())) {
            layout.playSound(player, "cancel-remove");
            player.closeInventory();
        }
    }

    private boolean matches(MenuLayout layout, String templateId, int slot) {
        MenuItemTemplate template = layout.item(templateId);
        if (template == null) {
            return false;
        }
        for (int candidate : template.slots()) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }
}
