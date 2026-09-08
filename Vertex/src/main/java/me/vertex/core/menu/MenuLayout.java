package me.vertex.core.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** One menu as configured: its size, title, background, and item templates. */
public final class MenuLayout {

    private final String id;
    private final int size;
    private final String title;
    private final long refreshTicks;
    private final MenuItemTemplate filler;
    private final Map<String, MenuItemTemplate> items;
    private final Map<String, int[]> slotLists;
    private final Map<String, String> titleVariants;

    MenuLayout(String id, int size, String title, long refreshTicks, MenuItemTemplate filler,
            Map<String, MenuItemTemplate> items, Map<String, int[]> slotLists,
            Map<String, String> titleVariants) {
        this.id = id;
        this.size = size;
        this.title = title;
        this.refreshTicks = refreshTicks;
        this.filler = filler;
        this.items = Map.copyOf(items);
        this.slotLists = Map.copyOf(slotLists);
        this.titleVariants = Map.copyOf(titleVariants);
    }

    /**
     * A named run of slots from the file's {@code layout:} section -- where a
     * repeated template gets placed. Falls back to the built-in positions
     * when an admin has not overridden them.
     */
    public int[] slots(String key, int[] fallback) {
        int[] configured = slotLists.get(key);
        return configured == null || configured.length == 0 ? fallback.clone() : configured.clone();
    }

    public String id() {
        return id;
    }

    public int size() {
        return size;
    }

    /** How often a live menu should re-render, or 0 when it never needs to. */
    public long refreshTicks() {
        return refreshTicks;
    }

    /**
     * @return the configured template, or null when the file omits it -- callers
     *         treat a missing optional button as "not shown"
     */
    public MenuItemTemplate item(String templateId) {
        MenuItemTemplate template = items.get(templateId);
        return template == null || !template.enabled() ? null : template;
    }

    public Component title(MenuPlaceholders placeholders) {
        return title(null, placeholders);
    }

    /**
     * A named title from the file's {@code titles:} section -- one menu often
     * needs several (a detail page, an "inspecting someone else" page). Falls
     * back to the main title when the variant is absent.
     */
    public Component title(String variant, MenuPlaceholders placeholders) {
        String template = variant == null ? title : titleVariants.getOrDefault(variant, title);
        return placeholders.render(template).getFirst().decoration(TextDecoration.ITALIC, false);
    }

    /** Builds the inventory and paints the background, if one is configured. */
    public Inventory createInventory(InventoryHolder holder, MenuPlaceholders placeholders) {
        return createInventory(holder, null, placeholders);
    }

    public Inventory createInventory(InventoryHolder holder, String titleVariant, MenuPlaceholders placeholders) {
        Inventory inventory = Bukkit.createInventory(holder, size, title(titleVariant, placeholders));
        if (filler != null && filler.enabled()) {
            ItemStack background = filler.render(placeholders);
            for (int slot = 0; slot < size; slot++) {
                inventory.setItem(slot, background);
            }
        }
        return inventory;
    }

    /** Renders a template into every slot the file assigned it. */
    public void place(Inventory inventory, String templateId, MenuPlaceholders placeholders) {
        MenuItemTemplate template = item(templateId);
        if (template == null) {
            return;
        }
        ItemStack rendered = template.render(placeholders);
        for (int slot : template.slots()) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, rendered);
            }
        }
    }

    /** Plays a template's configured click sound, when it has one. */
    public void playSound(Player player, String templateId) {
        MenuItemTemplate template = item(templateId);
        if (template != null && template.sound() != null) {
            player.playSound(player.getLocation(), template.sound(), 1f, 1f);
        }
    }
}
