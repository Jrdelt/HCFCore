package me.vertex.core.chunkbuster;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.shop.ShopManager;
import me.vertex.core.shop.ShopMenu;
import me.vertex.core.spawner.SpawnerManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Handles clicks in both {@link ChunkBusterMenu} (confirmation) and
 * {@link ChunkBusterShopMenu} (the {@code /shop} purchase browser) --
 * mirrors {@code SpawnerMenuListener} routing both {@code SpawnerShopMenu}
 * and {@code SpawnerManagementMenu} clicks from one listener.
 */
public final class ChunkBusterMenuListener implements Listener {

    private final ChunkBusterManager manager;
    private final ShopManager shopManager;
    private final SpawnerManager spawnerManager;
    private final Messages messages;
    private final MenuRegistry menus;

    public ChunkBusterMenuListener(ChunkBusterManager manager, ShopManager shopManager, SpawnerManager spawnerManager,
            Messages messages, MenuRegistry menus) {
        this.manager = manager;
        this.shopManager = shopManager;
        this.spawnerManager = spawnerManager;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ChunkBusterMenu.Holder
                || event.getInventory().getHolder() instanceof ChunkBusterShopMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ChunkBusterMenu.Holder holder) {
            onConfirmClick(event, holder);
        } else if (event.getView().getTopInventory().getHolder() instanceof ChunkBusterShopMenu.Holder holder) {
            onShopClick(event, holder);
        }
    }

    private void onConfirmClick(InventoryClickEvent event, ChunkBusterMenu.Holder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
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

    private void onShopClick(InventoryClickEvent event, ChunkBusterShopMenu.Holder holder) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof ChunkBusterShopMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getSlot() == ChunkBusterShopMenu.SLOT_BACK) {
            if (shopManager != null) {
                ShopMenu.openCategory(player, shopManager, spawnerManager, messages,
                        ShopMenu.CHUNK_BUSTERS_HOST_CATEGORY, 0);
            }
            return;
        }
        ChunkBusterType type = holder.typeAt(event.getSlot());
        if (type == null) {
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "chunkbuster.no-economy"));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        double price = manager.price(type);
        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "chunkbuster.cannot-afford", "amount", EconomyHook.format(price)));
            return;
        }
        ItemStack item = manager.createItem(type);
        for (ItemStack dropped : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.sendMessage(messages.get(player, "chunkbuster.purchased", "type", manager.displayName(type),
                "amount", EconomyHook.format(price)));
    }
}
