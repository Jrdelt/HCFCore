package me.vertex.core.gc;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Owns the entire GC ledger. GC is 100% self-hosted -- this class and
 * {@link GcStorage} are the sole balance authority; nothing here ever reads
 * from or defers to Tebex, PlaceholderAPI, or any other external plugin.
 *
 * <p>The database, not the cache, authorizes all spending. The displayed
 * balance projects the last confirmed wallet plus local pending deltas.
 * Each completed write replaces that confirmed baseline and removes only
 * its own reservation. Code withdrawals participate in the same queue.
 * Auction/coinflip settlement debits commit with the settlement in SQL.
 */
public final class GcManager {

    private final Plugin plugin;
    private final GcStorage storage;
    private final File file;
    private final LongSupplier monotonicMillis;

    private static final long DEFAULT_REDEEM_COOLDOWN_SECONDS = 3L;
    private static final long MAX_REDEEM_COOLDOWN_SECONDS = 60L;
    private volatile long redeemCooldownMillis = DEFAULT_REDEEM_COOLDOWN_SECONDS * 1000L;
    // Admission/cooldown state uses writeChains' monitor; completion only removes
    // from the concurrent in-flight set, so it never blocks a waiting staff write.
    private final Map<UUID, Long> redeemDeadlines = new HashMap<>();
    private final java.util.Set<UUID> redemptionsInFlight = ConcurrentHashMap.newKeySet();
    private long nextRedeemCleanup = Long.MIN_VALUE;

    private volatile long minWithdraw;
    private volatile long maxWithdraw;
    private volatile int redeemCodeLength;
    private volatile String redeemCodeCharset;
    private volatile long maxCodeLifetimeSeconds;
    private final Map<Integer, String> recognizedCodeAlphabets = new ConcurrentHashMap<>();
    private volatile int logPageSize;
    private volatile String interopCommandTemplate;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Long> confirmedBalances = new HashMap<>();
    private final Map<UUID, java.math.BigInteger> pendingDeltas = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    public GcManager(Plugin plugin, GcStorage storage) {
        this(plugin, storage, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    GcManager(Plugin plugin, GcStorage storage, LongSupplier monotonicMillis) {
        this.plugin = plugin;
        this.storage = storage;
        this.monotonicMillis = monotonicMillis;
        this.file = new File(plugin.getDataFolder(), "gc.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("gc.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        minWithdraw = Math.max(1L, config.getLong("min-withdraw", 1L));
        maxWithdraw = Math.max(minWithdraw, config.getLong("max-withdraw", 1_000_000_000L));
        long cooldownSeconds = DEFAULT_REDEEM_COOLDOWN_SECONDS;
        if (config.contains("redeem-cooldown-seconds")) {
            Object configured = config.get("redeem-cooldown-seconds");
            if ((configured instanceof Integer || configured instanceof Long)
                    && ((Number) configured).longValue() >= 0L
                    && ((Number) configured).longValue() <= MAX_REDEEM_COOLDOWN_SECONDS) {
                cooldownSeconds = ((Number) configured).longValue();
            } else {
                plugin.getLogger().warning("Invalid gc.yml redeem-cooldown-seconds: expected a whole number from 0 to "
                        + MAX_REDEEM_COOLDOWN_SECONDS + "; using " + DEFAULT_REDEEM_COOLDOWN_SECONDS + " seconds.");
            }
        }
        redeemCooldownMillis = cooldownSeconds * 1000L;
        redeemCodeLength = Math.max(4, Math.min(32, config.getInt("redeem-code-length", 12)));
        redeemCodeCharset = config.getString("redeem-code-charset", "ABCDEFGHJKLMNPQRSTUVWXYZ23456789");
        if (redeemCodeCharset == null || redeemCodeCharset.isBlank()) {
            redeemCodeCharset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        }
        redeemCodeCharset = redeemCodeCharset.toUpperCase(java.util.Locale.ROOT);
        rememberCodeAlphabet(redeemCodeLength, redeemCodeCharset);
        maxCodeLifetimeSeconds = config.getLong("max-code-lifetime-seconds", 31_536_000L);
        if (maxCodeLifetimeSeconds < 1 || maxCodeLifetimeSeconds > 3_153_600_000L) {
            maxCodeLifetimeSeconds = 31_536_000L;
            plugin.getLogger().warning("Invalid gc.yml max-code-lifetime-seconds; using 31536000 (one year).");
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
            Map<UUID, Long> loaded = storage.loadAllBalances();
            storage.loadCodeAlphabets().forEach(this::rememberCodeAlphabet);
            synchronized (writeChains) {
                confirmedBalances.clear();
                confirmedBalances.putAll(loaded);
                balances.clear();
                loaded.keySet().forEach(this::projectBalance);
                pendingDeltas.keySet().forEach(this::projectBalance);
            }
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

    public int redeemCodeLength() {
        return redeemCodeLength;
    }

    public long maxCodeLifetimeSeconds() { return maxCodeLifetimeSeconds; }

    private void rememberCodeAlphabet(int length, String alphabet) {
        recognizedCodeAlphabets.merge(length, alphabet, (old, added) -> {
            StringBuilder merged = new StringBuilder(old);
            for (char character : added.toCharArray()) if (merged.indexOf(String.valueOf(character)) < 0) merged.append(character);
            return merged.toString();
        });
    }

    public java.util.Set<Integer> recognizedCodeLengths() { return java.util.Set.copyOf(recognizedCodeAlphabets.keySet()); }

    /** Includes persisted historical formats so a reload cannot expose an older code in chat. */
    public boolean isRedeemCodeCandidate(String token) {
        if (token == null) return false;
        String alphabet = recognizedCodeAlphabets.get(token.length());
        if (alphabet == null) return false;
        String upper = token.toUpperCase(java.util.Locale.ROOT);
        for (int index = 0; index < upper.length(); index++) {
            if (alphabet.indexOf(upper.charAt(index)) < 0) return false;
        }
        return true;
    }

    // ---- Balance reads ----

    public long balance(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public boolean has(UUID uuid, long amount) {
        return amount > 0 && balance(uuid) >= amount;
    }

    // All reservation/baseline edits hold writeChains' monitor. BigInteger
    // prevents temporary pending deltas from overflowing during reconciliation.
    private void projectBalance(UUID target) {
        java.math.BigInteger projected = java.math.BigInteger.valueOf(confirmedBalances.getOrDefault(target, 0L))
                .add(pendingDeltas.getOrDefault(target, java.math.BigInteger.ZERO));
        balances.put(target, projected.max(java.math.BigInteger.ZERO)
                .min(java.math.BigInteger.valueOf(GcStorage.MAX_BALANCE)).longValue());
    }

    private boolean reserve(UUID target, long delta) {
        long current = balance(target);
        if (delta == Long.MIN_VALUE || (delta < 0 && current < -delta)
                || (delta > 0 && current > GcStorage.MAX_BALANCE - delta)) return false;
        pendingDeltas.merge(target, java.math.BigInteger.valueOf(delta), java.math.BigInteger::add);
        projectBalance(target);
        return true;
    }

    private void finishMutation(UUID target, long reservedDelta, Long committedBalance) {
        synchronized (writeChains) {
            if (reservedDelta != 0L) {
                pendingDeltas.compute(target, (ignored, value) -> {
                    java.math.BigInteger remaining = (value == null ? java.math.BigInteger.ZERO : value)
                            .subtract(java.math.BigInteger.valueOf(reservedDelta));
                    return remaining.signum() == 0 ? null : remaining;
                });
            }
            if (committedBalance != null) confirmedBalances.put(target, committedBalance);
            projectBalance(target);
        }
    }

    /** Refresh after login or a settlement performed on another SQL transaction. */
    public CompletableFuture<Boolean> refreshBalance(UUID target) {
        synchronized (writeChains) {
            return enqueue(target, () -> {
                try { finishMutation(target, 0L, storage.loadBalance(target)); return true; }
                catch (Exception error) {
                    plugin.getLogger().log(Level.WARNING, "Failed to refresh GC balance for " + target, error);
                    return false;
                }
            });
        }
    }

    private <T> CompletableFuture<T> enqueue(UUID target, java.util.function.Supplier<T> action) {
        CompletableFuture<Void> previous = writeChains.getOrDefault(target, CompletableFuture.completedFuture(null));
        CompletableFuture<T> result = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> action.get());
        CompletableFuture<Void> chain = result.handle((ignored, error) -> null);
        writeChains.put(target, chain);
        chain.whenComplete((ignored, error) -> writeChains.remove(target, chain));
        track(result);
        return result;
    }

    // ---- Core mutation primitives ----

    /**
     * Debits {@code amount} from {@code target}'s cached balance immediately
     * if (and only if) they have enough, then queues the matching database
     * delta. This compatibility API reports admission, NOT persistence;
     * callers granting a reward must use a durable operation instead.
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
     * write behind this debit ultimately fails. No reward should be finalized
     * on the strength of an optimistic admission alone.
     */
    public boolean tryDebit(UUID target, UUID actor, GcAction action, long amount, String note,
            Runnable onPersistFailure) {
        if (amount <= 0) {
            return false;
        }
        synchronized (writeChains) {
            if (!reserve(target, -amount)) return false;
            queuePersist(target, actor, action, -amount, amount, note, null, onPersistFailure);
            return true;
        }
    }

    public record DurableDebit(boolean accepted, CompletableFuture<Boolean> persisted) { }

    /**
     * Starts an idempotent GC debit and exposes the actual ledger commit.
     * Escrow callers must remain non-interactable until {@code persisted}
     * succeeds; an optimistic cache change alone is not a durable wager.
     */
    public DurableDebit tryDebitDurably(UUID target,UUID actor,GcAction action,long amount,String note,
            String operationKey){
        if(amount<=0)return new DurableDebit(false,CompletableFuture.completedFuture(false));
        synchronized(writeChains){
            if(!reserve(target,-amount))return new DurableDebit(false,CompletableFuture.completedFuture(false));
            return new DurableDebit(true,queuePersist(target,actor,action,-amount,amount,note,operationKey,null));
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
            if (!reserve(target, amount)) {
                if (onPersistFailure != null) Bukkit.getScheduler().runTask(plugin, onPersistFailure);
                plugin.getLogger().warning("GC credit rejected at wallet limit for " + target);
                return;
            }
            queuePersist(target, actor, action, amount, amount, note, null, onPersistFailure);
        }
    }

    /**
     * Credits GC and reports when the ledger write itself has committed. This
     * is used by durable outboxes: their row must not be acknowledged merely
     * because an asynchronous GC write was queued.
     */
    public CompletableFuture<Boolean> creditDurably(UUID target, UUID actor, GcAction action, long amount,
            String note) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        synchronized (writeChains) {
            boolean reserved = reserve(target, amount);
            // A retry may already be committed at the wallet limit. Always
            // allow SQL to recognize the operation key before rejecting it.
            return queuePersist(target, actor, action, amount, amount, note, note, null, reserved ? amount : 0L);
        }
    }

    /** Staff-only: overwrites a balance to an exact value. Deliberately absolute, not additive -- see the class doc. */
    public boolean setBalance(UUID target, UUID actor, GcAction action, long newBalance, String note) {
        try { return setBalanceDurably(target, actor, action, newBalance, note).get(10, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "GC staff override is still pending or failed; inspect the ledger for " + target, error);
            return false;
        }
    }

    /** Staff commands await this asynchronously, so SQL cannot stall a server tick. */
    public CompletableFuture<Boolean> setBalanceDurably(UUID target, UUID actor, GcAction action, long newBalance, String note) {
        long clamped = Math.max(0L, newBalance);
        synchronized (writeChains) {
            return enqueue(target, () -> {
                try {
                storage.setAbsolute(target, actor, action, clamped, note, System.currentTimeMillis());
                finishMutation(target, 0L, clamped);
                return true;
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to persist a GC staff balance override (" + action + ") for " + target
                                + "; the live balance was not changed.", error);
                return false;
            }
            });
        }
    }

    /** SQL-authorized staff adjustment, including wallets changed on another shard. */
    public CompletableFuture<Boolean> adjustStaffDurably(UUID target, UUID actor, GcAction action, long delta, String note) {
        if (action != GcAction.STAFF_GIVE && action != GcAction.STAFF_REMOVE) throw new IllegalArgumentException("Not a staff adjustment");
        if (delta == 0 || delta == Long.MIN_VALUE || (delta > 0) != (action == GcAction.STAFF_GIVE)) return CompletableFuture.completedFuture(false);
        synchronized (writeChains) {
            boolean reserved = reserve(target, delta);
            return queuePersist(target, actor, action, delta, Math.abs(delta), note,
                    "staff:" + UUID.randomUUID(), null, reserved ? delta : 0L);
        }
    }

    /**
     * Queues {@code delta}'s persistence on {@code target}'s write chain, so
     * every database write for one player lands in the same order its cache
     * mutation happened in. On failure, the cache effect is reversed by
     * applying the opposite delta -- a relative correction, safe regardless
     * of whatever else has mutated the cache in the meantime.
     */
    private CompletableFuture<Boolean> queuePersist(UUID target, UUID actor, GcAction action, long delta,
            long logAmount, String note, String operationKey,
            Runnable onPersistFailure) {
        return queuePersist(target, actor, action, delta, logAmount, note, operationKey, onPersistFailure, delta);
    }

    private CompletableFuture<Boolean> queuePersist(UUID target, UUID actor, GcAction action, long delta,
            long logAmount, String note, String operationKey, Runnable onPersistFailure, long reservation) {
        long now = System.currentTimeMillis();
        return enqueue(target, () -> {
            try {
                GcStorage.DeltaResult applied = storage.applyDeltaOnce(target, actor, action, delta, note, now,
                        operationKey);
                finishMutation(target, reservation, applied.balanceAfter());
                return true;
            } catch (GcStorage.BalanceRejectedException rejected) {
                finishMutation(target, reservation, rejected.balance());
                if (onPersistFailure != null) Bukkit.getScheduler().runTask(plugin, onPersistFailure);
                return false;
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist a GC balance " + (delta < 0 ? "debit" : "credit")
                        + " (" + action + ", " + Math.abs(logAmount) + ") for " + target
                        + " -- reversing it in memory.", e);
                finishMutation(target, reservation, null);
                if (onPersistFailure != null) {
                    Bukkit.getScheduler().runTask(plugin, onPersistFailure);
                }
                return false;
            }
        });
    }

    // ---- Redeem codes ----

    public enum RedeemResult {
        OK, NOT_FOUND, EXPIRED, EXHAUSTED, FAILED, COOLDOWN, IN_PROGRESS, BALANCE_LIMIT
    }

    public record RedeemOutcome(RedeemResult result, long amount, long retryAfterMillis) {
        public RedeemOutcome(RedeemResult result, long amount) {
            this(result, amount, 0L);
        }
    }

    /**
     * Rate-limits all attempts per player before queueing SQL, including invalid
     * codes. The SQL transaction, not this local spam guard, prevents two players
     * or servers from consuming the same single-use code successfully.
     */
    public CompletableFuture<RedeemOutcome> redeem(UUID playerUuid, String rawCode) {
        CompletableFuture<RedeemOutcome> request;
        synchronized (writeChains) {
            long now = monotonicMillis.getAsLong();
            if (now >= nextRedeemCleanup) {
                redeemDeadlines.entrySet().removeIf(entry -> entry.getValue() <= now);
                nextRedeemCleanup = now + MAX_REDEEM_COOLDOWN_SECONDS * 1000L;
            }
            if (redemptionsInFlight.contains(playerUuid)) {
                return CompletableFuture.completedFuture(new RedeemOutcome(RedeemResult.IN_PROGRESS, 0L));
            }
            Long deadline = redeemDeadlines.get(playerUuid);
            if (deadline != null && deadline > now) {
                return CompletableFuture.completedFuture(new RedeemOutcome(RedeemResult.COOLDOWN, 0L, deadline - now));
            }
            redeemDeadlines.put(playerUuid, now + redeemCooldownMillis);
            String code = normalizeCode(rawCode);
            // Codes already issued remain usable after length/charset config changes.
            if (code.isEmpty() || code.length() > 32) {
                return CompletableFuture.completedFuture(new RedeemOutcome(RedeemResult.NOT_FOUND, 0L));
            }
            redemptionsInFlight.add(playerUuid);
            CompletableFuture<Void> previous = writeChains.getOrDefault(playerUuid, CompletableFuture.completedFuture(null));
            request = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                try {
                    GcStorage.RedeemAttempt attempt = storage.consumeRedeemCode(code, playerUuid, System.currentTimeMillis());
                    if (attempt.result() == RedeemResult.OK) {
                        finishMutation(playerUuid, 0L, attempt.balanceAfter());
                    }
                    return new RedeemOutcome(attempt.result(), attempt.amount());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to redeem GC code for " + playerUuid, e);
                    return new RedeemOutcome(RedeemResult.FAILED, 0L);
                }
            }).whenComplete((ignored, error) -> redemptionsInFlight.remove(playerUuid));
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
            if (!reserve(ownerUuid, -amount)) {
                return CompletableFuture.completedFuture(new WithdrawCodeOutcome(WithdrawCodeResult.INSUFFICIENT, null, balance(ownerUuid)));
            }
            CompletableFuture<Void> previous = writeChains.getOrDefault(ownerUuid, CompletableFuture.completedFuture(null));
            request = previous.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                for (int attempt = 0; attempt < 8; attempt++) {
                    String code = generateCode();
                    try {
                        GcStorage.WithdrawCodeAttempt outcome = storage.withdrawToCode(ownerUuid, code, amount,
                                System.currentTimeMillis());
                        if (outcome.result() == GcStorage.WithdrawCodeResult.COLLISION) continue;
                        if (outcome.result() == GcStorage.WithdrawCodeResult.INSUFFICIENT) {
                            finishMutation(ownerUuid, -amount, storage.loadBalance(ownerUuid));
                            return new WithdrawCodeOutcome(WithdrawCodeResult.INSUFFICIENT, null, balance(ownerUuid));
                        }
                        finishMutation(ownerUuid, -amount, outcome.balanceAfter());
                        return new WithdrawCodeOutcome(WithdrawCodeResult.OK, code, outcome.balanceAfter());
                    } catch (Exception e) {
                        finishMutation(ownerUuid, -amount, null);
                        plugin.getLogger().log(Level.SEVERE, "Failed to create a GC withdrawal code for " + ownerUuid, e);
                        return new WithdrawCodeOutcome(WithdrawCodeResult.FAILED, null, balance(ownerUuid));
                    }
                }
                plugin.getLogger().severe("Could not allocate a unique GC withdrawal code after eight attempts.");
                finishMutation(ownerUuid, -amount, null);
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
     * Generates a code but reveals it only after its SQL insert commits.
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
