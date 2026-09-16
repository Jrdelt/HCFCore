package me.vertex.core.storage;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;

/**
 * Direct, always-succeeding give: adds straight to the player's inventory,
 * dropping anything that doesn't fit at their feet. Replaces the old durable
 * delivery inbox (queued overflow, WAL/SQL, restart-safe redelivery) with the
 * plain give every other Bukkit plugin uses -- nothing here can "fail" in a
 * way a caller needs to roll back for.
 */
public final class ItemGiver {
    private ItemGiver() { }

    public static void give(Player player, Collection<ItemStack> items) {
        if (player == null || items == null || items.isEmpty()) {
            return;
        }
        ItemStack[] toGive = items.stream().filter(item -> item != null && !item.isEmpty()).toArray(ItemStack[]::new);
        if (toGive.length == 0) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(toGive);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
