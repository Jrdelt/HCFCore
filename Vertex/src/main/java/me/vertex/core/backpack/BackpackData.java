package me.vertex.core.backpack;

import org.bukkit.inventory.ItemStack;

/**
 * A single backpack's state, round-tripped to/from its item's
 * PersistentDataContainer. {@code contents} is one entry per distinct
 * material -- not a fixed-size, Bukkit-inventory-shaped slot array -- so
 * a single entry's {@link ItemStack#getAmount()} can legitimately exceed
 * vanilla's normal max-stack-size; the only real ceiling is
 * {@link BackpackManager#itemCapacityForLevel}. Splitting an entry back
 * into real, vanilla-sized stacks only happens when handing it to an
 * actual inventory or the ground (see
 * {@link BackpackManager#splitIntoRealStacks}).
 */
record BackpackData(String tierId, int level, ItemStack[] contents) {

    BackpackData withLevel(int newLevel) {
        return new BackpackData(tierId, newLevel, contents);
    }

    BackpackData withContents(ItemStack[] newContents) {
        return new BackpackData(tierId, level, newContents);
    }
}
