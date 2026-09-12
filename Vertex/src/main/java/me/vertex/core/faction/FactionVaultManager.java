package me.vertex.core.faction;

import me.vertex.core.essentials.EssentialsHook;
import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.luckperms.LuckPermsHook;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Persistent six-row faction vault protected by a renewable network-wide database lease. */
public final class FactionVaultManager implements Listener {
    private static final int SIZE = 54;

    private final Plugin plugin;
    private final FactionVaultStorage storage;
    private final FactionUpgradeManager upgrades;
    private final Messages messages;
    private final NamespacedKey lockedKey;
    private final Map<Integer, ItemStack[]> contents = new HashMap<>();
    private final Map<Integer, Session> viewers = new HashMap<>();
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);
    private volatile Set<Material> blockedMaterials = Set.of(Material.SPAWNER);
    private volatile Set<NamespacedKey> blockedPdc = Set.of();
    private volatile long leaseMillis = 20_000L;
    private static final long FORCE_SAFETY_MILLIS = 12_000L;
    private BukkitTask leaseTask;

    public FactionVaultManager(Plugin plugin, FactionVaultStorage storage,
            FactionUpgradeManager upgrades, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.upgrades = upgrades;
        this.messages = messages;
        this.lockedKey = new NamespacedKey(plugin, "faction_vault_locked");
    }

    public void load() throws SQLException {
        synchronized (this) {
            contents.clear();
            storage.loadAll().forEach((factionId, saved) -> contents.put(factionId, normalize(saved)));
        }
        reloadConfig();
        if (leaseTask != null) leaseTask.cancel();
        leaseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renewLeases, 100L, 100L);
    }

    public void reloadConfig() {
        leaseMillis = Math.max(10_000L,
                plugin.getConfig().getLong("faction-vault.lease-seconds", 20L) * 1_000L);
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String raw : plugin.getConfig().getStringList("faction-vault.blacklist.materials")) {
            Material value = Material.matchMaterial(raw);
            if (value != null) materials.add(value);
            else plugin.getLogger().warning("Unknown faction-vault blacklist material: " + raw);
        }
        blockedMaterials = Set.copyOf(materials);
        Set<NamespacedKey> keys = new HashSet<>();
        for (String raw : plugin.getConfig().getStringList("faction-vault.blacklist.pdc-keys")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key != null) keys.add(key);
            else plugin.getLogger().warning("Invalid faction-vault PDC key: " + raw);
        }
        blockedPdc = Set.copyOf(keys);
    }

    public void open(Player player) {
        FactionData faction = FactionsHook.getFaction(player).orElse(null);
        if (faction == null) {
            player.sendMessage(messages.get(player, "faction-vault.no-faction"));
            return;
        }
        FactionMember member = FactionsHook.service().member(player.getUniqueId());
        if (!FactionsHook.service().hasAction(member, "vault-use")) {
            player.sendMessage(messages.get(player, "faction-vault.no-permission"));
            return;
        }
        requestOpen(player, faction.id(), false, member.role(),
                FactionsHook.service().configuredActionDefaultFor(member.role(), "vault-use"));
    }

    /** Opens any faction vault for an authorized /fa administrator, while retaining the same write lock. */
    public void openAdmin(Player player, int factionId) {
        if (!player.hasPermission("vertex.fa.*") && !player.hasPermission("vertex.fa.vault")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        requestOpen(player, factionId, true, null, true);
    }

    /** Explicit high-risk override used only by /fa vault ... --force. */
    public CompletableFuture<Boolean> forceUnlock(int factionId) {
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            try { storage.revoke(factionId, System.currentTimeMillis() + FORCE_SAFETY_MILLIS); return true; }
            catch (SQLException error) {
                plugin.getLogger().log(Level.WARNING, "Could not force-release faction vault lock.", error);
                return false;
            }
        });
        result.thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!success) return;
            Session session;
            synchronized (this) { session = viewers.get(factionId); }
            if (session == null) return;
            Player viewer = Bukkit.getPlayer(session.viewer());
            if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                    && holder.factionId == factionId) {
                snapshot(holder);
                synchronized (this) { viewers.remove(factionId, session); }
                viewer.closeInventory();
                viewer.sendMessage(messages.get(viewer, "faction-vault.force-closed"));
            } else {
                synchronized (this) { viewers.remove(factionId, session); }
            }
        }));
        return result;
    }

    public long forceSafetyDelayTicks() { return FORCE_SAFETY_MILLIS / 50L + 20L; }

    private void requestOpen(Player player, int factionId, boolean admin,
            me.vertex.core.factions.FactionRole expectedRole, boolean defaultAllowed) {
        synchronized (this) {
            Session local = viewers.get(factionId);
            if (local != null) {
                Player viewer = Bukkit.getPlayer(local.viewer());
                player.sendMessage(messages.get(player, "faction-vault.busy",
                        "viewer", display(viewer, local.viewer())));
                return;
            }
        }
        UUID token = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String viewerName = display(player, player.getUniqueId());
        CompletableFuture.supplyAsync(() -> {
            try {
                FactionVaultStorage.AcquireResult access = storage.acquire(factionId,
                        player.getUniqueId(), viewerName, token, now, now + leaseMillis,
                        admin ? null : expectedRole, defaultAllowed);
                if (!access.authorized()) return new OpenResult(null, null, null, true);
                FactionVaultStorage.Lease lease = access.lease();
                ItemStack[] saved = lease.acquired() ? storage.load(factionId) : null;
                return new OpenResult(lease, saved, null, false);
            } catch (Exception error) {
                return new OpenResult(null, null, error, false);
            }
        }).thenAccept(result -> Bukkit.getScheduler().runTask(plugin,
                () -> finishOpen(player, factionId, token, admin, result)));
    }

    private void finishOpen(Player player, int factionId, UUID token, boolean admin, OpenResult result) {
        if (result.permissionDenied()) {
            if (player.isOnline()) player.sendMessage(messages.get(player, "faction-vault.no-permission"));
            return;
        }
        if (result.error() != null || result.lease() == null) {
            plugin.getLogger().log(Level.WARNING, "Could not acquire faction vault lease.", result.error());
            if (player.isOnline()) player.sendMessage(messages.get(player, "faction-vault.persist-failed"));
            return;
        }
        if (!result.lease().acquired()) {
            if (player.isOnline()) player.sendMessage(messages.get(player, "faction-vault.busy",
                    "viewer", result.lease().viewerName()));
            return;
        }
        FactionMember member = FactionsHook.service().member(player.getUniqueId());
        boolean validAdmin = admin && (player.hasPermission("vertex.fa.*")
                || player.hasPermission("vertex.fa.vault"));
        boolean validMember = !admin && member != null && member.factionId() == factionId
                && FactionsHook.service().hasAction(member, "vault-use");
        if (!player.isOnline() || FactionsHook.service().faction(factionId).isEmpty()
                || (!validAdmin && !validMember)) {
            queue(() -> storage.release(factionId, player.getUniqueId(), token));
            return;
        }
        synchronized (this) {
            if (viewers.containsKey(factionId)) {
                queue(() -> storage.release(factionId, player.getUniqueId(), token));
                player.sendMessage(messages.get(player, "faction-vault.busy", "viewer",
                        messages.getRaw(player, "faction-vault.viewer-changing")));
                return;
            }
            viewers.put(factionId, new Session(player.getUniqueId(), token));
            contents.put(factionId, normalize(result.contents()));
        }
        Holder holder = new Holder(factionId, rows(factionId), player.getUniqueId(), token);
        FactionData faction = FactionsHook.service().faction(factionId).orElseThrow();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                messages.getGui(player, admin ? "faction-vault.admin-title" : "faction-vault.title",
                        "faction", faction.tag()));
        holder.inventory = inventory;
        ItemStack[] saved = contents.getOrDefault(factionId, new ItemStack[SIZE]);
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, slot < holder.rows * 9 && saved[slot] != null
                    ? saved[slot].clone() : slot >= holder.rows * 9 ? lockedPane(player) : null);
        }
        player.openInventory(inventory);
    }

    private int rows(int factionId) {
        double bonus = upgrades.bonus(factionId, FactionUpgrade.VAULT_ROWS);
        return Math.max(1, Math.min(6, bonus <= 0D ? 1 : (int) Math.round(bonus)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length == 2 && isFactionCommand(parts[0]) && parts[1].equalsIgnoreCase("vault")) {
            event.setCancelled(true);
            open(event.getPlayer());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) return;
        if (!owns(holder, player)) {
            event.setCancelled(true);
            loseLease(player, holder);
            return;
        }
        int raw = event.getRawSlot();
        if (event.getAction() == InventoryAction.CLONE_STACK) {
            event.setCancelled(true);
            return;
        }
        if (raw >= holder.rows * 9 && raw < SIZE) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == player.getInventory()
                && event.isShiftClick() && blocked(event.getCurrentItem())) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "faction-vault.blocked"));
            return;
        }
        if (raw >= 0 && raw < SIZE) {
            ItemStack incoming = switch (event.getAction()) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> event.getHotbarButton() >= 0
                        ? player.getInventory().getItem(event.getHotbarButton()) : null;
                default -> event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                        ? player.getInventory().getItemInOffHand() : null;
            };
            if (blocked(incoming)) {
                event.setCancelled(true);
                player.sendMessage(messages.get(player, "faction-vault.blocked"));
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> snapshot(holder));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) return;
        boolean top = event.getRawSlots().stream().anyMatch(slot -> slot < SIZE);
        if (!top) return;
        if (!owns(holder, player)) {
            event.setCancelled(true);
            loseLease(player, holder);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= holder.rows * 9 && slot < SIZE)
                || blocked(event.getOldCursor())) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "faction-vault.blocked"));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> snapshot(holder));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        snapshot(holder);
        synchronized (this) {
            viewers.remove(holder.factionId, new Session(holder.viewer, holder.token));
        }
        queue(() -> storage.release(holder.factionId, holder.viewer, holder.token));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Map.Entry<Integer, Session> entry : sessions()) {
            if (!entry.getValue().viewer().equals(event.getPlayer().getUniqueId())) continue;
            synchronized (this) {
                viewers.remove(entry.getKey(), entry.getValue());
            }
            queue(() -> storage.release(entry.getKey(), entry.getValue().viewer(), entry.getValue().token()));
        }
    }

    @EventHandler
    public void onDisband(FactionLifecycleEvent event) {
        if (event.action() != FactionLifecycleEvent.Action.DISBAND) return;
        Session session;
        synchronized (this) {
            contents.remove(event.faction().id());
            session = viewers.remove(event.faction().id());
        }
        if (session != null) {
            Player viewer = Bukkit.getPlayer(session.viewer());
            if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                    && holder.factionId == event.faction().id()) viewer.closeInventory();
        }
        queue(() -> storage.delete(event.faction().id()));
    }

    private synchronized void snapshot(Holder holder) {
        if (holder.inventory == null
                || !viewers.getOrDefault(holder.factionId, Session.NONE)
                        .equals(new Session(holder.viewer, holder.token))
                || FactionsHook.service().faction(holder.factionId).isEmpty()) return;
        ItemStack[] previous = contents.getOrDefault(holder.factionId, new ItemStack[SIZE]);
        ItemStack[] next = Arrays.copyOf(previous, SIZE);
        for (int slot = 0; slot < holder.rows * 9; slot++) {
            ItemStack item = holder.inventory.getItem(slot);
            next[slot] = item == null ? null : item.clone();
        }
        contents.put(holder.factionId, next);
        queue(() -> storage.save(holder.factionId, cloneContents(next)));
    }

    private void renewLeases() {
        long until = System.currentTimeMillis() + leaseMillis;
        for (Map.Entry<Integer, Session> entry : sessions()) {
            int factionId = entry.getKey();
            Session session = entry.getValue();
            CompletableFuture.supplyAsync(() -> {
                try {
                    return storage.renew(factionId, session.viewer(), session.token(), until);
                } catch (SQLException error) {
                    plugin.getLogger().log(Level.WARNING, "Could not renew faction vault lease.", error);
                    return false;
                }
            }).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (success) return;
                Player viewer = Bukkit.getPlayer(session.viewer());
                synchronized (this) {
                    if (!viewers.getOrDefault(factionId, Session.NONE).equals(session)) return;
                }
                if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                        && holder.factionId == factionId && holder.token.equals(session.token())) {
                    snapshot(holder);
                    synchronized (this) { viewers.remove(factionId, session); }
                    viewer.closeInventory();
                    viewer.sendMessage(messages.get(viewer, "faction-vault.lease-lost"));
                } else synchronized (this) { viewers.remove(factionId, session); }
            }));
        }
    }

    private boolean owns(Holder holder, Player player) {
        synchronized (this) {
            return holder.viewer.equals(player.getUniqueId())
                    && viewers.getOrDefault(holder.factionId, Session.NONE)
                            .equals(new Session(holder.viewer, holder.token));
        }
    }

    private void loseLease(Player player, Holder holder) {
        synchronized (this) {
            viewers.remove(holder.factionId, new Session(holder.viewer, holder.token));
        }
        player.closeInventory();
        player.sendMessage(messages.get(player, "faction-vault.lease-lost"));
    }

    private ItemStack lockedPane(Player player) {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(player, "faction-vault.locked")
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(lockedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean blocked(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        if (blockedMaterials.contains(item.getType())) return true;
        if (!item.hasItemMeta()) return false;
        for (NamespacedKey key : blockedPdc) {
            if (item.getItemMeta().getPersistentDataContainer().has(key)) return true;
        }
        return false;
    }

    private static ItemStack[] normalize(ItemStack[] source) {
        if (source == null) return new ItemStack[SIZE];
        return Arrays.copyOf(cloneContents(source), SIZE);
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    private synchronized List<Map.Entry<Integer, Session>> sessions() {
        return List.copyOf(viewers.entrySet());
    }

    private String display(Player viewer, UUID uuid) {
        if (viewer == null) return uuid.toString();
        String prefix = LuckPermsHook.getPrefix(viewer);
        return MessageFormatter.plain((prefix == null ? "" : prefix) + EssentialsHook.resolveName(viewer));
    }

    private boolean isFactionCommand(String raw) {
        String value = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(value));
    }

    private void queue(SqlAction action) {
        synchronized (writeLock) {
            writeTail = writeTail.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    action.run();
                } catch (SQLException error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }).exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Faction vault write failed.", error);
                return null;
            });
        }
    }

    public void shutdown() {
        if (leaseTask != null) leaseTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) snapshot(holder);
        }
        for (Map.Entry<Integer, Session> entry : sessions()) {
            queue(() -> storage.release(entry.getKey(), entry.getValue().viewer(), entry.getValue().token()));
        }
        synchronized (this) {
            viewers.clear();
        }
        awaitWrites();
    }

    /** Waits for persisted vault work without stopping leases or closing live sessions. */
    public void awaitWrites() {
        CompletableFuture<Void> tail;
        synchronized (writeLock) {
            tail = writeTail;
        }
        try {
            tail.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for faction vault writes.", error);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private record Session(UUID viewer, UUID token) {
        private static final Session NONE = new Session(new UUID(0L, 0L), new UUID(0L, 0L));
    }

    private record OpenResult(FactionVaultStorage.Lease lease, ItemStack[] contents,
                              Throwable error, boolean permissionDenied) {}

    private static final class Holder implements InventoryHolder {
        private final int factionId;
        private final int rows;
        private final UUID viewer;
        private final UUID token;
        private Inventory inventory;

        private Holder(int factionId, int rows, UUID viewer, UUID token) {
            this.factionId = factionId;
            this.rows = rows;
            this.viewer = viewer;
            this.token = token;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
