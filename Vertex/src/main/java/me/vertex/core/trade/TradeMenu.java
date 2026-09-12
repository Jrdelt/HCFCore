package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/** Shared six-row, two-sided trade inventory. */
final class TradeMenu {
    static final int SIZE = 54;
    static final int REQUESTER_LOCK = 47, TARGET_LOCK = 51;
    static final int[] LEFT_SLOTS = grid(0);
    static final int[] RIGHT_SLOTS = grid(5);

    private TradeMenu() { }
    private static int[] grid(int startColumn) {
        int[] slots = new int[16]; int index = 0;
        for (int row = 1; row <= 4; row++) for (int col = startColumn; col < startColumn + 4; col++) slots[index++] = row * 9 + col;
        return slots;
    }
    static boolean isTradeSlot(int slot) { return contains(LEFT_SLOTS, slot) || contains(RIGHT_SLOTS, slot); }
    static boolean ownTradeSlot(TradeSession s, UUID player, int slot) { return contains(s.slotsFor(player), slot); }
    static boolean contains(int[] values, int value) { for (int candidate : values) if (candidate == value) return true; return false; }

    static Inventory open(TradeSession session, OfflinePlayer requester, OfflinePlayer target, TradeManager manager, Messages messages) {
        Inventory inventory = Bukkit.createInventory(new Holder(session.id), SIZE,
                messages.getGui(requester.getPlayer() == null ? Bukkit.getConsoleSender() : requester.getPlayer(), "trade.gui-title", "player", target.getName() == null ? "player" : target.getName()));
        session.inventory = inventory;
        render(session, requester, target, manager, messages);
        return inventory;
    }
    static void render(TradeSession session, OfflinePlayer requester, OfflinePlayer target, TradeManager manager, Messages messages) {
        Inventory inventory = session.inventory;
        ItemStack filler = named(manager.fillerMaterial(), Component.empty());
        for (int slot = 0; slot < SIZE; slot++) if (!isTradeSlot(slot)) inventory.setItem(slot, filler);
        for (int row = 0; row < 6; row++) inventory.setItem(row * 9 + 4, named(manager.dividerMaterial(), Component.empty()));
        inventory.setItem(0, head(requester, messages, "trade.requester-head", requester.getName()));
        inventory.setItem(8, head(target, messages, "trade.target-head", target.getName()));
        inventory.setItem(REQUESTER_LOCK, button(manager, messages, requester.getPlayer(), session, session.requester));
        inventory.setItem(TARGET_LOCK, button(manager, messages, target.getPlayer(), session, session.target));
    }
    private static ItemStack head(OfflinePlayer player, Messages messages, String key, String fallback) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player); meta.displayName(messages.getGui(player.getPlayer() == null ? Bukkit.getConsoleSender() : player.getPlayer(), key, "player", fallback == null ? "player" : fallback)); head.setItemMeta(meta); return head;
    }
    private static ItemStack button(TradeManager manager, Messages messages, org.bukkit.command.CommandSender viewer, TradeSession session, UUID owner) {
        boolean locked = session.locked(owner); boolean confirm = session.bothLocked() && owner.equals(session.firstLocked);
        ItemStack item = new ItemStack(locked && !confirm ? manager.lockedMaterial() : manager.confirmMaterial());
        ItemMeta meta = item.getItemMeta(); org.bukkit.command.CommandSender sender = viewer == null ? Bukkit.getConsoleSender() : viewer;
        meta.displayName(messages.getGui(sender, confirm ? "trade.final-accept-title" : locked ? "trade.locked-title" : "trade.lock-title"));
        meta.lore(List.of(messages.getGui(sender, confirm ? "trade.final-accept-lore" : locked ? "trade.locked-lore" : "trade.lock-lore"))); item.setItemMeta(meta); return item;
    }
    static ItemStack named(Material material, Component name) { ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.displayName(name); item.setItemMeta(meta); return item; }
    record Holder(UUID sessionId) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
    record PeekHolder(UUID sessionId) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
