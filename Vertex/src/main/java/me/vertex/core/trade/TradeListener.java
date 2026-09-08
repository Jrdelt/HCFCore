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
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Strict click/drag boundary for a {@link TradeSession}, plus vanilla anvil values. */
public final class TradeListener implements Listener {
    private final Plugin plugin; private final TradeManager manager; private final Messages messages;
    private final Map<UUID, TradeAnvilHolder> activeAnvils = new ConcurrentHashMap<>();
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
            if (session == null || !session.id.equals(holder.sessionId())) { event.setCancelled(true); return; }
            boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < TradeMenu.SIZE;
            if (!top) { if (event.isShiftClick() || event.getAction().name().contains("DROP")) event.setCancelled(true); return; }
            int slot = event.getRawSlot();
            ItemStack offered = event.getCurrentItem();
            if (event.isRightClick() && TradeMenu.isTradeSlot(slot) && isPeekable(offered)) { event.setCancelled(true); openPeek(player, session, offered); return; }
            if (slot == (session.isRequester(player.getUniqueId()) ? TradeMenu.REQUESTER_LOCK : TradeMenu.TARGET_LOCK)) { event.setCancelled(true); lockOrComplete(player, session); return; }
            if (slot == (session.isRequester(player.getUniqueId()) ? TradeMenu.REQUESTER_MONEY : TradeMenu.TARGET_MONEY) && manager.moneyEnabled()) { event.setCancelled(true); openAnvil(player, session, TradeValueType.MONEY); return; }
            if (slot == (session.isRequester(player.getUniqueId()) ? TradeMenu.REQUESTER_XP : TradeMenu.TARGET_XP) && manager.experienceEnabled()) { event.setCancelled(true); openAnvil(player, session, TradeValueType.EXPERIENCE); return; }
            if (!TradeMenu.ownTradeSlot(session, player.getUniqueId(), slot) || session.locked(player.getUniqueId()) || event.isShiftClick() || event.getAction().name().contains("DROP")) { event.setCancelled(true); return; }
            ItemStack cursor = event.getCursor(); if (cursor != null && !cursor.isEmpty() && !manager.canTradeItem(player, cursor)) { event.setCancelled(true); player.sendMessage(messages.get(player, "trade.item-blocked")); return; }
            Bukkit.getScheduler().runTask(plugin, () -> manager.touch(session));
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof TradeAnvilHolder anvil && event.getRawSlot() == 2) {
            event.setCancelled(true); handleAnvilResult(player, anvil, (AnvilInventory) event.getView().getTopInventory());
        }
        if (event.getView().getTopInventory().getHolder() instanceof TradeMenu.PeekHolder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getView().getTopInventory().getHolder() instanceof TradeMenu.Holder)) return;
        TradeSession session = manager.session(player.getUniqueId()); if (session == null) { event.setCancelled(true); return; }
        for (int raw : event.getRawSlots()) if (raw < TradeMenu.SIZE && (!TradeMenu.ownTradeSlot(session, player.getUniqueId(), raw) || session.locked(player.getUniqueId()))) { event.setCancelled(true); return; }
        if (event.getCursor() != null && !event.getCursor().isEmpty() && !manager.canTradeItem(player, event.getCursor())) { event.setCancelled(true); player.sendMessage(messages.get(player, "trade.item-blocked")); return; }
        Bukkit.getScheduler().runTask(plugin, () -> manager.touch(session));
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof TradeMenu.Holder) { if (!manager.isProgrammaticClose(player.getUniqueId())) manager.cancel(manager.session(player.getUniqueId()), "trade-cancelled"); return; }
        if (event.getInventory().getHolder() instanceof TradeAnvilHolder anvil) { activeAnvils.remove(player.getUniqueId()); TradeSession session = manager.session(player.getUniqueId()); if (session != null && session.id.equals(anvil.sessionId()) && player.isOnline()) Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(session.inventory)); }
        if (event.getInventory().getHolder() instanceof TradeMenu.PeekHolder holder && activePeeks.remove(player.getUniqueId(), holder.sessionId())) { TradeSession session = manager.session(player.getUniqueId()); if (session != null && player.isOnline()) Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(session.inventory)); }
    }
    @EventHandler public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeAnvilHolder)) return;
        ItemStack first = event.getInventory().getFirstItem(); if (first == null) return;
        event.setResult(first.clone()); event.getInventory().setRepairCost(0); event.getInventory().setMaximumRepairCost(0);
    }
    private void lockOrComplete(Player player, TradeSession session) {
        boolean finalAccept = session.bothLocked();
        TradeManager.Result result = finalAccept ? manager.complete(player, session) : manager.lock(player, session);
        switch (result) { case OK -> { if (!finalAccept) player.sendMessage(messages.get(player, "trade.locked")); } case CANNOT_AFFORD -> player.sendMessage(messages.get(player, "trade.cannot-afford")); case NO_ECONOMY -> player.sendMessage(messages.get(player, "trade.no-economy")); case FULL -> player.sendMessage(messages.get(player, "trade.inventory-full")); case NOT_FIRST_LOCKED -> player.sendMessage(messages.get(player, "trade.wait-final")); default -> { } }
    }
    private void openAnvil(Player player, TradeSession session, TradeValueType type) {
        TradeAnvilHolder holder = new TradeAnvilHolder(session.id, type); Inventory anvil = Bukkit.createInventory(holder, InventoryType.ANVIL, messages.get(player, "trade.anvil-title"));
        ItemStack input = new ItemStack(type == TradeValueType.MONEY ? manager.currencyMaterial() : manager.experienceMaterial()); ItemMeta meta = input.getItemMeta(); meta.displayName(null); meta.lore(null); input.setItemMeta(meta); anvil.setItem(0, input); activeAnvils.put(player.getUniqueId(), holder); player.openInventory(anvil);
    }
    private void handleAnvilResult(Player player, TradeAnvilHolder anvil, AnvilInventory inventory) {
        TradeSession session = manager.session(player.getUniqueId()); if (session == null || !session.id.equals(anvil.sessionId())) return;
        TradeManager.Result result = manager.setValue(player, session, anvil.type(), inventory.getRenameText());
        if (result != TradeManager.Result.OK) { player.sendMessage(messages.get(player, result == TradeManager.Result.CANNOT_AFFORD ? "trade.cannot-afford" : "trade.invalid-amount")); return; }
        activeAnvils.remove(player.getUniqueId()); player.closeInventory();
    }
    private static boolean isPeekable(ItemStack item) { return item != null && (item.getType() == Material.BARREL || item.getType().name().endsWith("SHULKER_BOX")) && item.getItemMeta() instanceof BlockStateMeta; }
    private void openPeek(Player player, TradeSession session, ItemStack item) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta(); if (!(meta.getBlockState() instanceof Container container)) return;
        ItemStack[] contents = container.getInventory().getContents(); int size = contents.length <= 27 ? 27 : 54;
        Inventory view = Bukkit.createInventory(new TradeMenu.PeekHolder(session.id), size, messages.get(player, "trade.peek-title"));
        for (int i=0;i<Math.min(contents.length,size);i++) if(contents[i]!=null) view.setItem(i,contents[i].clone()); activePeeks.put(player.getUniqueId(), session.id); player.openInventory(view);
    }
}
