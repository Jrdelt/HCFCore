package me.vertex.core.enchant;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

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

    private static void add(List<ItemStack> items, ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            items.add(item);
        }
    }
}
