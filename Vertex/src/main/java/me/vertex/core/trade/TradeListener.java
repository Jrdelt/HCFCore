package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Strict click/drag boundary for an item-only {@link TradeSession}. */
public final class TradeListener implements Listener {
    private final Plugin plugin; private final TradeManager manager; private final Messages messages;
    private final Map<UUID, UUID> activePeeks = new ConcurrentHashMap<>();
    public TradeListener(Plugin plugin, TradeManager manager, Messages messages) { this.plugin=plugin; this.manager=manager; this.messages=messages; }

    @EventHandler public void onJoin(PlayerJoinEvent e) { manager.loadPlayer(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { manager.cancel(manager.session(e.getPlayer().getUniqueId()), "trade-cancelled"); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { manager.sweep(); }
    @EventHandler public void onGamemode(PlayerGameModeChangeEvent e) { manager.sweep(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenu.Holder holder) {
            TradeSession session = manager.session(player.getUniqueId());
            if (!manager.canEditEscrow() || session == null || !session.id.equals(holder.sessionId()) || session.finishing) { event.setCancelled(true); return; }
            // These actions can modify other slots even when the clicked slot
            // is in the player's bottom inventory.
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                    || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || event.getAction() == InventoryAction.UNKNOWN) { event.setCancelled(true); return; }
            boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < TradeMenu.SIZE;
            if (!top) { if (event.isShiftClick() || event.getAction().name().contains("DROP")) event.setCancelled(true); return; }
            int slot = event.getRawSlot();
            ItemStack offered = event.getCurrentItem();
            if (event.getClick() == ClickType.RIGHT && TradeMenu.isTradeSlot(slot) && isPeekable(offered)) { event.setCancelled(true); openPeek(player, session, offered); return; }
            if (slot == (session.isRequester(player.getUniqueId()) ? TradeMenu.REQUESTER_LOCK : TradeMenu.TARGET_LOCK)) { event.setCancelled(true); lockOrComplete(player, session); return; }
            if (!TradeMenu.ownTradeSlot(session, player.getUniqueId(), slot) || session.finishing || session.locked(player.getUniqueId()) || event.isShiftClick() || event.getAction().name().contains("DROP")) { event.setCancelled(true); return; }
            ItemStack incoming = event.getClick() == ClickType.SWAP_OFFHAND
                    ? player.getInventory().getItemInOffHand()
                    : event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : event.getCursor();
            if (incoming != null && !incoming.isEmpty() && !manager.canTradeItem(player, incoming)) { event.setCancelled(true); player.sendMessage(messages.get(player, "trade.item-blocked")); return; }
            Bukkit.getScheduler().runTask(plugin, () -> manager.touch(session));
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenu.PeekHolder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenu.PeekHolder) { event.setCancelled(true); return; }
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getView().getTopInventory().getHolder() instanceof TradeMenu.Holder holder)) return;
        TradeSession session = manager.session(player.getUniqueId()); if (!manager.canEditEscrow() || session == null || !session.id.equals(holder.sessionId()) || session.finishing) { event.setCancelled(true); return; }
        for (int raw : event.getRawSlots()) if (raw < TradeMenu.SIZE && (!TradeMenu.ownTradeSlot(session, player.getUniqueId(), raw) || session.locked(player.getUniqueId()))) { event.setCancelled(true); return; }
        if (!event.getOldCursor().isEmpty() && !manager.canTradeItem(player, event.getOldCursor())) { event.setCancelled(true); player.sendMessage(messages.get(player, "trade.item-blocked")); return; }
        Bukkit.getScheduler().runTask(plugin, () -> manager.touch(session));
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof TradeMenu.Holder holder) {
            boolean preview = holder.sessionId().equals(activePeeks.get(player.getUniqueId()));
            if (!manager.isProgrammaticClose(player.getUniqueId()) && !preview) manager.cancel(manager.session(player.getUniqueId()), "trade-cancelled");
            return;
        }
        if (event.getInventory().getHolder() instanceof TradeMenu.PeekHolder holder && activePeeks.remove(player.getUniqueId(), holder.sessionId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                TradeSession session = manager.session(player.getUniqueId());
                if (session != null && session.id.equals(holder.sessionId()) && !session.finishing && player.isOnline()) player.openInventory(session.inventory);
            });
        }
    }
    private void lockOrComplete(Player player, TradeSession session) {
        boolean finalAccept = session.bothLocked();
        TradeManager.Result result = finalAccept ? manager.complete(player, session) : manager.lock(player, session);
        switch (result) { case OK -> { if (!finalAccept) player.sendMessage(messages.get(player, "trade.locked")); } case FULL -> player.sendMessage(messages.get(player, "trade.inventory-full")); case NOT_FIRST_LOCKED -> player.sendMessage(messages.get(player, "trade.wait-final")); default -> { } }
    }
    private static boolean isPeekable(ItemStack item) { return item != null && (item.getType() == Material.BARREL || item.getType().name().endsWith("SHULKER_BOX")) && item.getItemMeta() instanceof BlockStateMeta; }
    private void openPeek(Player player, TradeSession session, ItemStack item) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta(); if (!(meta.getBlockState() instanceof Container container)) return;
        ItemStack[] contents = container.getInventory().getContents(); int size = contents.length <= 27 ? 27 : 54;
        Inventory view = Bukkit.createInventory(new TradeMenu.PeekHolder(session.id), size, messages.getGui(player, "trade.peek-title"));
        for (int i=0;i<Math.min(contents.length,size);i++) if(contents[i]!=null) view.setItem(i,contents[i].clone());
        // Opening/closing an inventory inside InventoryClickEvent is unsafe.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || manager.session(player.getUniqueId()) != session || session.finishing
                    || !(player.getOpenInventory().getTopInventory().getHolder() instanceof TradeMenu.Holder holder)
                    || !session.id.equals(holder.sessionId())) return;
            activePeeks.put(player.getUniqueId(), session.id);
            if (player.openInventory(view) == null) activePeeks.remove(player.getUniqueId(), session.id);
        });
    }
}
