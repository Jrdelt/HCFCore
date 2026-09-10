package me.vertex.core.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * One configured entry in a menu: what it looks like, where it sits, and
 * what it sounds like when clicked.
 *
 * <p>A template may be used once (a Back button) or many times (a row of
 * category icons, rendered from the same template with different
 * placeholders), which is why slots and material can both be supplied by the
 * caller instead of the file.
 */
public final class MenuItemTemplate {

    private final String id;
    private final Material material;
    private final Integer customModelData;
    private final int amount;
    private final String name;
    private final List<String> lore;
    private final int[] slots;
    private final boolean enabled;
    private final Sound sound;
    private final boolean glow;

    MenuItemTemplate(String id, Material material, Integer customModelData, int amount, String name,
            List<String> lore, int[] slots, boolean enabled, Sound sound) {
        this(id, material, customModelData, amount, name, lore, slots, enabled, sound, false);
    }

    MenuItemTemplate(String id, Material material, Integer customModelData, int amount, String name,
            List<String> lore, int[] slots, boolean enabled, Sound sound, boolean glow) {
        this.id = id;
        this.material = material;
        this.customModelData = customModelData;
        this.amount = amount;
        this.name = name;
        this.lore = List.copyOf(lore);
        this.slots = slots.clone();
        this.enabled = enabled;
        this.sound = sound;
        this.glow = glow;
    }

    public String id() {
        return id;
    }

    /** Slots this template occupies, empty when the caller decides placement. */
    public int[] slots() {
        return slots.clone();
    }

    /** The first configured slot, or -1 when the caller decides placement. */
    public int slot() {
        return slots.length == 0 ? -1 : slots[0];
    }

    /** An optional button an admin has switched off is simply not rendered. */
    public boolean enabled() {
        return enabled;
    }

    public Sound sound() {
        return sound;
    }

    public ItemStack render(MenuPlaceholders placeholders) {
        return render(material, placeholders);
    }

    /**
     * @param fallbackMaterial used when the file names no material, for
     *                         templates whose icon is inherently per-instance
     */
    public ItemStack render(Material fallbackMaterial, MenuPlaceholders placeholders) {
        Material resolved = material != null ? material : fallbackMaterial;
        ItemStack item = new ItemStack(resolved == null ? Material.STONE : resolved, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (name != null && !name.isEmpty()) {
            meta.displayName(noItalic(placeholders.render(name).getFirst()));
        }
        if (!lore.isEmpty()) {
            List<Component> rendered = new ArrayList<>();
            for (String line : lore) {
                placeholders.render(line).forEach(component -> rendered.add(noItalic(component)));
            }
            meta.lore(rendered);
        }
        if (customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
