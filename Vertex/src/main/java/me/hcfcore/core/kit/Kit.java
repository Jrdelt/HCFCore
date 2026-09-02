package me.hcfcore.core.kit;

import org.bukkit.inventory.ItemStack;

public final class Kit {

    private final String name;
    private final String permission;
    private final int cooldownSeconds;
    private final ItemStack[] armor;
    private final ItemStack[] contents;

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents) {
        this.name = name;
        this.permission = permission;
        this.cooldownSeconds = cooldownSeconds;
        this.armor = armor;
        this.contents = contents;
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
}
