package me.vertex.core.enchant;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One shared definition of "every item a rune could currently be sourced
 * from for {@code /binds}" -- armor + main hand + off hand + hotbar + main
 * inventory. A strict superset of {@code
 * me.vertex.core.enchant.listener.RuneEffectListener}'s own {@code
 * equipped()} (which only covers armor+hands, since passive procs only ever
 * come from worn/held gear). Used by the Rune Selector, the live bind HUD
 * refresh, and activation-time re-validation -- one scan, not three that
 * could drift apart.
 */
public final class RuneEquipment {

    private RuneEquipment() {
    }

    public static List<ItemStack> bindEligibleItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        if (player == null) {
            return items;
        }
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getArmorContents()) {
            add(items, item);
        }
        add(items, inventory.getItemInMainHand());
        add(items, inventory.getItemInOffHand());
        // getStorageContents() is hotbar (0-8) + main inventory (9-35) --
        // exactly the two sources the spec adds on top of equipped gear. The
        // held item is included twice (here and via getItemInMainHand()
        // above), which is harmless: every caller dedupes by rune id anyway.
        for (ItemStack item : inventory.getStorageContents()) {
            add(items, item);
        }
        return items;
    }

    /**
     * @return the highest currently-available level of {@code enchantId} across every eligible item, or 0 if unavailable.
     */
    public static int highestAvailableLevel(Player player, EnchantManager manager, String enchantId) {
        int highest = 0;
        for (ItemStack item : bindEligibleItems(player)) {
            Integer level = manager.enchantsOf(item).get(enchantId);
            if (level != null && level > highest) {
                highest = level;
            }
        }
        return highest;
    }

    /**
     * Resolves the display item representation for an enchant/ability.
     * If the player holds/equips an eligible seasonal item carrying this enchant, that real item is shown.
     * Otherwise, bakes the seasonal item from catalog or generates the appropriate tiered rune item.
     */
    public static ItemStack resolveDisplayItem(Player player, EnchantManager manager, String enchantId, int level) {
        if (manager == null || enchantId == null) {
            return null;
        }
        // 1. Check if the player currently holds/equips an eligible item with this enchant
        if (player != null) {
            for (ItemStack candidate : bindEligibleItems(player)) {
                if (candidate != null && !candidate.getType().isAir()) {
                    Map<String, Integer> enchants = manager.enchantsOf(candidate);
                    if (enchants.containsKey(enchantId)) {
                        ItemStack clone = candidate.clone();
                        clone.setAmount(1);
                        // A bind icon is a reference to which rune is bound,
                        // not a live readout of that piece's condition -- the
                        // real equipped item's damage/durability must never
                        // bleed into this display copy, or the icon visibly
                        // cracks/darkens the moment the player takes damage.
                        if (clone.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable
                                && damageable.hasDamage()) {
                            damageable.setDamage(0);
                            clone.setItemMeta((ItemMeta) damageable);
                        }
                        return clone;
                    }
                }
            }
        }
        // 2. If seasonal, bake the real seasonal item from catalog or definition
        RuneTier tier = manager.isSeasonal(enchantId) ? RuneTier.SEASONAL : tierGuess(manager, enchantId);
        ItemStack item = manager.createEnchantItem(enchantId, Math.max(1, level), tier);
        if (item != null) {
            return item;
        }
        // 3. Fallback: check definition material/customModelData
        EnchantDefinition definition = manager.definition(enchantId);
        if (definition != null) {
            EnchantDefinition.Level levelConfig = definition.level(Math.max(1, level));
            if (levelConfig != null) {
                ItemStack fallback = new ItemStack(levelConfig.material());
                ItemMeta meta = fallback.getItemMeta();
                if (levelConfig.customModelData() != null) {
                    meta.setCustomModelData(levelConfig.customModelData());
                }
                fallback.setItemMeta(meta);
                return fallback;
            }
        }
        return null;
    }

    public static RuneTier tierGuess(EnchantManager manager, String enchantId) {
        if (manager.isSeasonal(enchantId)) {
            return RuneTier.SEASONAL;
        }
        for (RuneTier tier : RuneTier.values()) {
            if (!manager.rollTable(tier).isEmpty() && manager.rollTable(tier).entries().stream()
                    .anyMatch(entry -> entry.enchantId().equals(enchantId))) {
                return tier;
            }
        }
        return RuneTier.SIMPLE;
    }

    private static void add(List<ItemStack> items, ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            items.add(item);
        }
    }
}
