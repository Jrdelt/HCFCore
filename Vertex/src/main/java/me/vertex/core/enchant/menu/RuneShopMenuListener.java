package me.vertex.core.enchant.menu;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RunePreferenceManager;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.InventoryAccess;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

/** Handles purchase clicks in the dedicated {@link RuneShopMenu}. */
public final class RuneShopMenuListener implements Listener {

    private final EnchantManager manager;
    private final RunePreferenceManager preferences;
    private final BackpackManager backpackManager;
    private final Messages messages;

    public RuneShopMenuListener(EnchantManager manager, RunePreferenceManager preferences,
            BackpackManager backpackManager, Messages messages) {
        this.manager = manager;
        this.preferences = preferences;
        this.backpackManager = backpackManager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RuneShopMenu.Holder
                || event.getInventory().getHolder() instanceof RuneShopMenu.ConfirmationHolder
                || event.getInventory().getHolder() instanceof RuneCatalogMenu.Holder
                || event.getInventory().getHolder() instanceof SeasonalMenu.Holder
                || event.getInventory().getHolder() instanceof SeasonalSetMenu.Holder) {
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
        if (top.getHolder() instanceof RuneShopMenu.Holder
                || top.getHolder() instanceof RuneShopMenu.ConfirmationHolder
                || top.getHolder() instanceof RuneCatalogMenu.Holder
                || top.getHolder() instanceof SeasonalMenu.Holder
                || top.getHolder() instanceof SeasonalSetMenu.Holder) {
            // Menus own the whole view; bottom shift/collect/swap actions can mutate the top.
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
        } else return;
        if (event.getInventory().getHolder() instanceof RuneCatalogMenu.Holder) {
            event.setCancelled(true);
            if (event.getClickedInventory().getHolder() instanceof RuneCatalogMenu.Holder) {
                RuneTier tier = RuneCatalogMenu.tierAt(event.getSlot());
                if (tier != null) {
                    RuneCatalogMenu.open(player, manager, messages, tier);
                } else if (event.getSlot() == 49) {
                    RuneShopMenu.open(player, manager, messages);
                }
            }
            return;
        }
        if (event.getInventory().getHolder() instanceof SeasonalMenu.Holder) {
            event.setCancelled(true);
            if (event.getSlot() == SeasonalMenu.BACK_SLOT) {
                RuneShopMenu.open(player, manager, messages);
            } else if (SeasonalMenu.isSetSlot(event.getSlot())) {
                SeasonalSetMenu.open(player, manager, backpackManager, messages);
            }
            return;
        }
        if (event.getInventory().getHolder() instanceof SeasonalSetMenu.Holder) {
            event.setCancelled(true);
            if (event.getSlot() == SeasonalSetMenu.BACK_SLOT) {
                SeasonalMenu.open(player, manager, messages);
            }
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
        if (event.getSlot() == RuneShopMenu.INCINERATOR_SLOT) {
            IncineratorMenu.open(player, manager, preferences, messages);
            return;
        }
        if (event.getSlot() == RuneShopMenu.SEASONAL_SLOT) {
            SeasonalMenu.open(player, manager, messages);
            return;
        }
        RuneShopMenu.PurchaseProduct product = holder.productAt(event.getSlot());
        if (product == null) {
            return;
        }
        if (event.isRightClick() && !event.isShiftClick() && !product.isLuckyGem()) {
            RuneCatalogMenu.open(player, manager, messages, product.tier());
            return;
        }
        if (event.isShiftClick()) {
            openBulkConfirmation(player, product, event.isRightClick());
            return;
        }
        completePurchase(player, product, 1, false);
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
        completePurchase(player, confirmation.product(), confirmation.quantity(), true);
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
            player.sendMessage(messages.get(player, "rune.no-economy"));
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

    private void completePurchase(Player player, RuneShopMenu.PurchaseProduct product, int quantity,
            boolean closeAfterSuccess) {
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
            player.sendMessage(messages.get(player, "rune.no-economy"));
            return;
        }
        if (affordableQuantity(player, product) < quantity || !charge(player, product, quantity)) {
            player.sendMessage(cannotAfford(player, product, quantity));
            return;
        }
        give(player, prototype, quantity);
        if (closeAfterSuccess) {
            player.closeInventory();
        }
        if (product.isLuckyGem()) {
            player.sendMessage(messages.get(player, "rune.purchased-gem", "count", String.valueOf(quantity),
                    "amount", priceText(product, quantity)));
        } else {
            player.sendMessage(messages.get(player, "rune.purchased", "tier", displayTier(product.tier()),
                    "count", String.valueOf(quantity), "amount", priceText(product, quantity)));
        }
    }

    private ItemStack itemFor(RuneShopMenu.PurchaseProduct product) {
        return product.isLuckyGem() ? manager.createLuckyGem() : manager.createRune(product.tier());
    }

    private boolean hasCurrencyProvider(RuneShopMenu.PurchaseProduct product) {
        return currency(product) == EnchantManager.Currency.XP_LEVELS || EconomyHook.isAvailable();
    }

    private EnchantManager.Currency currency(RuneShopMenu.PurchaseProduct product) {
        return product.isLuckyGem() ? EnchantManager.Currency.MONEY : manager.runeShopCurrency(product.tier());
    }

    private double basePrice(RuneShopMenu.PurchaseProduct product) {
        return product.isLuckyGem() ? manager.luckyGemShopPrice() : manager.runeShopPrice(product.tier());
    }

    private int affordableQuantity(Player player, RuneShopMenu.PurchaseProduct product) {
        double price = basePrice(product);
        if (currency(product) == EnchantManager.Currency.XP_LEVELS) {
            int unitCost = (int) Math.ceil(price);
            return unitCost <= 0 ? Integer.MAX_VALUE : player.getLevel() / unitCost;
        }
        if (price <= 0D) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(EconomyHook.getEconomy().getBalance(player) / price);
    }

    private boolean charge(Player player, RuneShopMenu.PurchaseProduct product, int quantity) {
        double price = basePrice(product);
        if (currency(product) == EnchantManager.Currency.XP_LEVELS) {
            long total = (long) Math.ceil(price) * quantity;
            if (total > Integer.MAX_VALUE || player.getLevel() < total) {
                return false;
            }
            player.setLevel((int) (player.getLevel() - total));
            return true;
        }
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, price * quantity);
        return response != null && response.transactionSuccess();
    }

    private Component cannotAfford(Player player, RuneShopMenu.PurchaseProduct product, int quantity) {
        return messages.get(player, "rune.cannot-afford", "amount", priceText(product, quantity));
    }

    private String priceText(RuneShopMenu.PurchaseProduct product, int quantity) {
        double amount = basePrice(product) * quantity;
        return currency(product) == EnchantManager.Currency.XP_LEVELS
                ? ((long) Math.ceil(amount)) + " XP levels"
                : EconomyHook.format(amount);
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
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }
}
