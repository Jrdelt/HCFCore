package me.vertex.core.gc;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.util.Numbers;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * The player-facing GC wallet: balance display plus Withdraw-code/Redeem/
 * Logs buttons. Copies {@code FactionBankMenu}'s exact shape --
 * PDC-tagged buttons read back in {@link #onClick}, the whole inventory
 * reopened after a change -- with its layout driven by {@code gui/gc.yml}
 * through {@link MenuRegistry}, the same config-driven shape {@code
 * gui/events.yml} already uses.
 *
 * <p>GC never converts to or from Vault money here. Withdrawal creates a
 * single-use redeem code through {@code /gc withdraw <amount>}; the menu is
 * deliberately a clear command reference, not an unreliable text-entry UI.
 */
public final class GcMenu implements Listener {

    public static final String MENU_ID = "gc";

    private final Plugin plugin;
    private final GcManager manager;
    private final Messages messages;
    private final MenuRegistry menuRegistry;
    private final NamespacedKey actionKey;

    public GcMenu(Plugin plugin, GcManager manager, Messages messages, MenuRegistry menuRegistry) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.menuRegistry = menuRegistry;
        this.actionKey = new NamespacedKey(plugin, "gc_menu_action");
    }

    public void open(Player player) {
        MenuLayout layout = menuRegistry.layout(MENU_ID);
        MenuPlaceholders placeholders = MenuPlaceholders.of()
                .put("balance", Numbers.formatFull(manager.balance(player.getUniqueId())));
        Holder holder = new Holder();
        Inventory inventory = layout.createInventory(holder, placeholders);
        holder.setInventory(inventory);
        layout.place(inventory, "balance", placeholders);
        placeAction(inventory, layout, placeholders, "withdraw");
        placeAction(inventory, layout, placeholders, "redeem");
        placeAction(inventory, layout, placeholders, "logs");
        player.openInventory(inventory);
    }

    private void placeAction(Inventory inventory, MenuLayout layout, MenuPlaceholders placeholders, String id) {
        MenuItemTemplate template = layout.item(id);
        if (template == null) {
            return;
        }
        ItemStack item = template.render(placeholders);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        for (int slot : template.slots()) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, item);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        switch (action) {
            case "withdraw" -> {
                player.closeInventory();
                player.sendMessage(messages.getGui(player, "gc.withdraw-hint"));
            }
            case "redeem" -> {
                player.closeInventory();
                player.sendMessage(messages.getGui(player, "gc.redeem-hint"));
            }
            case "logs" -> GcLogMenu.open(player, manager, messages, 0);
            default -> { }
        }
    }


    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
