package me.vertex.core.resetvault;

import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/**
 * Centralized item display-name resolver.
 * Custom items use their actual custom display name/formatting across all message types
 * (Components, MiniMessage, legacy ampersand, legacy section sign, spread hex, etc.).
 * Vanilla unnamed items use clean names such as DIAMOND_SWORD -> Diamond Sword.
 */
public final class ItemDisplayNameResolver {

    public String resolve(ItemStack item) {
        Component comp = resolveComponent(item);
        return MessageFormatter.serialize(comp);
    }

    public Component resolveComponent(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Component.text("Air");
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                Component comp = meta.displayName();
                String plain = PlainTextComponentSerializer.plainText().serialize(comp);
                if (containsFormattingCodes(plain)) {
                    return MessageFormatter.deserialize(plain);
                }
                return comp;
            }
        }
        return Component.text(titleCase(item.getType()));
    }

    private static boolean containsFormattingCodes(String text) {
        return text != null && (text.contains("&") || text.contains("§") || (text.contains("<") && text.contains(">")));
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
