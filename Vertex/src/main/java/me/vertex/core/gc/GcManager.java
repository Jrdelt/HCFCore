package me.vertex.core.gc;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns the entire GC ledger. GC is 100% self-hosted -- this class and
 * {@link GcStorage} are the sole balance authority; nothing here ever reads
 * from or defers to Tebex, PlaceholderAPI, or any other external plugin.
 *
 * <p>Balances live in an in-memory cache ({@link #balances}), the same
 * "moment-to-moment source of truth, storage trails behind it"
 * philosophy {@code CoinflipManager}'s own class doc describes -- chosen
 * over {@code FactionBankManager}'s more conservative "commit to memory
 * only after the database write succeeds" style because GC needs to hand a
 * synchronous yes/no back to Coinflip and the Auction House the same way
 * {@code EconomyHook}/Vault already does for money, on the same tick a
 * command runs.
 *
 * <p>Every credit or debit is applied to the cache immediately (see
 * {@link #tryDebit} / {@link #credit}) and queued as a <em>relative</em>
 * database delta on a per-player write chain -- see {@link GcStorage}'s
 * class doc for why a relative delta is what makes this safe without
 * blocking the calling thread on the database write. Staff {@code
 * set}/{@code zero} are the one absolute (non-relative) mutation, which is
 * intentional: an admin overwriting a balance to an exact value is meant to
 * win outright, not merge with whatever else was happening to it.
 */
public final class GcManager {

    private final Plugin plugin;
    private final GcStorage storage;
    private final File file;

    private volatile long minWithdraw;
    private volatile long maxWithdraw;
    private volatile int redeemCodeLength;
    private volatile String redeemCodeCharset;
    private volatile int logPageSize;
    private volatile String interopCommandTemplate;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    public GcManager(Plugin plugin, GcStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "gc.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("gc.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        minWithdraw = Math.max(1L, config.getLong("min-withdraw", 1L));
        maxWithdraw = Math.max(minWithdraw, config.getLong("max-withdraw", 1_000_000_000L));
        redeemCodeLength = Math.max(4, Math.min(32, config.getInt("redeem-code-length", 12)));
        redeemCodeCharset = config.getString("redeem-code-charset", "ABCDEFGHJKLMNPQRSTUVWXYZ23456789");
        if (redeemCodeCharset == null || redeemCodeCharset.isBlank()) {
            redeemCodeCharset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        }
        logPageSize = Math.max(1, config.getInt("log-page-size", 8));
        interopCommandTemplate = config.getString("interop-command", "");
    }

    /** Blank by default -- see {@link GcInteropHook}. */
    public String interopCommandTemplate() {
        return interopCommandTemplate;
    }

    public void loadState() {
        try {
            balances.putAll(storage.loadAllBalances());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load GC balances from the database.", e);
        }
    }

    public long minWithdraw() {
        return minWithdraw;
    }

    public long maxWithdraw() {
        return maxWithdraw;
    }

    public int logPageSize() {
        return logPageSize;
    }

    // ---- Balance reads ----

    public long balance(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public boolean has(UUID uuid, long amount) {
        return amount > 0 && balance(uuid) >= amount;
    }

    // ---- Core mutation primitives ----

    /**
     * Debits {@code amount} from {@code target}'s cached balance immediately
     * if (and only if) they have enough, then queues the matching database
     * delta. Used for every debit: player withdrawals, staff removals,
     * Coinflip wagers, Auction House fees/purchases.
     *
     * @return false without changing anything if the amount is non-positive
     *         or the balance is insufficient.
     */
    public boolean tryDebit(UUID target, UUID actor, GcAction action, long amount, String note) {
        return tryDebit(target, actor, action, amount, note, null);
    }

    /**
     * As {@link #tryDebit(UUID, UUID, GcAction, long, String)}, additionally
     * invoking {@code onPersistFailure} (on the main thread) if the database
     * write behind this debit ultimately fails -- for a caller that took
     * something from an external system on the strength of this debit
     * succeeding (Vault money, in {@code GcMenu}'s withdraw-to-economy flow)
     * and must undo that too, not just let GC's own in-memory reversal happen.
     */
    public boolean tryDebit(UUID target, UUID actor, GcAction action, long amount, String note,
            Runnable onPersistFailure) {
        if (amount <= 0) {
            return false;
        }
        synchronized (writeChains) {
            long current = balances.getOrDefault(target, 0L);
            if (current < amount) {
                return false;
            }
            balances.merge(target, -amount, Long::sum);
            queuePersist(target, actor, action, -amount, amount, note, onPersistFailure);
            return true;
        }
    }

    /**
     * Credits {@code amount} to {@code target}'s cached balance immediately,
     * then queues the matching database delta. A no-op for a non-positive
     * amount.
     */
    public void credit(UUID target, UUID actor, GcAction action, long amount, String note) {
        credit(target, actor, action, amount, note, null);
    }

    /** As {@link #credit(UUID, UUID, GcAction, long, String)}; see {@link #tryDebit} for {@code onPersistFailure}. */
    public void credit(UUID target, UUID actor, GcAction action, long amount, String note, Runnable onPersistFailure) {
        if (amount <= 0) {
            return;
        }
        synchronized (writeChains) {
            balances.merge(target, amount, Long::sum);
            queuePersist(target, actor, action, amount, amount, note, onPersistFailure);
        }
    }

    /** Staff-only: overwrites a balance to an exact value. Deliberately absolute, not additive -- see the class doc. */
    public void setBalance(UUID target, UUID actor, GcAction action, long newBalance, String note) {
        long clamped = Math.max(0L, newBalance);
        synchronized (writeChains) {
            balances.put(target, clamped);
            CompletableFuture<Void> previous = writeChains.getOrDefault(target, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> next = previous.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    storage.setAbsolute(target, actor, action, clamped, note, System.currentTimeMillis());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to persist a GC staff balance override (" + action + ") for " + target, e);
                }
            });
            writeChains.put(target, next);
            next.whenComplete((ignored, error) -> writeChains.remove(target, next));
            track(next);
        }
    }

    /**
     * Queues {@code delta}'s persistence on {@code target}'s write chain, so
     * every database write for one player lands in the same order its cache
     * mutation happened in. On failure, the cache effect is reversed by
     * applying the opposite delta -- a relative correction, safe regardless
     * of whatever else has mutated the cache in the meantime.
     */
    private void queuePersist(UUID target, UUID actor, GcAction action, long delta, long logAmount, String note,
            Runnable onPersistFailure) {
        long now = System.currentTimeMillis();
        CompletableFuture<Void> previous = writeChains.getOrDefault(target, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> next = previous.handle((ignored, error) -> null).thenRunAsync(() -> {
            try {
                storage.applyDelta(target, actor, action, delta, note, now);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist a GC balance " + (delta < 0 ? "debit" : "credit")
                        + " (" + action + ", " + Math.abs(logAmount) + ") for " + target
                        + " -- reversing it in memory.", e);
                balances.merge(target, -delta, Long::sum);
                if (onPersistFailure != null) {
                    Bukkit.getScheduler().runTask(plugin, onPersistFailure);
                }
            }
        });
        writeChains.put(target, next);
        next.whenComplete((ignored, error) -> writeChains.remove(target, next));
        track(next);
    }

    // ---- Redeem codes ----

    public enum RedeemResult {
        OK, NOT_FOUND, EXPIRED, EXHAUSTED, FAILED
    }

    public record RedeemOutcome(RedeemResult result, long amount) {
    }

    /** Consumes one use of a code for this player, crediting its amount atomically against the database. */
    public CompletableFuture<RedeemOutcome> redeem(UUID playerUuid, String rawCode) {
        String code = normalizeCode(rawCode);
        if (code.isEmpty()) {
            return CompletableFuture.completedFuture(new RedeemOutcome(RedeemResult.NOT_FOUND, 0L));
        }
        CompletableFuture<RedeemOutcome> request;
        synchronized (writeChains) {
            CompletableFuture<Void> previous = writeChains.getOrDefault(playerUuid, CompletableFuture.completedFuture(null));
            request = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                try {
                    GcStorage.RedeemAttempt attempt = storage.consumeRedeemCode(code, playerUuid, System.currentTimeMillis());
                    if (attempt.result() == RedeemResult.OK) {
                        // A relative correction -- see the class doc on why this
                        // must never be an absolute put() computed here.
                        balances.merge(playerUuid, attempt.amount(), Long::sum);
                    }
                    return new RedeemOutcome(attempt.result(), attempt.amount());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to redeem GC code for " + playerUuid, e);
                    return new RedeemOutcome(RedeemResult.FAILED, 0L);
                }
            });
            CompletableFuture<Void> chain = request.handle((ignored, error) -> null);
            writeChains.put(playerUuid, chain);
            chain.whenComplete((ignored, error) -> writeChains.remove(playerUuid, chain));
        }
        track(request);
        return request;
    }

    public record CreateCodeOutcome(boolean success, String code) {
    }

    public enum WithdrawCodeResult { OK, INSUFFICIENT, FAILED }
    public record WithdrawCodeOutcome(WithdrawCodeResult result, String code, long balanceAfter) { }

    /**
     * Converts a player's GC into a one-use code. Unlike the retired
     * GC-to-Vault path, this waits for one database transaction before the
     * code is revealed, eliminating an unlogged/free code failure window.
     */
    public CompletableFuture<WithdrawCodeOutcome> withdrawToCode(UUID ownerUuid, long amount) {
        if (amount < minWithdraw || amount > maxWithdraw) {
            return CompletableFuture.completedFuture(new WithdrawCodeOutcome(WithdrawCodeResult.FAILED, null, balance(ownerUuid)));
        }
        CompletableFuture<WithdrawCodeOutcome> request;
        synchronized (writeChains) {
            CompletableFuture<Void> previous = writeChains.getOrDefault(ownerUuid, CompletableFuture.completedFuture(null));
            request = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                for (int attempt = 0; attempt < 8; attempt++) {
                    String code = generateCode();
                    try {
                        GcStorage.WithdrawCodeAttempt outcome = storage.withdrawToCode(ownerUuid, code, amount,
                                System.currentTimeMillis());
                        if (outcome.result() == GcStorage.WithdrawCodeResult.COLLISION) continue;
                        if (outcome.result() == GcStorage.WithdrawCodeResult.INSUFFICIENT) {
                            return new WithdrawCodeOutcome(WithdrawCodeResult.INSUFFICIENT, null, balance(ownerUuid));
                        }
                        balances.put(ownerUuid, outcome.balanceAfter());
                        return new WithdrawCodeOutcome(WithdrawCodeResult.OK, code, outcome.balanceAfter());
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to create a GC withdrawal code for " + ownerUuid, e);
                        return new WithdrawCodeOutcome(WithdrawCodeResult.FAILED, null, balance(ownerUuid));
                    }
                }
                plugin.getLogger().severe("Could not allocate a unique GC withdrawal code after eight attempts.");
                return new WithdrawCodeOutcome(WithdrawCodeResult.FAILED, null, balance(ownerUuid));
            });
            CompletableFuture<Void> chain = request.handle((ignored, error) -> null);
            writeChains.put(ownerUuid, chain);
            chain.whenComplete((ignored, error) -> writeChains.remove(ownerUuid, chain));
        }
        track(request);
        return request;
    }

    /**
     * Generates and persists a new redeem code. The code is generated and
     * returned immediately; the database insert happens asynchronously,
     * with a console error if it fails -- the same "trust the common case,
     * log loudly on the rare failure" shape used everywhere else this
     * plugin persists something after already having decided the outcome.
     */
    public CompletableFuture<CreateCodeOutcome> createRedeemCode(UUID staffUuid, long amount, int uses, Long expiresAtMillis) {
        if (amount <= 0 || uses <= 0) {
            return CompletableFuture.completedFuture(new CreateCodeOutcome(false, null));
        }
        String code = generateCode();
        CompletableFuture<CreateCodeOutcome> request = CompletableFuture.supplyAsync(() -> {
            try {
                storage.insertRedeemCode(code, amount, uses, staffUuid, System.currentTimeMillis(), expiresAtMillis);
                return new CreateCodeOutcome(true, code);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist a new GC redeem code.", e);
                return new CreateCodeOutcome(false, null);
            }
        });
        track(request);
        return request;
    }

    private String generateCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(redeemCodeLength);
        String charset = redeemCodeCharset;
        for (int i = 0; i < redeemCodeLength; i++) {
            builder.append(charset.charAt(random.nextInt(charset.length())));
        }
        return builder.toString();
    }

    private static String normalizeCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    // ---- Audit log ----

    /** Blocking JDBC read -- for staff command handlers only, mirroring {@code CoinflipManager#loadLog}. */
    public List<GcLogEntry> loadLog(UUID playerFilter, int limit, int offset) {
        try {
            return storage.loadLog(playerFilter, limit, offset);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load the GC audit log.", e);
            return List.of();
        }
    }

    /** Loads log rows without blocking a GUI open -- for {@code GcLogMenu}. */
    public CompletableFuture<List<GcLogEntry>> loadLogAsync(UUID playerFilter, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> loadLog(playerFilter, limit, offset));
    }

    // ---- Shutdown / migration draining ----

    private void track(CompletableFuture<?> write) {
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending GC writes.", e);
        }
    }
}
