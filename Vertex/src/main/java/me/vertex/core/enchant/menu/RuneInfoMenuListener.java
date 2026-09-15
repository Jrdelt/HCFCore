package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.InventoryAccess;
import me.vertex.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Handles clicks in {@link RuneInfoMenu}'s home/detail pages. */
public final class RuneInfoMenuListener implements Listener {

    private final EnchantManager manager;
    private final RuneCooldownStore cooldowns;
    private final UserManager users;
    private final Messages messages;

    public RuneInfoMenuListener(EnchantManager manager, RuneCooldownStore cooldowns, UserManager users, Messages messages) {
        this.manager = manager;
        this.cooldowns = cooldowns;
        this.users = users;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RuneInfoMenu.HomeHolder
                || event.getInventory().getHolder() instanceof RuneInfoMenu.DetailHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!InventoryAccess.ready(manager.plugin(), player)) {
            event.setCancelled(true);
            return;
        }
        var top = event.getView().getTopInventory();
        if (top.getHolder() instanceof RuneInfoMenu.HomeHolder home) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) {
                return;
            }
            RuneInfoMenu.Entry entry = home.entryAt(event.getSlot());
            if (entry != null) {
                RuneInfoMenu.openDetail(player, manager, cooldowns, users, messages, home.entries(), entry.enchantId(), entry.level());
            }
            return;
        }
        if (top.getHolder() instanceof RuneInfoMenu.DetailHolder detail) {
            event.setCancelled(true);
            if (event.getClickedInventory() == top && event.getSlot() == 22) {
                RuneInfoMenu.openHome(player, manager, cooldowns, users, messages, detail.home());
            }
        }
    }
}
