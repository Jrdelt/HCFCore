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

/** Double-chest GUI for every persistent player communication preference. */
public final class SettingsMenu {
    private static final List<Entry> ENTRIES = List.of(
            new Entry(10, AnnouncementCategory.GLOBAL_CHAT, Material.WRITABLE_BOOK, "settings.global-chat", "settings.global-chat-lore"),
            new Entry(11, AnnouncementCategory.TRADE_REQUESTS, Material.EMERALD, "settings.trade-requests", "settings.trade-requests-lore"),
            new Entry(12, AnnouncementCategory.NOTIFICATIONS, Material.BELL, "settings.notifications", "settings.notifications-lore"),
            new Entry(13, AnnouncementCategory.PRIVATE_MESSAGES, Material.PAPER, "settings.private-messages", "settings.private-messages-lore"),
            new Entry(14, AnnouncementCategory.PAYMENTS, Material.GOLD_INGOT, "settings.payments", "settings.payments-lore"),
            new Entry(15, AnnouncementCategory.TELEPORT_REQUESTS, Material.ENDER_PEARL, "settings.teleport-requests", "settings.teleport-requests-lore"),
            new Entry(20, AnnouncementCategory.COINFLIPS, Material.SUNFLOWER, "settings.coinflips", null),
            new Entry(21, AnnouncementCategory.KOTH, Material.NETHER_STAR, "settings.koth", null),
            new Entry(22, AnnouncementCategory.OUTPOST, Material.CAMPFIRE, "settings.outpost", null),
            new Entry(23, AnnouncementCategory.MINING, Material.DIAMOND_PICKAXE, "settings.mining", null),
            new Entry(24, AnnouncementCategory.SERVER, Material.REDSTONE_TORCH, "settings.server", null));

    private SettingsMenu() {
    }

    public static void open(Player player, AnnouncementPreferenceManager preferences, Messages messages) {
        Holder holder = new Holder(preferences, messages);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "settings.title"));
        holder.inventory = inventory;
        ItemStack border = border();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == 5 || column == 0 || column == 8) {
                inventory.setItem(slot, border);
            }
        }
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
        meta.displayName(noItalic(messages.getGui(player, entry.messageKey())));
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (entry.descriptionKey() != null) {
            lore.add(noItalic(messages.getGui(player, entry.descriptionKey())));
            lore.add(Component.empty());
        }
        lore.add(noItalic(messages.getGui(player, enabled ? "settings.enabled" : "settings.disabled")));
        lore.add(Component.empty());
        lore.add(noItalic(messages.getGui(player, "settings.toggle-hint")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack border() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(noItalic(Component.text(" ")));
        pane.setItemMeta(meta);
        return pane;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    record Entry(int slot, AnnouncementCategory category, Material material, String messageKey, String descriptionKey) {
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
