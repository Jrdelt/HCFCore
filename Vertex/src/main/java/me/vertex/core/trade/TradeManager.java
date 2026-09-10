package me.vertex.core.trade;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.util.AmountParser;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
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
import java.util.logging.Level;

/** Owns requests, sessions, escrow, cooldowns and all trade safety rules. */
public final class TradeManager {
    public enum Result { OK, DISABLED, SELF, BUSY, TOO_FAR, TARGET_OFF, BLOCKED, NO_REQUEST, EXPIRED, NOT_REQUESTED, COOLDOWN, NO_ECONOMY, CANNOT_AFFORD, INVALID_AMOUNT, LOCKED, NOT_FIRST_LOCKED, FULL }
    private record Request(UUID sender, UUID target, long expires) { }
    private final Plugin plugin; private final TradeStorage storage; private final Messages messages; private final File file;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> accepting = new ConcurrentHashMap<>();
    private final Set<UUID> programmaticClose = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<Void>> escrowChains = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled, compact; private volatile int decimalPlaces;
    private volatile double maxDistance, maxMoney; private volatile int maxExperience; private volatile long requestTimeout, idleTimeout, cooldown;
    private volatile Material divider = Material.BLACK_STAINED_GLASS_PANE, filler = Material.GRAY_STAINED_GLASS_PANE, confirm = Material.LIME_DYE, locked = Material.GRAY_DYE, xp = Material.EXPERIENCE_BOTTLE, currency = Material.GOLD_INGOT;
    private volatile Set<String> worlds = Set.of(); private volatile Set<GameMode> gameModes = Set.of(); private volatile Set<Material> blockedItems = Set.of();

    public TradeManager(Plugin plugin, TradeStorage storage, Messages messages) { this.plugin = plugin; this.storage = storage; this.messages = messages; this.file = new File(plugin.getDataFolder(), "traders.yml"); }
    public void load() {
        if (!file.exists()) plugin.saveResource("traders.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        enabled = c.getBoolean("enabled", true); maxDistance = positive(c, "trade.max-distance", 10); requestTimeout = seconds(c, "trade.request-timeout-seconds", 30); idleTimeout = seconds(c, "trade.idle-timeout-seconds", 60); cooldown = seconds(c, "trade.cooldown-seconds", 15);
        // Trades are item-only. The legacy money/XP fields remain in the SQL
        // schema solely to return any escrow created before this update.
        maxMoney = 0; maxExperience = 0;
        compact = !"full".equalsIgnoreCase(c.getString("number-formatting.style", "compact")); decimalPlaces = Math.max(0, Math.min(4, c.getInt("number-formatting.decimal-places", 1)));
        divider = material(c, "gui.divider-material", Material.BLACK_STAINED_GLASS_PANE); filler = material(c, "gui.filler-material", Material.GRAY_STAINED_GLASS_PANE); confirm = material(c, "gui.confirm-item", Material.LIME_DYE); locked = material(c, "gui.locked-item", Material.GRAY_DYE); xp = material(c, "gui.xp-item", Material.EXPERIENCE_BOTTLE); currency = material(c, "gui.currency-item", Material.GOLD_INGOT);
        worlds = lower(c.getStringList("blacklist.worlds")); blockedItems = materials(c.getStringList("blacklist.items")); Set<GameMode> modes = new HashSet<>(); for (String raw : c.getStringList("blacklist.gamemodes")) try { modes.add(GameMode.valueOf(raw.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException e) { warn("blacklist.gamemodes", raw); } gameModes = Set.copyOf(modes);
    }
    private double positive(YamlConfiguration c, String key, double fallback) { double v = c.getDouble(key, fallback); if (!Double.isFinite(v) || v < 0) { warn(key, "using " + fallback); return fallback; } return v; }
    private long seconds(YamlConfiguration c, String key, long fallback) { return Math.round(positive(c, key, fallback) * 1000L); }
    private Material material(YamlConfiguration c, String key, Material fallback) { Material m = Material.matchMaterial(c.getString(key, fallback.name())); if (m == null) { warn(key, "using " + fallback); return fallback; } return m; }
    private Set<Material> materials(Collection<String> values) { Set<Material> out = new HashSet<>(); for (String raw : values) { Material m = Material.matchMaterial(raw); if (m == null) warn("blacklist.items", raw); else out.add(m); } return Set.copyOf(out); }
    private Set<String> lower(Collection<String> values) { return values.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private void warn(String key, String value) { plugin.getLogger().warning("Invalid traders.yml " + key + " (" + value + "); using a safe fallback."); }
    public boolean isEnabled() { return enabled; } public boolean moneyEnabled() { return false; } public boolean experienceEnabled() { return false; }
    public Material dividerMaterial() { return divider; } public Material fillerMaterial() { return filler; } public Material confirmMaterial() { return confirm; } public Material lockedMaterial() { return locked; } public Material experienceMaterial() { return xp; } public Material currencyMaterial() { return currency; }
    public double maxDistance() { return maxDistance; }
    public boolean isProgrammaticClose(UUID id) { return programmaticClose.remove(id); }
    public boolean isInSession(UUID id) { return sessions.containsKey(id); }
    public TradeSession session(UUID id) { return sessions.get(id); }

    public Result request(Player sender, Player target) {
        if (!enabled) return Result.DISABLED; if (sender.equals(target)) return Result.SELF; if (busy(sender.getUniqueId()) || busy(target.getUniqueId())) return Result.BUSY; if (onCooldown(sender.getUniqueId()) || onCooldown(target.getUniqueId())) return Result.COOLDOWN;
        if (!allowed(sender) || !allowed(target)) return Result.BLOCKED; if (!accepting.getOrDefault(target.getUniqueId(), true)) return Result.TARGET_OFF; if (!near(sender, target)) return Result.TOO_FAR;
        Request request = new Request(sender.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + requestTimeout); requests.put(target.getUniqueId(), request); return Result.OK;
    }
    public Result accept(Player target, Player sender) {
        Request request = requests.get(target.getUniqueId()); if (request == null) return Result.NO_REQUEST; if (!request.sender.equals(sender.getUniqueId())) return Result.NOT_REQUESTED;
        if (request.expires < System.currentTimeMillis()) { endRequest(request); return Result.EXPIRED; }
        if (!near(sender, target)) return Result.TOO_FAR; if (!allowed(sender) || !allowed(target)) return Result.BLOCKED;
        requests.remove(target.getUniqueId()); TradeSession session = new TradeSession(sender.getUniqueId(), target.getUniqueId()); sessions.put(sender.getUniqueId(), session); sessions.put(target.getUniqueId(), session);
        org.bukkit.inventory.Inventory inventory = TradeMenu.open(session, sender, target, this, messages); sender.openInventory(inventory); target.openInventory(inventory); persistEscrow(session); return Result.OK;
    }
    public boolean isAccepting(UUID uuid) { return accepting.getOrDefault(uuid, true); }

    public boolean toggle(Player player) { boolean value = !accepting.getOrDefault(player.getUniqueId(), true); accepting.put(player.getUniqueId(), value); track(CompletableFuture.runAsync(() -> { try { storage.saveAccepting(player.getUniqueId(), value); } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Could not save trade preference", e); } })); return value; }
    public void loadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        track(CompletableFuture.runAsync(() -> {
            try {
                accepting.put(playerId, storage.loadAccepting(playerId));
                applyClaims(playerId);
                int levels = storage.takePendingExperience(playerId);
                double money = storage.takePendingMoney(playerId);
                if (levels > 0 || money > 0) {
                    Bukkit.getScheduler().runTask(plugin, () -> applyLegacyCredits(playerId, levels, money));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load trade preferences", e);
            }
        }));
    }
    public boolean allowed(Player player) { if (player.hasPermission("vertex.trade.staff.bypassblacklist")) return true; return !worlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT)) && !gameModes.contains(player.getGameMode()); }
    public boolean canTradeItem(Player player, ItemStack item) { return player.hasPermission("vertex.trade.staff.bypassblacklist") || item == null || !blockedItems.contains(item.getType()); }
    private boolean near(Player a, Player b) { return a.hasPermission("vertex.trade.staff.bypassdistance") || a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= maxDistance * maxDistance; }
    private boolean busy(UUID id) { return sessions.containsKey(id) || requests.containsKey(id) || requests.values().stream().anyMatch(r -> r.sender.equals(id)); }
    private boolean onCooldown(UUID id) { return cooldowns.getOrDefault(id, 0L) > System.currentTimeMillis(); }
    private void endRequest(Request request) { requests.remove(request.target, request); cooldowns.put(request.sender, System.currentTimeMillis() + cooldown); cooldowns.put(request.target, System.currentTimeMillis() + cooldown); }
    public Result setValue(Player player, TradeSession session, TradeValueType type, String raw) {
        if (session == null || session.locked(player.getUniqueId())) return Result.LOCKED; Long parsed = AmountParser.parse(raw); if (parsed == null) return Result.INVALID_AMOUNT;
        if (type == TradeValueType.MONEY) { if (!moneyEnabled() || parsed > maxMoney) return Result.INVALID_AMOUNT; if (!EconomyHook.isAvailable()) return Result.NO_ECONOMY; if (EconomyHook.getEconomy().getBalance(player) + 1.0E-8 < parsed) return Result.CANNOT_AFFORD; if (session.isRequester(player.getUniqueId())) session.requesterMoney = parsed; else session.targetMoney = parsed; }
        else { if (!experienceEnabled() || parsed > maxExperience) return Result.INVALID_AMOUNT; if (player.getLevel() < parsed) return Result.CANNOT_AFFORD; if (session.isRequester(player.getUniqueId())) session.requesterExperience = parsed.intValue(); else session.targetExperience = parsed.intValue(); }
        touch(session); return Result.OK;
    }
    public Result lock(Player player, TradeSession session) {
        if (session == null || session.locked(player.getUniqueId())) return Result.LOCKED;
        session.lock(player.getUniqueId()); touch(session); return Result.OK;
    }
    public Result complete(Player actor, TradeSession session) {
        if (session == null || !session.bothLocked() || !actor.getUniqueId().equals(session.firstLocked)) return Result.NOT_FIRST_LOCKED; if (session.finishing) return Result.LOCKED;
        Player requester = Bukkit.getPlayer(session.requester); Player target = Bukkit.getPlayer(session.target); if (requester == null || target == null || !near(requester, target) || !allowed(requester) || !allowed(target) || !canFit(requester, session.itemsFor(session.target)) || !canFit(target, session.itemsFor(session.requester))) { cancel(session, "trade-cancelled"); return Result.FULL; }
        session.finishing = true; ItemStack[] left = session.itemsFor(session.requester), right = session.itemsFor(session.target); give(requester, right); give(target, left);
        finish(session, "COMPLETED", requester, target); return Result.OK;
    }
    public void cancel(TradeSession session, String messageKey) {
        if (session == null || session.finishing) return; session.finishing = true; Player requester = Bukkit.getPlayer(session.requester); Player target = Bukkit.getPlayer(session.target);
        if (requester != null) { give(requester, session.itemsFor(session.requester)); }
        if (target != null) { give(target, session.itemsFor(session.target)); }
        finish(session, "CANCELLED", requester, target); if (requester != null) requester.sendMessage(messages.get(requester, "trade." + messageKey, "player", target == null ? "player" : target.getName())); if (target != null) target.sendMessage(messages.get(target, "trade." + messageKey, "player", requester == null ? "player" : requester.getName()));
    }
    private void finish(TradeSession s, String status, Player requester, Player target) {
        sessions.remove(s.requester, s); sessions.remove(s.target, s); cooldowns.put(s.requester, System.currentTimeMillis() + cooldown); cooldowns.put(s.target, System.currentTimeMillis() + cooldown);
        if (requester != null) { programmaticClose.add(s.requester); requester.closeInventory(); if ("COMPLETED".equals(status)) requester.sendMessage(messages.get(requester, "trade.complete", "player", target == null ? "player" : target.getName())); }
        if (target != null) { programmaticClose.add(s.target); target.closeInventory(); if ("COMPLETED".equals(status)) target.sendMessage(messages.get(target, "trade.complete", "player", requester == null ? "player" : requester.getName())); }
        persistClear(s, requester == null ? s.requester.toString() : requester.getName(), target == null ? s.target.toString() : target.getName(), status);
    }
    public void touch(TradeSession session) { session.lastActivity = System.currentTimeMillis(); Player a = Bukkit.getPlayer(session.requester), b = Bukkit.getPlayer(session.target); TradeMenu.render(session, Bukkit.getOfflinePlayer(session.requester), Bukkit.getOfflinePlayer(session.target), this, messages); if (a != null && b != null) { Player other = a; other.playSound(other.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, .2f, 1.7f); } persistEscrow(session); }
    public void sweep() { long now = System.currentTimeMillis(); for (Request request : List.copyOf(requests.values())) if (request.expires <= now) endRequest(request); Set<TradeSession> unique = new HashSet<>(sessions.values()); for (TradeSession s : unique) { Player a = Bukkit.getPlayer(s.requester), b = Bukkit.getPlayer(s.target); if (a == null || b == null || !allowed(a) || !allowed(b) || !near(a,b)) cancel(s, "trade-cancelled"); else if (now - s.lastActivity >= idleTimeout) cancel(s, "trade-idle-cancelled"); } cooldowns.entrySet().removeIf(e -> e.getValue() <= now); }
    public void shutdown() { for (TradeSession session : new HashSet<>(sessions.values())) cancel(session, "trade-cancelled"); awaitWrites(); }
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
    private void persistClear(TradeSession session, String requester, String target, String status) {
        TradeSnapshot snapshot = TradeSnapshot.capture(session);
        CompletableFuture<Void> next = escrowChains.compute(snapshot.sessionId(), (id, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((v, e) -> null))
                        .thenRunAsync(() -> {
                            try { storage.deleteEscrow(snapshot.sessionId()); storage.insertHistory(snapshot, requester, target, status); }
                            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Failed to write trade audit row", e); }
                        }));
        track(next);
        next.whenComplete((ignored, error) -> escrowChains.remove(snapshot.sessionId(), next));
    }
    public void restoreEscrow() {
        track(CompletableFuture.supplyAsync(() -> {
            try { return storage.loadEscrow(); }
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
    /** Deletes first, then delivers only the claims this invocation owns. */
    private void applyClaims(UUID playerId) {
        try {
            List<ItemStack> claims = storage.takeClaims(playerId);
            if (claims.isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    restoreClaims(playerId, claims);
                    return;
                }
                for (ItemStack item : claims) {
                    give(player, new ItemStack[]{item});
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to claim returned trade items", e);
        }
    }

    /** A player may quit between the asynchronous claim reservation and its main-thread delivery. */
    private void restoreClaims(UUID playerId, List<ItemStack> claims) {
        track(CompletableFuture.runAsync(() -> {
            try {
                for (ItemStack item : claims) {
                    if (item != null && !item.isEmpty()) {
                        storage.insertClaim(playerId, item);
                    }
                }
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to restore undelivered trade claims for " + playerId, error);
            }
        }));
    }

    /** Legacy money/XP escrow is re-queued if a player leaves before delivery. */
    private void applyLegacyCredits(UUID playerId, int levels, double money) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            track(CompletableFuture.runAsync(() -> {
                try {
                    storage.addPendingExperience(playerId, levels);
                    if (money > 0) {
                        storage.addPendingMoney(playerId, money);
                    }
                } catch (Exception error) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to restore pending legacy trade credits for " + playerId, error);
                }
            }));
            return;
        }
        if (levels > 0) {
            player.setLevel(player.getLevel() + levels);
        }
        if (money > 0 && EconomyHook.isAvailable()) {
            EconomyHook.getEconomy().depositPlayer(player, money);
        }
    }
    private static boolean canFit(Player player, ItemStack[] items) { ItemStack[] contents = player.getInventory().getStorageContents().clone(); for (ItemStack need : items) { if (need == null || need.isEmpty()) continue; int remaining = need.getAmount(); for (int i=0;i<contents.length && remaining>0;i++) { ItemStack have=contents[i]; if (have != null && have.isSimilar(need)) { int room=have.getMaxStackSize()-have.getAmount(); if(room>0){int move=Math.min(room,remaining);have=have.clone();have.setAmount(have.getAmount()+move);contents[i]=have;remaining-=move;}} } for(int i=0;i<contents.length&&remaining>0;i++) if(contents[i]==null||contents[i].isEmpty()){int move=Math.min(need.getMaxStackSize(),remaining);ItemStack placed=need.clone();placed.setAmount(move);contents[i]=placed;remaining-=move;} if(remaining>0)return false; } return true; }
    private void give(Player player, ItemStack[] items) { for (ItemStack item : items) if (item != null && !item.isEmpty()) player.getInventory().addItem(item.clone()).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left)); }
    private void track(CompletableFuture<?> future) { pendingWrites.add(future); future.whenComplete((v,e)->pendingWrites.remove(future)); }
    public void awaitWrites() { for (CompletableFuture<?> write : List.copyOf(pendingWrites)) try { write.get(5, TimeUnit.SECONDS); } catch (Exception ignored) { } }
    public void openHistory(Player viewer, UUID filter, String label, int page) {
        int safePage = Math.max(0, page);
        track(CompletableFuture.supplyAsync(() -> { try { return storage.loadHistory(filter, 45, safePage * 45); } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Failed to load trade history", e); return List.<TradeLogEntry>of(); } }).thenAccept(entries -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) TradeHistoryMenu.open(viewer, messages, filter, label, safePage, entries);
        })));
    }
    public String format(double amount) { if (!compact) return new DecimalFormat("#,##0." + "#".repeat(decimalPlaces)).format(amount); String[] suffix={"","K","M","B"}; int i=0; while(amount>=1000&&i<3){amount/=1000;i++;} return new DecimalFormat("0."+"#".repeat(decimalPlaces)).format(amount)+suffix[i]; }
}
