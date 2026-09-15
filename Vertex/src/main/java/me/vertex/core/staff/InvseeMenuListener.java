package me.vertex.core.staff;

import me.vertex.core.lang.Messages;
import me.vertex.core.storage.ClaimDelivery;
import me.vertex.core.storage.InventoryAccess;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Cancels native cross-player transfers; source and destination change in one server-thread operation. */
public final class InvseeMenuListener implements Listener {
    private final Plugin plugin;
    private final Messages messages;
    public InvseeMenuListener(Plugin plugin, Messages messages) { this.plugin = plugin; this.messages = messages; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InvseeMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player viewer)) return;
        // Never allow a native transfer or a deferred close-time write-back.
        event.setCancelled(true);
        Player target = target(holder, viewer);
        if (target == null) return;
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < InvseeMenu.SIZE;
        if (top && InvseeMenu.isFillerSlot(raw)) return;
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.MIDDLE || event.getClick() == ClickType.CREATIVE) return;
        if (!top && !event.isShiftClick()) {
            // Ordinary bottom clicks cannot move anything into the detached top.
            event.setCancelled(false);
            return;
        }
        if (!fresh(target, holder)) { reject(holder, viewer, target); return; }
        ItemStack[] before = InvseeMenu.snapshot(holder.getInventory());
        ItemStack[] next = copy(before);
        ItemStack[] viewerBefore = copy(viewer.getInventory().getContents());
        ItemStack[] viewerNext = copy(viewerBefore);
        ItemStack cursor = cloneItem(viewer.getItemOnCursor());
        ItemStack nextCursor = cloneItem(cursor);
        if (event.isShiftClick()) {
            if (top) {
                ItemStack source = next[raw];
                next[raw] = move(source, viewerNext, 36);
            } else if (event.getClickedInventory() == viewer.getInventory()) {
                int slot = event.getSlot();
                if (slot < 0 || slot >= viewerNext.length) return;
                viewerNext[slot] = move(viewerNext[slot], next, 36);
            } else return;
        } else if (top && (event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.SWAP_OFFHAND)) {
            int slot = event.getClick() == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
            if (slot < 0 || slot >= viewerNext.length || !fits(viewerNext[slot], raw)) return;
            ItemStack swap = next[raw]; next[raw] = viewerNext[slot]; viewerNext[slot] = swap;
        } else if (top && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
            ItemStack have = next[raw];
            boolean right = event.getClick() == ClickType.RIGHT;
            if (empty(cursor)) {
                if (empty(have)) return;
                int quantity = right ? (have.getAmount() + 1) / 2 : have.getAmount();
                nextCursor = amount(have, quantity); next[raw] = amount(have, have.getAmount() - quantity);
            } else if (empty(have) || have.isSimilar(cursor)) {
                int capacity = InvseeMenu.isArmorSlot(raw) ? 1 : cursor.getMaxStackSize();
                int existing = empty(have) ? 0 : have.getAmount();
                int quantity = Math.min(right ? 1 : cursor.getAmount(), Math.max(0, capacity - existing));
                next[raw] = amount(cursor, existing + quantity);
                if (!fits(next[raw], raw)) return;
                nextCursor = amount(cursor, cursor.getAmount() - quantity);
            } else {
                if (!fits(cursor, raw)) return;
                next[raw] = cursor; nextCursor = have;
            }
        } else return;
        commit(holder, viewer, target, before, next, viewerBefore, viewerNext, cursor, nextCursor);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InvseeMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player viewer)) return;
        event.setCancelled(true);
        Player target = target(holder, viewer);
        if (target == null) return;
        if (!fresh(target, holder)) { reject(holder, viewer, target); return; }
        ItemStack[] before = InvseeMenu.snapshot(holder.getInventory()), next = copy(before);
        ItemStack[] viewerBefore = copy(viewer.getInventory().getContents()), viewerNext = copy(viewerBefore);
        ItemStack cursor = cloneItem(event.getOldCursor());
        if (empty(cursor)) return;
        int moved = 0;
        for (var entry : event.getNewItems().entrySet()) {
            int raw = entry.getKey();
            boolean top = raw >= 0 && raw < InvseeMenu.SIZE;
            if (raw < 0 || (top && InvseeMenu.isFillerSlot(raw))) return;
            int slot = top ? raw : event.getView().convertSlot(raw);
            ItemStack[] contents = top ? next : viewerNext;
            if (slot < 0 || slot >= contents.length) return;
            ItemStack item = entry.getValue(), previous = contents[slot];
            if (!item.isSimilar(cursor) || (!empty(previous) && !previous.isSimilar(cursor))
                    || item.getAmount() > item.getMaxStackSize() || (top && !fits(item, raw))) return;
            int delta = item.getAmount() - (empty(previous) ? 0 : previous.getAmount());
            if (delta < 0) return;
            moved += delta; contents[slot] = item.clone();
        }
        int remaining = empty(event.getCursor()) ? 0 : event.getCursor().getAmount();
        if (moved + remaining != cursor.getAmount() || (!empty(event.getCursor()) && !event.getCursor().isSimilar(cursor))) return;
        commit(holder, viewer, target, before, next, viewerBefore, viewerNext, cursor, cloneItem(event.getCursor()));
    }

    private Player target(InvseeMenu.Holder holder, Player viewer) {
        Player target = Bukkit.getPlayer(holder.targetId());
        if (target == null || !target.isOnline() || target == viewer || !viewer.hasPermission("vertex.staff.invsee")
                || !InventoryAccess.ready(plugin, viewer) || !InventoryAccess.ready(plugin, target)
                || ClaimDelivery.hasMarkedItem(plugin, viewer) || ClaimDelivery.hasMarkedItem(plugin, target)) return null;
        return target;
    }
    private boolean fresh(Player target, InvseeMenu.Holder holder) {
        for (int i = 0; i <= InvseeMenu.SLOT_OFFHAND; i++) if (!InvseeMenu.isSlotFresh(target, holder, i)) return false;
        return true;
    }
    private void reject(InvseeMenu.Holder holder, Player viewer, Player target) {
        InvseeMenu.refresh(holder.getInventory(), target, holder);
        if (messages != null) viewer.sendMessage(messages.get(viewer, "staff.invsee-stale", "player", target.getName()));
        plugin.getLogger().info("Rejected stale invsee edit staff=" + viewer.getUniqueId() + " target=" + target.getUniqueId());
    }
    private void commit(InvseeMenu.Holder holder, Player viewer, Player target, ItemStack[] before, ItemStack[] next,
                        ItemStack[] viewerBefore, ItemStack[] viewerNext, ItemStack cursor, ItemStack nextCursor) {
        // There is no scheduler boundary between validation, debit and credit.
        try {
            for (int i = 0; i < next.length; i++) InvseeMenu.setLiveSlot(target, i, cloneItem(next[i]));
            viewer.getInventory().setContents(viewerNext);
            viewer.setItemOnCursor(nextCursor);
            InvseeMenu.refresh(holder.getInventory(), target, holder);
            plugin.getLogger().info("Invsee edit id=" + java.util.UUID.randomUUID() + " staff=" + viewer.getUniqueId()
                    + " target=" + target.getUniqueId());
        } catch (RuntimeException error) {
            for (int i = 0; i < before.length; i++) InvseeMenu.setLiveSlot(target, i, cloneItem(before[i]));
            viewer.getInventory().setContents(viewerBefore); viewer.setItemOnCursor(cursor);
            InvseeMenu.refresh(holder.getInventory(), target, holder);
            throw error;
        }
    }
    private static ItemStack move(ItemStack source, ItemStack[] destination, int length) {
        if (empty(source)) return null;
        int left = source.getAmount();
        for (int i = 0; i < length && left > 0; i++) if (!empty(destination[i]) && destination[i].isSimilar(source)) {
            int quantity = Math.min(left, Math.max(0, source.getMaxStackSize() - destination[i].getAmount()));
            destination[i] = amount(source, destination[i].getAmount() + quantity); left -= quantity;
        }
        for (int i = 0; i < length && left > 0; i++) if (empty(destination[i])) {
            int quantity = Math.min(left, source.getMaxStackSize()); destination[i] = amount(source, quantity); left -= quantity;
        }
        return amount(source, left);
    }
    private static boolean fits(ItemStack item, int slot) {
        return empty(item) || !InvseeMenu.isArmorSlot(slot)
                || (item.getAmount() <= 1 && InvseeMenu.fitsArmorSlot(item.getType(), slot));
    }
    private static ItemStack amount(ItemStack item, int count) {
        if (count <= 0) return null; ItemStack result = item.clone(); result.setAmount(count); return result;
    }
    private static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }
    private static ItemStack cloneItem(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static ItemStack[] copy(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = cloneItem(source[i]);
        return result;
    }
}
