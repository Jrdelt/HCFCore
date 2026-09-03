package me.hcfcore.core.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class Kit {

    private final String name;
    private final String permission;
    private final int cooldownSeconds;
    private final ItemStack[] armor;
    private final ItemStack[] contents;
    private final Cost cost;

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents) {
        this(name, permission, cooldownSeconds, armor, contents, Cost.NONE);
    }

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents, Cost cost) {
        this.name = name;
        this.permission = permission;
        this.cooldownSeconds = cooldownSeconds;
        this.armor = cloneArray(armor);
        this.contents = cloneArray(contents);
        this.cost = cost;
    }

    public String getName() {
        return name;
    }

    public String getPermission() {
        return permission;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public Cost getCost() {
        return cost;
    }

    public ItemStack[] getArmor() {
        return cloneArray(armor);
    }

    public ItemStack[] getContents() {
        return cloneArray(contents);
    }

    private static ItemStack[] cloneArray(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    /**
     * A kit's price. Either or both of the money/item components can be
     * set; NONE means free. itemType/itemAmount travel together -- a
     * non-null type with a zero amount is treated as no item cost.
     */
    public record Cost(double money, Material itemType, int itemAmount) {
        public static final Cost NONE = new Cost(0.0, null, 0);

        public boolean hasMoneyCost() {
            return money > 0;
        }

        public boolean hasItemCost() {
            return itemType != null && itemAmount > 0;
        }
    }
}
