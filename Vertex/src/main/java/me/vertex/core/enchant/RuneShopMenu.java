package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
 * Opened from the "Buy Runes" control-row button in {@code /shop} -- a
 * catalog of the four Rune tiers, priced per {@code runes.yml} (not {@code
 * shop.yml}), same precedent as {@code ChunkBusterShopMenu}/{@code
 * SourceBucketShopMenu} being priced from their own feature config rather
 * than becoming literal {@code ShopEntry} rows: a Rune is a custom item
 * with a tier, not a plain vanilla material, so it cannot fit the
 * dynamic-pricing block-market model {@code ShopEntry} assumes.
 */
public final class RuneShopMenu {

    /** Reserved slot for the "back to shop" button -- tier icons start at 1. */
    public static final int SLOT_BACK = 0;

    private RuneShopMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "rune.shop-title"));
        holder.inventory = inventory;

        inventory.setItem(SLOT_BACK, backButton(player, messages));

        int slot = SLOT_BACK + 1;
        for (RuneTier tier : RuneTier.values()) {
            if (slot >= inventory.getSize()) {
                break;
            }
            holder.slotToTier.put(slot, tier);
            inventory.setItem(slot, buildIcon(player, messages, manager, tier));
            slot++;
        }
        player.openInventory(inventory);
    }

    private static ItemStack backButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(player, "shop.back-button").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildIcon(Player player, Messages messages, EnchantManager manager, RuneTier tier) {
        ItemStack item = manager.createRune(tier);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(messages.get(player, "rune.shop-price", "amount", EconomyHook.format(manager.runeShopPrice(tier))));
        lore.add(messages.get(player, "rune.shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, RuneTier> slotToTier = new LinkedHashMap<>();
        private Inventory inventory;

        private Holder() {
        }

        public RuneTier tierAt(int slot) {
            return slotToTier.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
