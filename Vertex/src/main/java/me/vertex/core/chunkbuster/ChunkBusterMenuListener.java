package me.vertex.core.chunkbuster;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Handles the confirmation screen shown before a Chunk Buster clears blocks. */
public final class ChunkBusterMenuListener implements Listener {

    private final ChunkBusterManager manager;
    private final Messages messages;
    private final MenuRegistry menus;

    public ChunkBusterMenuListener(ChunkBusterManager manager, Messages messages, MenuRegistry menus) {
        this.manager = manager;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ChunkBusterMenu.Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ChunkBusterMenu.Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;

        MenuLayout layout = menus.layout(ChunkBusterMenu.MENU_ID);
        String confirmTemplate = holder.hasSpawners() ? "confirm-spawner-warning" : "confirm";
        if (matches(layout, confirmTemplate, event.getSlot())) {
            layout.playSound(player, confirmTemplate);
            player.closeInventory();
            ChunkBusterManager.UseResult result = manager.confirmAndExecute(player, holder.target(), holder.type());
            player.sendMessage(messages.get(player, ChunkBusterListener.messageKeyFor(result)));
        } else if (matches(layout, "cancel", event.getSlot())) {
            layout.playSound(player, "cancel");
            player.closeInventory();
        }
    }

    private static boolean matches(MenuLayout layout, String templateId, int slot) {
        MenuItemTemplate template = layout.item(templateId);
        if (template == null) return false;
        for (int candidate : template.slots()) if (candidate == slot) return true;
        return false;
    }
}
