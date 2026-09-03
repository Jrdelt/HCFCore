package me.hcfcore.core.staff;

import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

public final class InvRestoreMenuListener implements Listener {

    private final Plugin plugin;
    private final DeathManager deathManager;
    private final Messages messages;

    public InvRestoreMenuListener(Plugin plugin, DeathManager deathManager, Messages messages) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof InvRestoreMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof InvRestoreMenu.Holder)) {
            return;
        }
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InvRestoreMenu.Holder holder = (InvRestoreMenu.Holder) event.getInventory().getHolder();
        NamespacedKey deathActionKey = new NamespacedKey(plugin, InvRestoreMenu.DEATH_ACTION_KEY);
        NamespacedKey deathIndexKey = new NamespacedKey(plugin, InvRestoreMenu.DEATH_INDEX_KEY);

        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(deathActionKey, PersistentDataType.STRING);

        if (action == null) {
            return;
        }

        if (action.equals("restore")) {
            Integer index = clicked.getItemMeta().getPersistentDataContainer()
                    .get(deathIndexKey, PersistentDataType.INTEGER);
            if (index != null && index >= 0 && index < holder.deaths.size()) {
                Death death = holder.deaths.get(index);
                if (event.getClick() == ClickType.LEFT) {
                    restoreToInventory(player, death);
                } else if (event.getClick() == ClickType.RIGHT) {
                    InvRestoreMenu.openContentsView(player, death, plugin, holder.targetUUID, messages);
                }
            }
        } else if (action.equals("back")) {
            if (holder.targetUUID != null) {
                InvRestoreMenu.openByUUID(player, holder.targetUUID, plugin, deathManager, messages);
            }
        }
    }

    private void restoreToInventory(Player staffPlayer, Death death) {
        staffPlayer.closeInventory();
        int added = 0;
        int dropped = 0;

        for (ItemStack item : death.getAllItems()) {
            if (item != null && !item.getType().isAir()) {
                java.util.Map<Integer, ItemStack> couldNotAdd = staffPlayer.getInventory().addItem(item);
                if (couldNotAdd.isEmpty()) {
                    added++;
                } else {
                    staffPlayer.getWorld().dropItemNaturally(staffPlayer.getLocation(), item);
                    dropped++;
                }
            }
        }

        staffPlayer.sendMessage(messages.getChat(staffPlayer, "staff.restore-success", "added", String.valueOf(added)));
        if (dropped > 0) {
            staffPlayer.sendMessage(messages.getChat(staffPlayer, "staff.restore-overflow", "dropped", String.valueOf(dropped)));
        }
    }
}
