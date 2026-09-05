package me.hcfcore.core.spawner;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Routes clicks for both SpawnerShopMenu (buy) and SpawnerManagementMenu (withdraw/sell). */
public final class SpawnerMenuListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final Messages messages;

    public SpawnerMenuListener(SpawnerManager spawnerManager, Messages messages) {
        this.spawnerManager = spawnerManager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SpawnerShopMenu.Holder
                || event.getInventory().getHolder() instanceof SpawnerManagementMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof SpawnerShopMenu.Holder shop) {
            onShopClick(event, shop);
        } else if (event.getInventory().getHolder() instanceof SpawnerManagementMenu.Holder management) {
            onManagementClick(event, management);
        }
    }

    private void onShopClick(InventoryClickEvent event, SpawnerShopMenu.Holder holder) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof SpawnerShopMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        EntityType mobType = holder.mobTypeAt(event.getSlot());
        if (mobType == null) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(mobType);
        if (config == null) {
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "spawner.no-economy"));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, config.price());
        if (!response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "spawner.cannot-afford", "amount", EconomyHook.format(config.price())));
            return;
        }
        ItemStack item = SpawnerManager.createSpawnerItem(mobType,
                me.hcfcore.core.lang.MessageFormatter.deserialize(config.displayName()));
        for (ItemStack dropped : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.sendMessage(messages.get(player, "spawner.purchased", "amount", EconomyHook.format(config.price())));
    }

    private void onManagementClick(InventoryClickEvent event, SpawnerManagementMenu.Holder holder) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof SpawnerManagementMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Location location = holder.location();
        SpawnerData data = spawnerManager.get(location);
        if (data == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getSlot();
        if (slot == SpawnerManagementMenu.WITHDRAW_ONE_SLOT) {
            withdraw(player, location, data, 1);
        } else if (slot == SpawnerManagementMenu.WITHDRAW_ALL_SLOT) {
            withdraw(player, location, data, data.stackSize());
        } else if (slot == SpawnerManagementMenu.SELL_ONE_SLOT) {
            sell(player, location, data, 1);
        } else if (slot == SpawnerManagementMenu.SELL_ALL_SLOT) {
            sell(player, location, data, data.stackSize());
        } else {
            return;
        }
        player.closeInventory();
    }

    private void withdraw(Player player, Location location, SpawnerData data, int amount) {
        amount = Math.min(amount, data.stackSize());
        if (amount <= 0) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(data.mobType());
        net.kyori.adventure.text.Component displayName = config != null
                ? me.hcfcore.core.lang.MessageFormatter.deserialize(config.displayName())
                : net.kyori.adventure.text.Component.text(data.mobType().name());

        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < amount; i++) {
            for (ItemStack dropped : inventory.addItem(SpawnerManager.createSpawnerItem(data.mobType(), displayName)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
        }
        spawnerManager.decreaseStack(location, amount);
        player.sendMessage(messages.get(player, "spawner.withdrew", "amount", String.valueOf(amount)));
    }

    private void sell(Player player, Location location, SpawnerData data, int amount) {
        amount = Math.min(amount, data.stackSize());
        if (amount <= 0) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(data.mobType());
        double price = config != null ? config.price() : 0;
        double refund = price * spawnerManager.sellRefundPercent() / 100.0 * amount;

        if (refund > 0) {
            if (!EconomyHook.isAvailable()) {
                player.sendMessage(messages.get(player, "spawner.no-economy"));
                return;
            }
            EconomyHook.getEconomy().depositPlayer(player, refund);
        }
        spawnerManager.decreaseStack(location, amount);
        player.sendMessage(messages.get(player, "spawner.sold", "amount", String.valueOf(amount),
                "refund", EconomyHook.format(refund)));
    }
}
