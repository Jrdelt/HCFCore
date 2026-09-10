package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

    private RuneShopMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "rune.shop-title"));
        holder.inventory = inventory;

        for (int index = 0; index < RuneTier.values().length && index < TIER_SLOTS.size(); index++) {
            RuneTier tier = RuneTier.values()[index];
            int slot = TIER_SLOTS.get(index);
            holder.slotToTier.put(slot, tier);
            inventory.setItem(slot, buildIcon(player, messages, manager, tier));
        }
        player.openInventory(inventory);
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
