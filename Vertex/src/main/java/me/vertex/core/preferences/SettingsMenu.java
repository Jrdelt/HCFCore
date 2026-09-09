package me.vertex.core.preferences;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Small self-contained GUI for optional broadcast categories. */
public final class SettingsMenu {
    private static final List<Entry> ENTRIES = List.of(
            new Entry(10, AnnouncementCategory.COINFLIPS, Material.SUNFLOWER, "settings.coinflips"),
            new Entry(12, AnnouncementCategory.KOTH, Material.NETHER_STAR, "settings.koth"),
            new Entry(14, AnnouncementCategory.OUTPOST, Material.CAMPFIRE, "settings.outpost"),
            new Entry(16, AnnouncementCategory.MINING, Material.DIAMOND_PICKAXE, "settings.mining"),
            new Entry(22, AnnouncementCategory.SERVER, Material.REDSTONE_TORCH, "settings.server"));

    private SettingsMenu() {
    }

    public static void open(Player player, AnnouncementPreferenceManager preferences, Messages messages) {
        Holder holder = new Holder(preferences, messages);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "settings.title"));
        holder.inventory = inventory;
        for (Entry entry : ENTRIES) {
            inventory.setItem(entry.slot(), button(player, preferences, messages, entry));
        }
        player.openInventory(inventory);
    }

    static Entry entryAt(int slot) {
        return ENTRIES.stream().filter(entry -> entry.slot() == slot).findFirst().orElse(null);
    }

    private static ItemStack button(Player player, AnnouncementPreferenceManager preferences, Messages messages, Entry entry) {
        boolean enabled = preferences.isEnabled(player.getUniqueId(), entry.category());
        ItemStack item = new ItemStack(entry.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, entry.messageKey())));
        meta.lore(List.of(
                noItalic(messages.get(player, enabled ? "settings.enabled" : "settings.disabled")),
                Component.empty(), noItalic(messages.get(player, "settings.toggle-hint"))));
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    record Entry(int slot, AnnouncementCategory category, Material material, String messageKey) {
    }

    static final class Holder implements InventoryHolder {
        private final AnnouncementPreferenceManager preferences;
        private final Messages messages;
        private Inventory inventory;

        private Holder(AnnouncementPreferenceManager preferences, Messages messages) {
            this.preferences = preferences;
            this.messages = messages;
        }

        AnnouncementPreferenceManager preferences() { return preferences; }
        Messages messages() { return messages; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
