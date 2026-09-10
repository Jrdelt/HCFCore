package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
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

/** Handles purchase clicks in {@link RuneShopMenu} (the {@code /shop} Rune browser). */
public final class RuneShopMenuListener implements Listener {

    private final EnchantManager manager;
    private final ShopManager shopManager;
    private final SpawnerManager spawnerManager;
    private final Messages messages;

    public RuneShopMenuListener(EnchantManager manager, ShopManager shopManager, SpawnerManager spawnerManager,
            Messages messages) {
        this.manager = manager;
        this.shopManager = shopManager;
        this.spawnerManager = spawnerManager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RuneShopMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof RuneShopMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getSlot() == RuneShopMenu.SLOT_BACK) {
            if (shopManager != null) {
                ShopMenu.openCategory(player, shopManager, spawnerManager, messages, ShopMenu.RUNES_HOST_CATEGORY, 0);
            }
            return;
        }
        RuneTier tier = holder.tierAt(event.getSlot());
        if (tier == null) {
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "rune.no-economy"));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        double price = manager.runeShopPrice(tier);
        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "rune.cannot-afford", "amount", EconomyHook.format(price)));
            return;
        }
        ItemStack item = manager.createRune(tier);
        for (ItemStack dropped : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.sendMessage(messages.get(player, "rune.purchased", "tier", displayTier(tier),
                "amount", EconomyHook.format(price)));
    }

    private static String displayTier(RuneTier tier) {
        String name = tier.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
