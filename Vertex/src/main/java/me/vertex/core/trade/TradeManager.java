package me.vertex.core.trade;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import me.vertex.core.storage.ClaimDelivery;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Owns requests, sessions, escrow, cooldowns and all trade safety rules. */
public final class TradeManager {
    public enum Result { OK, DISABLED, SELF, BUSY, TOO_FAR, TARGET_OFF, BLOCKED, NO_REQUEST, EXPIRED, NOT_REQUESTED, COOLDOWN, LOCKED, NOT_FIRST_LOCKED, FULL }
    private record Request(UUID sender, UUID target, long expires) { }
    private final Plugin plugin; private final TradeStorage storage; private final AnnouncementPreferenceManager preferences; private final Messages messages; private final File file;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> programmaticClose = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<Void>> escrowChains = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Set<UUID> claimDeliveries = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingPayoutsInProgress = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final AtomicBoolean renewingOwnership = new AtomicBoolean();
    private long lastOwnershipRenew;
    private volatile boolean enabled;
    private volatile double maxDistance; private volatile long requestTimeout, idleTimeout, cooldown;
    private volatile Material divider = Material.BLACK_STAINED_GLASS_PANE, filler = Material.GRAY_STAINED_GLASS_PANE, confirm = Material.LIME_DYE, locked = Material.GRAY_DYE;
    private volatile Set<String> worlds = Set.of(); private volatile Set<GameMode> gameModes = Set.of(); private volatile Set<Material> blockedItems = Set.of();

    public TradeManager(Plugin plugin, TradeStorage storage, AnnouncementPreferenceManager preferences, Messages messages) { this.plugin = plugin; this.storage = storage; this.preferences = preferences; this.messages = messages; this.file = new File(plugin.getDataFolder(), "traders.yml"); }
    public void load() {
        if (!file.exists()) plugin.saveResource("traders.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        enabled = c.getBoolean("enabled", true); maxDistance = positive(c, "trade.max-distance", 10); requestTimeout = seconds(c, "trade.request-timeout-seconds", 30); idleTimeout = seconds(c, "trade.idle-timeout-seconds", 60); cooldown = seconds(c, "trade.cooldown-seconds", 15);
        // Trades are item-only. Legacy money/XP columns remain solely so old
        // escrow can be returned after an upgrade.
        divider = material(c, "gui.divider-material", Material.BLACK_STAINED_GLASS_PANE); filler = material(c, "gui.filler-material", Material.GRAY_STAINED_GLASS_PANE); confirm = material(c, "gui.confirm-item", Material.LIME_DYE); locked = material(c, "gui.locked-item", Material.GRAY_DYE);
        worlds = lower(c.getStringList("blacklist.worlds")); blockedItems = materials(c.getStringList("blacklist.items")); Set<GameMode> modes = new HashSet<>(); for (String raw : c.getStringList("blacklist.gamemodes")) try { modes.add(GameMode.valueOf(raw.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException e) { warn("blacklist.gamemodes", raw); } gameModes = Set.copyOf(modes);
    }
    private double positive(YamlConfiguration c, String key, double fallback) { double v = c.getDouble(key, fallback); if (!Double.isFinite(v) || v < 0) { warn(key, "using " + fallback); return fallback; } return v; }
    private long seconds(YamlConfiguration c, String key, long fallback) { return Math.round(positive(c, key, fallback) * 1000L); }
    private Material material(YamlConfiguration c, String key, Material fallback) { Material m = Material.matchMaterial(c.getString(key, fallback.name())); if (m == null) { warn(key, "using " + fallback); return fallback; } return m; }
    private Set<Material> materials(Collection<String> values) { Set<Material> out = new HashSet<>(); for (String raw : values) { Material m = Material.matchMaterial(raw); if (m == null) warn("blacklist.items", raw); else out.add(m); } return Set.copyOf(out); }
    private Set<String> lower(Collection<String> values) { return values.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private void warn(String key, String value) { plugin.getLogger().warning("Invalid traders.yml " + key + " (" + value + "); using a safe fallback."); }
    public boolean isEnabled() { return enabled && canEditEscrow(); }
    public boolean canEditEscrow() { return !shuttingDown.get() && storage != null && storage.ownershipHealthy(); }
    public Material dividerMaterial() { return divider; } public Material fillerMaterial() { return filler; } public Material confirmMaterial() { return confirm; } public Material lockedMaterial() { return locked; }
    public double maxDistance() { return maxDistance; }
    public boolean isProgrammaticClose(UUID id) { return programmaticClose.remove(id); }
    public boolean isInSession(UUID id) { return sessions.containsKey(id); }
    public TradeSession session(UUID id) { return sessions.get(id); }

    public Result request(Player sender, Player target) {
        if (!isEnabled()) return Result.DISABLED; if (sender.equals(target)) return Result.SELF; if (busy(sender.getUniqueId()) || busy(target.getUniqueId())) return Result.BUSY; if (onCooldown(sender.getUniqueId()) || onCooldown(target.getUniqueId())) return Result.COOLDOWN;
        if (!allowed(sender) || !allowed(target)) return Result.BLOCKED; if (!isAccepting(target.getUniqueId())) return Result.TARGET_OFF; if (!near(sender, target)) return Result.TOO_FAR;
        Request request = new Request(sender.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + requestTimeout); requests.put(target.getUniqueId(), request); return Result.OK;
    }
    public Result accept(Player target, Player sender) {
        if (!isEnabled()) return Result.DISABLED;
        Request request = requests.get(target.getUniqueId()); if (request == null) return Result.NO_REQUEST; if (!request.sender.equals(sender.getUniqueId())) return Result.NOT_REQUESTED;
        if (request.expires < System.currentTimeMillis()) { endRequest(request); return Result.EXPIRED; }
        if (!near(sender, target)) return Result.TOO_FAR; if (!allowed(sender) || !allowed(target)) return Result.BLOCKED;
        requests.remove(target.getUniqueId()); TradeSession session = new TradeSession(sender.getUniqueId(), target.getUniqueId()); sessions.put(sender.getUniqueId(), session); sessions.put(target.getUniqueId(), session);
        org.bukkit.inventory.Inventory inventory = TradeMenu.open(session, sender, target, this, messages); sender.openInventory(inventory); target.openInventory(inventory); persistEscrow(session); return Result.OK;
    }
    public boolean isAccepting(UUID uuid) { return preferences == null || preferences.isEnabled(uuid, AnnouncementCategory.TRADE_REQUESTS); }

    public void loadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        track(CompletableFuture.runAsync(() -> {
            try {
                applyClaims(playerId);
                processPendingPayouts(playerId);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load pending trade deliveries", e);
            }
        }));
    }
    public boolean allowed(Player player) { if (player.hasPermission("vertex.trade.staff.bypassblacklist")) return true; return !worlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT)) && !gameModes.contains(player.getGameMode()); }
    public boolean canTradeItem(Player player, ItemStack item) { return player.hasPermission("vertex.trade.staff.bypassblacklist") || item == null || !blockedItems.contains(item.getType()); }
    private boolean near(Player a, Player b) { return a.hasPermission("vertex.trade.staff.bypassdistance") || a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= maxDistance * maxDistance; }
    private boolean busy(UUID id) { return sessions.containsKey(id) || requests.containsKey(id) || requests.values().stream().anyMatch(r -> r.sender.equals(id)); }
    private boolean onCooldown(UUID id) { return cooldowns.getOrDefault(id, 0L) > System.currentTimeMillis(); }
    private void endRequest(Request request) { requests.remove(request.target, request); cooldowns.put(request.sender, System.currentTimeMillis() + cooldown); cooldowns.put(request.target, System.currentTimeMillis() + cooldown); }
    public Result lock(Player player, TradeSession session) {
        if (!canEditEscrow()) return Result.DISABLED;
        if (session == null || session.locked(player.getUniqueId())) return Result.LOCKED;
        session.lock(player.getUniqueId()); touch(session); return Result.OK;
    }
    public Result complete(Player actor, TradeSession session) {
        if (!canEditEscrow()) return Result.DISABLED;
        if (session == null || !session.bothLocked() || !actor.getUniqueId().equals(session.firstLocked)) return Result.NOT_FIRST_LOCKED; if (session.finishing) return Result.LOCKED;
        Player requester = Bukkit.getPlayer(session.requester); Player target = Bukkit.getPlayer(session.target); if (requester == null || target == null || !near(requester, target) || !allowed(requester) || !allowed(target) || !canFit(requester, session.itemsFor(session.target)) || !canFit(target, session.itemsFor(session.requester))) { cancel(session, "trade-cancelled"); return Result.FULL; }
        session.finishing = true;
        settleEscrowAsync(session, "COMPLETED", requester, target, true, null);
        return Result.OK;
    }
    public void cancel(TradeSession session, String messageKey) {
        if (session == null || session.finishing) return; session.finishing = true; Player requester = Bukkit.getPlayer(session.requester); Player target = Bukkit.getPlayer(session.target);
        settleEscrowAsync(session, "CANCELLED", requester, target, false, messageKey);
    }
    private void finish(TradeSession s, String status, Player requester, Player target) {
        sessions.remove(s.requester, s); sessions.remove(s.target, s); cooldowns.put(s.requester, System.currentTimeMillis() + cooldown); cooldowns.put(s.target, System.currentTimeMillis() + cooldown);
        closeTradeView(s, requester); closeTradeView(s, target);
        if (requester != null && "COMPLETED".equals(status)) requester.sendMessage(messages.get(requester, "trade.complete", "player", target == null ? "player" : target.getName()));
        if (target != null && "COMPLETED".equals(status)) target.sendMessage(messages.get(target, "trade.complete", "player", requester == null ? "player" : requester.getName()));
    }
    private void closeTradeView(TradeSession session, Player player) {
        if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof TradeMenu.PeekHolder peek
                && session.id.equals(peek.sessionId())) {
            player.closeInventory();
            return;
        }
        if (player == null || !(player.getOpenInventory().getTopInventory().getHolder() instanceof TradeMenu.Holder holder)
                || !session.id.equals(holder.sessionId())) return;
        programmaticClose.add(player.getUniqueId()); player.closeInventory();
    }
    public void touch(TradeSession session) { if (session == null || session.finishing || sessions.get(session.requester) != session) return; session.lastActivity = System.currentTimeMillis(); Player a = Bukkit.getPlayer(session.requester), b = Bukkit.getPlayer(session.target); TradeMenu.render(session, Bukkit.getOfflinePlayer(session.requester), Bukkit.getOfflinePlayer(session.target), this, messages); if (a != null && b != null) { Player other = a; other.playSound(other.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, .2f, 1.7f); } persistEscrow(session); }
    public void sweep() {
        if (shuttingDown.get()) return;
        long monotonic = System.nanoTime();
        if ((lastOwnershipRenew == 0 || monotonic - lastOwnershipRenew >= TimeUnit.SECONDS.toNanos(10))
                && renewingOwnership.compareAndSet(false, true)) {
            lastOwnershipRenew = monotonic;
            track(CompletableFuture.runAsync(() -> {
                try { storage.renewOwnership(); }
                catch (Exception error) { plugin.getLogger().log(Level.SEVERE,
                        "Trade ownership renewal failed; escrow edits are blocked until ownership is healthy. "
                                + "An expired/fenced owner requires a restart.", error); }
                finally { renewingOwnership.set(false); }
            }));
        }
        if (!canEditEscrow()) return;
        long now = System.currentTimeMillis();
        for (Request request : List.copyOf(requests.values())) if (request.expires <= now) endRequest(request);
        for (TradeSession session : new HashSet<>(sessions.values())) {
            Player a = Bukkit.getPlayer(session.requester), b = Bukkit.getPlayer(session.target);
            if (a == null || b == null || !allowed(a) || !allowed(b) || !near(a, b)) cancel(session, "trade-cancelled");
            else if (now - session.lastActivity >= idleTimeout) cancel(session, "trade-idle-cancelled");
        }
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
    }
    public void shutdown() {
        shuttingDown.set(true);
        for (TradeSession session : new HashSet<>(sessions.values())) {
            cancel(session, "trade-cancelled");
        }
        awaitWrites();
        for (String key : List.copyOf(pendingPayoutsInProgress)) {
            try {
                storage.releasePendingPayout(key);
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not release Trade payout " + key + " during shutdown", error);
            } finally {
                pendingPayoutsInProgress.remove(key);
            }
        }
        awaitWrites();
        if (pendingWrites.isEmpty()) {
            try { storage.releaseOwnership(); }
            catch (Exception error) { plugin.getLogger().log(Level.SEVERE, "Could not release trade ownership", error); }
        }
    }
    private void persistEscrow(TradeSession session) {
        TradeSnapshot snapshot = TradeSnapshot.capture(session);
        CompletableFuture<Void> next = escrowChains.compute(snapshot.sessionId(), (id, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((v, e) -> null))
                        .thenRunAsync(() -> {
                            try { storage.replaceEscrow(snapshot); }
                            catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed to persist trade escrow " + snapshot.sessionId(), e); }
                        }));
        track(next);
    }
    private void settleEscrowAsync(TradeSession session, String status, Player requesterPlayer,
            Player targetPlayer, boolean completed, String cancellationMessageKey) {
        TradeSnapshot snapshot = TradeSnapshot.capture(session);
        String requesterName = requesterPlayer == null ? session.requester.toString() : requesterPlayer.getName();
        String targetName = targetPlayer == null ? session.target.toString() : targetPlayer.getName();
        CompletableFuture<Void> settlement = escrowChains.compute(snapshot.sessionId(), (id, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((v, e) -> null))
                        .thenRunAsync(() -> {
                            try { storage.settleEscrow(snapshot, requesterName, targetName, status, completed); }
                            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                        }));
        track(settlement);
        settlement.whenComplete((ignored, error) -> {
            escrowChains.remove(snapshot.sessionId(), settlement);
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player requester = Bukkit.getPlayer(session.requester);
                Player target = Bukkit.getPlayer(session.target);
                if (error != null) {
                    session.finishing = false;
                    plugin.getLogger().log(Level.SEVERE, "Failed to durably "
                            + status.toLowerCase(Locale.ROOT) + " trade " + snapshot.sessionId(), error);
                    if (requester != null) requester.sendMessage(messages.get(requester, "trade.settle-failed"));
                    if (target != null) target.sendMessage(messages.get(target, "trade.settle-failed"));
                    return;
                }
                finish(session, status, requester, target);
                if (requester != null) applyClaims(session.requester);
                if (target != null) applyClaims(session.target);
                if (!completed && cancellationMessageKey != null) {
                    if (requester != null) requester.sendMessage(messages.get(requester,
                            "trade." + cancellationMessageKey, "player", target == null ? "player" : target.getName()));
                    if (target != null) target.sendMessage(messages.get(target,
                            "trade." + cancellationMessageKey, "player", requester == null ? "player" : requester.getName()));
                }
            });
        });
    }
    public void restoreEscrow() {
        track(CompletableFuture.supplyAsync(() -> {
            try {
                List<TradeStorage.PendingPayout> uncertain = storage.loadUncertainPayouts();
                if (!uncertain.isEmpty()) plugin.getLogger().severe("Trade has " + uncertain.size()
                        + " uncertain legacy payout(s). Inspect with /trade payouts before retrying them.");
                int unowned = storage.unownedEscrowCount();
                if (unowned > 0) plugin.getLogger().severe("Trade has " + unowned
                        + " legacy escrow session(s) without shard ownership. They were preserved, not refunded. "
                        + "See docs/trading.md before performing an offline ownership migration.");
                return storage.loadRecoverableEscrow();
            }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).thenAccept(escrows -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (TradeEscrow escrow : escrows) {
                track(CompletableFuture.runAsync(() -> {
                    try { storage.recoverEscrow(escrow);
                        // JDBC recovery is allowed off-thread; Bukkit player
                        // lookup is not. Schedule the lookup itself rather
                        // than merely scheduling a later use of its result.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player owner = Bukkit.getPlayer(escrow.owner());
                            if (owner != null) loadPlayer(owner);
                        });
                    } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed to restore trade escrow " + escrow.sessionId(), e); }
                }));
            }
        })).exceptionally(error -> { plugin.getLogger().log(Level.SEVERE, "Failed to load trade escrow", error); return null; }));
    }
    /** Reserves durable rows, then reconciles/acknowledges them after inventory delivery. */
    private void applyClaims(UUID playerId) {
        if (!claimDeliveries.add(playerId)) return;
        CompletableFuture<Void> load = CompletableFuture.supplyAsync(() -> {
            try {
                List<TradeStorage.ClaimReservation> reservations = new ArrayList<>(
                        storage.loadDeliveringClaims(playerId));
                TradeStorage.ClaimReservation fresh = storage.reserveClaims(playerId);
                if (!fresh.claims().isEmpty()) reservations.add(fresh);
                return new TradeClaimBatch(reservations, fresh.claims().isEmpty() ? null : fresh.token());
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).thenAccept(batch ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    releaseFreshClaim(playerId, batch).whenComplete((ignored, error) ->
                            claimDeliveries.remove(playerId));
                    return;
                }
                if (batch.reservations().isEmpty()) {
                    ClaimDelivery.clearSourceMarkers(player, plugin, "trade");
                    claimDeliveries.remove(playerId);
                }
                else deliverTradeReservation(player, batch, 0);
            })
        );
        track(load.whenComplete((ignored, error) -> {
            if (error == null) return;
            plugin.getLogger().log(Level.WARNING, "Failed to claim returned trade items", error);
            claimDeliveries.remove(playerId);
        }));
    }

    private record TradeClaimBatch(List<TradeStorage.ClaimReservation> reservations, String freshToken) { }

    private void deliverTradeReservation(Player player, TradeClaimBatch batch, int index) {
        List<TradeStorage.ClaimReservation> reservations = batch.reservations();
        if (index >= reservations.size() || !player.isOnline()) {
            claimDeliveries.remove(player.getUniqueId());
            return;
        }
        TradeStorage.ClaimReservation reservation = reservations.get(index);
        List<ClaimDelivery.TaggedItem> expected = new ArrayList<>();
        for (TradeStorage.ClaimRow row : reservation.claims()) expected.add(ClaimDelivery.tagged(plugin, "trade",
                reservation.token(), row.id(), 0, row.item()));
        List<ClaimDelivery.TaggedItem> missing = ClaimDelivery.missing(player, plugin, expected);
        if (!ClaimDelivery.canFit(player, missing) || !ClaimDelivery.add(player, missing)) {
            releaseFreshClaim(player.getUniqueId(), batch).whenComplete((ignored, error) ->
                    claimDeliveries.remove(player.getUniqueId()));
            return;
        }
        CompletableFuture<Integer> complete = CompletableFuture.supplyAsync(() -> {
            try { return storage.completeReservation(player.getUniqueId(), reservation.token()); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        track(complete);
        complete.whenComplete((changed, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || changed == null || changed != reservation.claims().size()) {
                plugin.getLogger().log(Level.SEVERE, "Could not acknowledge trade claim reservation "
                        + reservation.token() + "; tagged items remain reconcilable.", error);
                claimDeliveries.remove(player.getUniqueId());
                return;
            }
            ClaimDelivery.clearMarkers(player, plugin, "trade", reservation.token());
            deliverTradeReservation(player, batch, index + 1);
        }));
    }

    private CompletableFuture<Void> releaseFreshClaim(UUID playerId, TradeClaimBatch batch) {
        if (batch.freshToken() == null) return CompletableFuture.completedFuture(null);
        return releaseClaims(playerId, batch.reservations().stream()
                .filter(reservation -> reservation.token().equals(batch.freshToken())).toList());
    }

    private CompletableFuture<Void> releaseClaims(UUID playerId, List<TradeStorage.ClaimReservation> reservations) {
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            try {
                for (TradeStorage.ClaimReservation reservation : reservations)
                    storage.releaseReservation(playerId, reservation.token());
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to release undelivered trade claims for " + playerId,
                        error);
            }
        });
        track(release);
        return release;
    }

    /** Loads and reserves legacy currency credits off-thread before touching Bukkit on the main thread. */
    public void processPendingPayouts(UUID ownerFilter) {
        if (shuttingDown.get()) return;
        CompletableFuture<List<TradeStorage.PendingPayout>> load = CompletableFuture.supplyAsync(() -> {
            List<TradeStorage.PendingPayout> reserved = new ArrayList<>();
            try {
                for (TradeStorage.PendingPayout payout : storage.loadPendingPayouts()) {
                    if (ownerFilter != null && !ownerFilter.equals(payout.owner())) continue;
                    if (!pendingPayoutsInProgress.add(payout.key())) continue;
                    try {
                        if (storage.reservePendingPayout(payout.key(), System.currentTimeMillis())) {
                            reserved.add(payout);
                        } else {
                            pendingPayoutsInProgress.remove(payout.key());
                        }
                    } catch (Exception error) {
                        pendingPayoutsInProgress.remove(payout.key());
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not reserve Trade legacy payout " + payout.key(), error);
                    }
                }
                return reserved;
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        track(load);
        load.whenComplete((reserved, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not load Trade legacy payouts", error);
                return;
            }
            if (reserved.isEmpty()) return;
            if (shuttingDown.get() || !plugin.isEnabled()) {
                reserved.forEach(payout -> finishPendingPayout(payout.key(), false));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (shuttingDown.get()) {
                    reserved.forEach(payout -> finishPendingPayout(payout.key(), false));
                } else {
                    reserved.forEach(this::deliverPendingPayout);
                }
            });
        });
    }

    private void deliverPendingPayout(TradeStorage.PendingPayout payout) {
        if (shuttingDown.get()) {
            finishPendingPayout(payout.key(), false);
            return;
        }
        Player player = Bukkit.getPlayer(payout.owner());
        if (player == null || !player.isOnline()) {
            finishPendingPayout(payout.key(), false);
            return;
        }
        boolean delivered = false;
        boolean definitelyNotDelivered = false;
        try {
            if (payout.currency() == TradeStorage.PayoutCurrency.EXP) {
                if (payout.amount() != Math.rint(payout.amount()) || payout.amount() > Integer.MAX_VALUE) {
                    plugin.getLogger().severe("Trade payout " + payout.key() + " has an invalid EXP amount; "
                            + "it remains uncertain for staff review.");
                    pendingPayoutsInProgress.remove(payout.key());
                    return;
                }
                player.setLevel(player.getLevel() + (int) payout.amount());
                delivered = true;
            } else if (!EconomyHook.isAvailable()) {
                definitelyNotDelivered = true;
            } else {
                delivered = EconomyHook.getEconomy().depositPlayer(player, payout.amount()).transactionSuccess();
                definitelyNotDelivered = !delivered;
            }
        } catch (RuntimeException uncertain) {
            plugin.getLogger().log(Level.SEVERE, "Trade payout " + payout.key()
                    + " has an uncertain external result and will not be replayed automatically.", uncertain);
        }
        if (delivered) finishPendingPayout(payout.key(), true);
        else if (definitelyNotDelivered) finishPendingPayout(payout.key(), false);
        else pendingPayoutsInProgress.remove(payout.key());
    }

    private void finishPendingPayout(String key, boolean delivered) {
        CompletableFuture<Void> finish = CompletableFuture.runAsync(() -> {
            try {
                boolean changed = delivered ? storage.acknowledgePendingPayout(key)
                        : storage.releasePendingPayout(key);
                if (!changed) plugin.getLogger().severe("Trade payout " + key
                        + " changed while its result was being recorded.");
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Could not record the result of Trade payout " + key, error);
            }
        });
        track(finish);
        finish.whenComplete((ignored, error) -> pendingPayoutsInProgress.remove(key));
    }

    public List<TradeStorage.PendingPayout> uncertainPayouts() {
        try { return storage.loadUncertainPayouts(); }
        catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect uncertain Trade payouts", error);
            return List.of();
        }
    }

    public boolean resolveUncertainPayout(String key, boolean paid, Player actor) {
        try {
            boolean changed = paid ? storage.acknowledgePendingPayout(key) : storage.releasePendingPayout(key);
            if (!changed) return false;
            plugin.getLogger().warning(actor.getName() + " resolved Trade payout " + key + " as "
                    + (paid ? "PAID" : "RETRY") + ".");
            if (!paid) processPendingPayouts(null);
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not reconcile Trade payout " + key, error);
            return false;
        }
    }
    private static boolean canFit(Player player, ItemStack[] items) { ItemStack[] contents = player.getInventory().getStorageContents().clone(); for (ItemStack need : items) { if (need == null || need.isEmpty()) continue; int remaining = need.getAmount(); for (int i=0;i<contents.length && remaining>0;i++) { ItemStack have=contents[i]; if (have != null && have.isSimilar(need)) { int room=have.getMaxStackSize()-have.getAmount(); if(room>0){int move=Math.min(room,remaining);have=have.clone();have.setAmount(have.getAmount()+move);contents[i]=have;remaining-=move;}} } for(int i=0;i<contents.length&&remaining>0;i++) if(contents[i]==null||contents[i].isEmpty()){int move=Math.min(need.getMaxStackSize(),remaining);ItemStack placed=need.clone();placed.setAmount(move);contents[i]=placed;remaining-=move;} if(remaining>0)return false; } return true; }
    private void track(CompletableFuture<?> future) { pendingWrites.add(future); future.whenComplete((v,e)->pendingWrites.remove(future)); }
    public void awaitWrites() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int emptyRounds = 0;
        try {
            while (System.nanoTime() < deadline) {
                CompletableFuture<?>[] snapshot = pendingWrites.toArray(new CompletableFuture[0]);
                if (snapshot.length == 0) {
                    if (++emptyRounds >= 3) return;
                    TimeUnit.MILLISECONDS.sleep(2L);
                    continue;
                }
                emptyRounds = 0;
                long remaining = Math.max(1L, deadline - System.nanoTime());
                CompletableFuture.allOf(snapshot).get(remaining, TimeUnit.NANOSECONDS);
            }
            plugin.getLogger().warning("Timed out waiting for pending Trade writes.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while waiting for Trade writes.", error);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Trade writes.", error);
        }
    }
    public void openHistory(Player viewer, UUID filter, String label, int page) {
        int safePage = Math.max(0, page);
        track(CompletableFuture.supplyAsync(() -> { try { return storage.loadHistory(filter, 45, safePage * 45); } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Failed to load trade history", e); return List.<TradeLogEntry>of(); } }).thenAccept(entries -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) TradeHistoryMenu.open(viewer, messages, filter, label, safePage, entries);
        })));
    }
}
