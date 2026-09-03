package me.hcfcore.core.staff;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class Death {

    private final long timestamp;
    private final String cause;
    private final String killerName;
    private final List<ItemStack> items;
    private final ItemStack helmet;
    private final ItemStack chestplate;
    private final ItemStack leggings;
    private final ItemStack boots;
    private final ItemStack offhand;

    public Death(long timestamp, String cause, String killerName, List<ItemStack> items,
                 ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots, ItemStack offhand) {
        this.timestamp = timestamp;
        this.cause = cause;
        this.killerName = killerName;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.offhand = offhand;
    }

    public static Death from(Player player, String cause, String killerName) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> items = new ArrayList<>();

        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }

        ItemStack helmet = inv.getHelmet() != null ? inv.getHelmet().clone() : null;
        ItemStack chestplate = inv.getChestplate() != null ? inv.getChestplate().clone() : null;
        ItemStack leggings = inv.getLeggings() != null ? inv.getLeggings().clone() : null;
        ItemStack boots = inv.getBoots() != null ? inv.getBoots().clone() : null;
        ItemStack offhand = inv.getItemInOffHand() != null ? inv.getItemInOffHand().clone() : null;

        return new Death(System.currentTimeMillis(), cause, killerName, items, helmet, chestplate, leggings, boots, offhand);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getCause() {
        return cause;
    }

    public String getKillerName() {
        return killerName;
    }

    public List<ItemStack> getItems() {
        return new ArrayList<>(items);
    }

    public ItemStack getHelmet() {
        return helmet;
    }

    public ItemStack getChestplate() {
        return chestplate;
    }

    public ItemStack getLeggings() {
        return leggings;
    }

    public ItemStack getBoots() {
        return boots;
    }

    public ItemStack getOffhand() {
        return offhand;
    }

    public List<ItemStack> getAllItems() {
        List<ItemStack> all = new ArrayList<>(items);
        if (helmet != null) all.add(helmet);
        if (chestplate != null) all.add(chestplate);
        if (leggings != null) all.add(leggings);
        if (boots != null) all.add(boots);
        if (offhand != null) all.add(offhand);
        return all;
    }
}
