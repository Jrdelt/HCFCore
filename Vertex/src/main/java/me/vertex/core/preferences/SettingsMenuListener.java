package me.vertex.core.preferences;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Prevents item movement and applies a preference click exactly once. */
public final class SettingsMenuListener implements Listener {
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SettingsMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SettingsMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }
        SettingsMenu.Entry entry = SettingsMenu.entryAt(event.getRawSlot());
        if (entry == null) {
            return;
        }
        boolean enabled = holder.preferences().toggle(player.getUniqueId(), entry.category());
        Component status = holder.messages().get(player, enabled ? "settings.enabled" : "settings.disabled");
        player.sendMessage(holder.messages().get(player, "settings.updated-prefix")
                .append(holder.messages().get(player, entry.messageKey()))
                .append(Component.text(": "))
                .append(status));
        SettingsMenu.open(player, holder.preferences(), holder.messages());
    }
}
