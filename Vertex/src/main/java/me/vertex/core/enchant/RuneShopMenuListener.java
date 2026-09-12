package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Handles purchase clicks in the dedicated {@link RuneShopMenu}. */
public final class RuneShopMenuListener implements Listener {

    private final EnchantManager manager;
    private final Messages messages;

    public RuneShopMenuListener(EnchantManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RuneShopMenu.Holder
                || event.getInventory().getHolder() instanceof RuneShopMenu.ConfirmationHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory().getHolder() instanceof RuneShopMenu.ConfirmationHolder confirmation) {
            event.setCancelled(true);
            handleConfirmation(event, player, confirmation);
            return;
        }
        if (!(event.getClickedInventory().getHolder() instanceof RuneShopMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        RuneShopMenu.PurchaseProduct product = holder.productAt(event.getSlot());
        if (product == null) {
            return;
        }
        if (event.isShiftClick()) {
            openBulkConfirmation(player, product, event.isRightClick());
            return;
        }
        completePurchase(player, product, 1);
    }

    private void handleConfirmation(InventoryClickEvent event, Player player,
            RuneShopMenu.ConfirmationHolder confirmation) {
        if (event.getSlot() == 15) {
            player.closeInventory();
            return;
        }
        if (event.getSlot() != 11) {
            return;
        }
        completePurchase(player, confirmation.product(), confirmation.quantity());
    }

    private void openBulkConfirmation(Player player, RuneShopMenu.PurchaseProduct product, boolean buyMaximum) {
        ItemStack prototype = itemFor(product);
        if (prototype == null) {
            player.sendMessage(messages.get(player, "rune.shop-unavailable"));
            return;
        }
        int capacity = inventoryCapacity(player, prototype);
        if (capacity < 1) {
            player.sendMessage(messages.get(player, "rune.inventory-full"));
            return;
        }
        if (!hasCurrencyProvider(product)) {
            player.sendMessage(messages.get(player, product.isArena() ? "arena-runes.no-economy" : "rune.no-economy"));
            return;
        }
        int affordable = affordableQuantity(player, product);
        if (affordable < 1) {
            player.sendMessage(cannotAfford(player, product, 1));
            return;
        }
        int quantity = Math.min(capacity, affordable);
        if (!buyMaximum) {
            quantity = Math.min(64, quantity);
        }
        RuneShopMenu.openConfirmation(player, messages, product, prototype, quantity, priceText(product, quantity));
    }

    private void completePurchase(Player player, RuneShopMenu.PurchaseProduct product, int quantity) {
        if (quantity < 1) {
            return;
        }
        ItemStack prototype = itemFor(product);
        if (prototype == null) {
            player.sendMessage(messages.get(player, "rune.shop-unavailable"));
            return;
        }
        if (inventoryCapacity(player, prototype) < quantity) {
            player.sendMessage(messages.get(player, "rune.inventory-full"));
            return;
        }
        if (!hasCurrencyProvider(product)) {
            player.sendMessage(messages.get(player, product.isArena() ? "arena-runes.no-economy" : "rune.no-economy"));
            return;
        }
        if (affordableQuantity(player, product) < quantity || !charge(player, product, quantity)) {
            player.sendMessage(cannotAfford(player, product, quantity));
            return;
        }
        give(player, prototype, quantity);
        player.closeInventory();
        if (product.isArena()) {
            player.sendMessage(messages.get(player, "arena-runes.purchased-bulk", "amount", String.valueOf(quantity),
                    "item", product == RuneShopMenu.PurchaseProduct.ARENA_RUNE ? "Arena Rune" : "Lucky Gem",
                    "cost", priceText(product, quantity)));
        } else {
            player.sendMessage(messages.get(player, "rune.purchased", "tier", displayTier(product.tier()),
                    "count", String.valueOf(quantity), "amount", priceText(product, quantity)));
        }
    }

    private ItemStack itemFor(RuneShopMenu.PurchaseProduct product) {
        if (!product.isArena()) {
            return manager.createRune(product.tier());
        }
        ArenaRuneManager arena = RuneShopMenu.arenaRunes();
        if (arena == null) {
            return null;
        }
        return product.arenaPurchase() == ArenaRuneManager.Purchase.RUNE ? arena.createRune() : arena.createLuckyGem();
    }

    private boolean hasCurrencyProvider(RuneShopMenu.PurchaseProduct product) {
        if (!product.isArena()) {
            return EconomyHook.isAvailable();
        }
        ArenaRuneManager arena = RuneShopMenu.arenaRunes();
        return arena != null && (!arena.requiresEconomy() || EconomyHook.isAvailable());
    }

    private int affordableQuantity(Player player, RuneShopMenu.PurchaseProduct product) {
        if (product.isArena()) {
            ArenaRuneManager arena = RuneShopMenu.arenaRunes();
            return arena == null ? 0 : arena.affordableQuantity(player, product.arenaPurchase());
        }
        double price = manager.runeShopPrice(product.tier());
        if (price <= 0D) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(EconomyHook.getEconomy().getBalance(player) / price);
    }

    private boolean charge(Player player, RuneShopMenu.PurchaseProduct product, int quantity) {
        if (product.isArena()) {
            ArenaRuneManager arena = RuneShopMenu.arenaRunes();
            return arena != null && arena.charge(player, product.arenaPurchase(), quantity);
        }
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, manager.runeShopPrice(product.tier()) * quantity);
        return response != null && response.transactionSuccess();
    }

    private Component cannotAfford(Player player, RuneShopMenu.PurchaseProduct product, int quantity) {
        return messages.get(player, product.isArena() ? "arena-runes.cannot-afford" : "rune.cannot-afford",
                "amount", priceText(product, quantity));
    }

    private String priceText(RuneShopMenu.PurchaseProduct product, int quantity) {
        if (product.isArena()) {
            ArenaRuneManager arena = RuneShopMenu.arenaRunes();
            return arena == null ? "0" : arena.priceText(product.arenaPurchase(), quantity);
        }
        return EconomyHook.format(manager.runeShopPrice(product.tier()) * quantity);
    }

    private static int inventoryCapacity(Player player, ItemStack prototype) {
        int capacity = 0;
        int maximum = prototype.getMaxStackSize();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                capacity += maximum;
            } else if (existing.isSimilar(prototype)) {
                capacity += Math.max(0, maximum - existing.getAmount());
            }
        }
        return capacity;
    }

    private static void give(Player player, ItemStack prototype, int quantity) {
        int remaining = quantity;
        while (remaining > 0) {
            ItemStack stack = prototype.clone();
            int amount = Math.min(stack.getMaxStackSize(), remaining);
            stack.setAmount(amount);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            remaining -= amount;
        }
    }

    private static String displayTier(RuneTier tier) {
        String name = tier.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
