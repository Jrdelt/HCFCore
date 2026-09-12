package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
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
 */
public final class RuneShopMenu {

    private static final List<Integer> TIER_SLOTS = List.of(11, 12, 14, 15);
    private static volatile ArenaRuneManager arenaRunes;

    public enum PurchaseProduct {
        SIMPLE(RuneTier.SIMPLE, null),
        ELITE(RuneTier.ELITE, null),
        RARE(RuneTier.RARE, null),
        LEGENDARY(RuneTier.LEGENDARY, null),
        ARENA_RUNE(null, ArenaRuneManager.Purchase.RUNE),
        ARENA_LUCKY_GEM(null, ArenaRuneManager.Purchase.LUCKY_GEM);

        private final RuneTier tier;
        private final ArenaRuneManager.Purchase arenaPurchase;

        PurchaseProduct(RuneTier tier, ArenaRuneManager.Purchase arenaPurchase) {
            this.tier = tier;
            this.arenaPurchase = arenaPurchase;
        }

        public RuneTier tier() { return tier; }
        public ArenaRuneManager.Purchase arenaPurchase() { return arenaPurchase; }
        public boolean isArena() { return arenaPurchase != null; }

        static PurchaseProduct forTier(RuneTier tier) {
            return switch (tier) {
                case SIMPLE -> SIMPLE;
                case ELITE -> ELITE;
                case RARE -> RARE;
                case LEGENDARY -> LEGENDARY;
            };
        }
    }

    private RuneShopMenu() {
    }

    public static void setArenaRunes(ArenaRuneManager manager) {
        arenaRunes = manager;
    }

    static ArenaRuneManager arenaRunes() {
        return arenaRunes;
    }

    public static void open(Player player, EnchantManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "rune.shop-title"));
        holder.inventory = inventory;

        for (int index = 0; index < RuneTier.values().length && index < TIER_SLOTS.size(); index++) {
            RuneTier tier = RuneTier.values()[index];
            int slot = TIER_SLOTS.get(index);
            holder.slotToProduct.put(slot, PurchaseProduct.forTier(tier));
            inventory.setItem(slot, buildIcon(player, messages, manager, tier));
        }
        ArenaRuneManager arena = arenaRunes;
        if (arena != null) {
            holder.slotToProduct.put(13, PurchaseProduct.ARENA_RUNE);
            holder.slotToProduct.put(22, PurchaseProduct.ARENA_LUCKY_GEM);
            inventory.setItem(13, arena.createShopRuneIcon());
            inventory.setItem(22, arena.createShopLuckyGemIcon());
        }
        player.openInventory(inventory);
    }

    private static ItemStack buildIcon(Player player, Messages messages, EnchantManager manager, RuneTier tier) {
        ItemStack item = manager.createRune(tier);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(messages.getGui(player, "rune.shop-price", "amount", EconomyHook.format(manager.runeShopPrice(tier))));
        lore.add(messages.getGui(player, "rune.shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
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
