package me.vertex.core.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Standardized utility for creating and styling items and menu buttons.
 */
public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = this.item.getItemMeta();
    }

    public ItemBuilder(ItemStack item) {
        this.item = item.clone();
        this.meta = this.item.getItemMeta();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    public static ItemBuilder of(ItemStack item) {
        return new ItemBuilder(item);
    }

    /**
     * Standard helper to build a UI button item with a display name and optional lore.
     */
    public static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Standard helper to build a UI button item with a display name and no lore.
     */
    public static ItemStack button(Material material, Component name) {
        return button(material, name, null);
    }

    public ItemBuilder name(Component name) {
        if (meta != null) {
            meta.displayName(name);
        }
        return this;
    }

    public ItemBuilder lore(List<Component> lore) {
        if (meta != null && lore != null) {
            meta.lore(lore);
        }
        return this;
    }

    public ItemBuilder addLore(Component... lines) {
        if (meta != null && lines != null && lines.length > 0) {
            List<Component> current = meta.lore();
            List<Component> updated = current != null ? new ArrayList<>(current) : new ArrayList<>();
            for (Component line : lines) {
                if (line != null) {
                    updated.add(line);
                }
            }
            meta.lore(updated);
        }
        return this;
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder customModelData(Integer data) {
        if (meta != null) {
            meta.setCustomModelData(data);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }
}
