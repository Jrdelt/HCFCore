package me.vertex.core.resetvault;

import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/**
 * Centralized item display-name resolver.
 * Custom items use their actual custom display name/formatting.
 * Vanilla unnamed items use clean names such as DIAMOND_SWORD -> Diamond Sword.
 */
public final class ItemDisplayNameResolver {

    public String resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "Air";
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) {
                return MessageFormatter.serialize(meta.displayName());
            }
        }
        return titleCase(item.getType());
    }

    public static String titleCase(Material material) {
        if (material == null) {
            return "Air";
        }
        String name = material.name().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(name.length());
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
