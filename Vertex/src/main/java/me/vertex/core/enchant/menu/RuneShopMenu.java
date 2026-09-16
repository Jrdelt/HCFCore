package me.vertex.core.enchant.menu;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dedicated Rune catalog opened by {@code /runes}, {@code /ce},
 * {@code /customenchants}, or {@code /enchant}. Runes are intentionally not
 * mixed into the ordinary material shop because they open enchant gameplay.
 * Every {@link RuneTier} -- Arena included -- gets one slot here; none of
 * them are special-cased.
 */
public final class RuneShopMenu {

    // Simple stays put; every other original tier shifts one slot left of
    // its old position, freeing slot 16 (immediately right of Arena/"mob
    // runes") for the Seasonal button. The Expanded Rune Module's three new
    // purchasable tiers (Common/Mythic/Cursed) fill the row's remaining gaps
    // (9, 14, 17) rather than extending past Seasonal's button slot.
    //
    // <p>Deliberately an explicit (tier, slot) list instead of indexing
    // straight into {@code RuneTier.values()} -- Seasonal sits between the
    // original five tiers and these three newer ones in that enum's
    // declaration order, and it is never sold here (see {@link #forTier}),
    // so a positional loop over the raw enum would either skip the new
    // tiers or crash trying to sell Seasonal the moment it did reach them.
    private static final List<RuneTier> SHOP_TIERS = List.of(
            RuneTier.SIMPLE, RuneTier.ELITE, RuneTier.RARE, RuneTier.LEGENDARY, RuneTier.ARENA,
            RuneTier.COMMON, RuneTier.MYTHIC, RuneTier.CURSED);
    private static final List<Integer> TIER_SLOTS = List.of(10, 11, 12, 13, 15, 9, 14, 17);
    public static final int SEASONAL_SLOT = 16;
    public static final int LUCKY_GEM_SLOT = 20;
    public static final int INCINERATOR_SLOT = 24;

    public enum PurchaseProduct {
        SIMPLE(RuneTier.SIMPLE),
        ELITE(RuneTier.ELITE),
        RARE(RuneTier.RARE),
        LEGENDARY(RuneTier.LEGENDARY),
        ARENA(RuneTier.ARENA),
        COMMON(RuneTier.COMMON),
        MYTHIC(RuneTier.MYTHIC),
        CURSED(RuneTier.CURSED),
        LUCKY_GEM(null);

        private final RuneTier tier;

        PurchaseProduct(RuneTier tier) {
            this.tier = tier;
        }

        public RuneTier tier() { return tier; }
        public boolean isLuckyGem() { return this == LUCKY_GEM; }

        static PurchaseProduct forTier(RuneTier tier) {
            return switch (tier) {
                case SIMPLE -> SIMPLE;
                case ELITE -> ELITE;
                case RARE -> RARE;
                case LEGENDARY -> LEGENDARY;
                case ARENA -> ARENA;
                case COMMON -> COMMON;
                case MYTHIC -> MYTHIC;
                case CURSED -> CURSED;
                case SEASONAL -> throw new IllegalArgumentException("Seasonal Runes are admin-distributed only and are never sold in the shop");
            };
        }
    }

    private RuneShopMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "rune.shop-title"));
        holder.inventory = inventory;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler());
        }
        for (int index = 0; index < SHOP_TIERS.size() && index < TIER_SLOTS.size(); index++) {
            RuneTier tier = SHOP_TIERS.get(index);
            int slot = TIER_SLOTS.get(index);
            holder.slotToProduct.put(slot, PurchaseProduct.forTier(tier));
            inventory.setItem(slot, buildIcon(player, messages, manager, tier));
        }
        holder.slotToProduct.put(LUCKY_GEM_SLOT, PurchaseProduct.LUCKY_GEM);
        inventory.setItem(LUCKY_GEM_SLOT, buildLuckyGemIcon(player, messages, manager));
        inventory.setItem(SEASONAL_SLOT, button(Material.NETHER_STAR,
                messages.getGui(player, "rune.seasonal-button"),
                List.of(
                        messages.getGui(player, "rune.seasonal-button-lore"),
                        messages.getGui(player, "rune.seasonal-button-hint")
                )));
        inventory.setItem(INCINERATOR_SLOT, button(Material.ANVIL,
                messages.getGui(player, "rune.incinerator-button"),
                List.of(messages.getGui(player, "rune.incinerator-button-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private static ItemStack buildIcon(Player player, Messages messages, EnchantManager manager, RuneTier tier) {
        ItemStack item = manager.createRune(tier);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(messages.getGui(player, "rune.shop-price", "amount", priceText(manager, tier, 1)));
        lore.add(messages.getGui(player, "rune.shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildLuckyGemIcon(Player player, Messages messages, EnchantManager manager) {
        ItemStack item = manager.createLuckyGem();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(messages.getGui(player, "rune.shop-price", "amount", EconomyHook.format(manager.luckyGemShopPrice())));
        lore.add(messages.getGui(player, "rune.gem-shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Formats a tier's price in whichever currency that tier is configured to use. */
    static String priceText(EnchantManager manager, RuneTier tier, int quantity) {
        double amount = manager.runeShopPrice(tier) * quantity;
        return manager.runeShopCurrency(tier) == EnchantManager.Currency.XP_LEVELS
                ? ((long) Math.ceil(amount)) + " XP levels"
                : EconomyHook.format(amount);
    }

    public static void openConfirmation(Player player, Messages messages, PurchaseProduct product,
            ItemStack displayItem, int quantity, String cost) {
        ConfirmationHolder holder = new ConfirmationHolder(product, quantity);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                messages.getGui(player, "rune.bulk-confirm-title", "amount", String.valueOf(quantity)));
        holder.inventory = inventory;

        ItemStack details = displayItem.clone();
        details.setAmount(1);
        ItemMeta detailsMeta = details.getItemMeta();
        List<Component> lore = new ArrayList<>(detailsMeta.hasLore() && detailsMeta.lore() != null
                ? detailsMeta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(messages.getGui(player, "rune.bulk-confirm-amount", "amount", String.valueOf(quantity)));
        lore.add(messages.getGui(player, "rune.bulk-confirm-cost", "cost", cost));
        detailsMeta.lore(lore);
        details.setItemMeta(detailsMeta);
        inventory.setItem(13, details);
        inventory.setItem(11, button(Material.LIME_CONCRETE,
                messages.getGui(player, "rune.bulk-confirm-button"),
                List.of(messages.getGui(player, "rune.bulk-confirm-hint"))));
        inventory.setItem(15, button(Material.RED_CONCRETE,
                messages.getGui(player, "rune.bulk-cancel-button"),
                List.of(messages.getGui(player, "rune.bulk-cancel-hint"))));
        player.openInventory(inventory);
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, PurchaseProduct> slotToProduct = new LinkedHashMap<>();
        private Inventory inventory;

        private Holder() {
        }

        public PurchaseProduct productAt(int slot) {
            return slotToProduct.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class ConfirmationHolder implements InventoryHolder {
        private final PurchaseProduct product;
        private final int quantity;
        private Inventory inventory;

        private ConfirmationHolder(PurchaseProduct product, int quantity) {
            this.product = product;
            this.quantity = quantity;
        }

        public PurchaseProduct product() { return product; }
        public int quantity() { return quantity; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
