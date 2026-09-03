package me.hcfcore.core.kit;

import me.hcfcore.core.lang.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class KitMenuListener implements Listener {

    private final Plugin plugin;
    private final KitManager kitManager;
    private final Messages messages;

    public KitMenuListener(Plugin plugin, KitManager kitManager, Messages messages) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof KitPreviewMenu.Holder
                || event.getInventory().getHolder() instanceof KitsMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof KitPreviewMenu.Holder) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getInventory().getHolder() instanceof KitsMenu.Holder)) {
            return;
        }
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        NamespacedKey key = new NamespacedKey(plugin, KitsMenu.KIT_ID_KEY);
        String kitId = clicked.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (kitId == null) {
            return;
        }
        Kit kit = kitManager.get(kitId);
        if (kit == null) {
            return;
        }

        if (event.getClick() == ClickType.RIGHT) {
            KitPreviewMenu.open(player, kit, messages);
        } else if (event.getClick() == ClickType.LEFT) {
            player.closeInventory();
            kitManager.apply(player, kit);
        }
    }
}
