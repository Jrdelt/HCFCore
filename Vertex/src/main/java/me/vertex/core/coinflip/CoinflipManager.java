package me.vertex.core.coinflip;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.gc.GcAction;
import me.vertex.core.gc.GcManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import me.vertex.core.storage.ClaimDelivery;
import me.vertex.core.util.Numbers;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Owns every Coinflip rule and all of its state. In-memory maps
 * ({@link #activeCoinflips} and bans) are the moment-to-
 * moment source of truth -- updated synchronously on the main thread the
 * instant a command runs -- with {@link CoinflipStorage} as trailing,
 * eventually-consistent persistence behind them, the same pattern
 * {@code ChunkCollectorManager}/{@code BlueprintManager} already use. A
 * coinflip's wager (money, levels, or items) is always taken from the
 * host at creation time and from the opponent at play time -- nothing is
 * ever "reserved" without actually being removed from a real balance,
 * inventory, or level count, so a lost/crashed server never leaves a
 * wager double-counted or unaccounted for.
 */
public final class CoinflipManager {

    private final Plugin plugin;
    private final CoinflipStorage storage;
    private final Messages messages;
    private final CombatManager combatManager;
    private final AnnouncementPreferenceManager announcements;
    private final File file;
    /** Set post-construction, mirroring {@code captureEventManager.setPvpTopManager}; null means GC wagers refuse cleanly. */
    private volatile GcManager gcManager;

    private volatile boolean enabled;
    private volatile double minMoneyWager;
    private volatile double maxMoneyWager;
    private volatile int minExpWager;
    private volatile int maxExpWager;
    private volatile long minGcWager;
    private volatile long maxGcWager;
    private volatile int maxItemStacksPerWager;
    private volatile double houseFeePercent;
    private volatile int selfBanDays;
    private volatile long itemIconCycleTicks;
    private volatile long guiRefreshIntervalTicks;
    private volatile long logRetentionDays;
    /** Result animation length, clamped to the player-facing 5-10 second contract. */
    private volatile long animationDurationTicks;
    private volatile long itemMatchApprovalTimeoutMillis;

    private final Map<Integer, Coinflip> activeCoinflips = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bans = new ConcurrentHashMap<>();
    private final java.util.Set<String> pendingPayoutsInProgress = ConcurrentHashMap.newKeySet();
    /** uuid -> the deadline for a "/cf ban confirm" to actually apply. */
    private final Map<UUID, Long> pendingBanConfirmations = new ConcurrentHashMap<>();
    /** coinflip id -> the one opponent item-wager awaiting that coinflip's host to approve or deny. */
    private final Map<Integer, CoinflipPendingMatch> pendingItemMatches = new ConcurrentHashMap<>();
    /** Prevents a double-click from claiming the same database rows twice. */
    private final java.util.Set<UUID> claimsInProgress = ConcurrentHashMap.newKeySet();
    /** Suppresses a join-time notification read racing the scheduled in-session reveal. */
    private final java.util.Set<ResultNotificationKey> resultNotificationsInFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService payoutExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vertex-coinflip-payout");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final AtomicInteger localIdCounter = new AtomicInteger(-1);
    /** Increments only when a browser-visible listing or pending-match state changes. */
    private final AtomicLong browserVersion = new AtomicLong();

    public CoinflipManager(Plugin plugin, CoinflipStorage storage, Messages messages, CombatManager combatManager) {
        this(plugin, storage, messages, combatManager, null);
    }

    public CoinflipManager(Plugin plugin, CoinflipStorage storage, Messages messages, CombatManager combatManager,
            AnnouncementPreferenceManager announcements) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
        this.combatManager = combatManager;
        this.announcements = announcements;
        this.file = new File(plugin.getDataFolder(), "coinflips.yml");
    }

    /** Wired in after construction, once {@code GcManager} exists -- same pattern as {@code setPvpTopManager}. */
    public void setGcManager(GcManager gcManager) {
        this.gcManager = gcManager;
        processPendingPayouts(null);
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("coinflips.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        minMoneyWager = Math.max(0, config.getDouble("min-money-wager", 1.0));
        maxMoneyWager = Math.max(minMoneyWager, config.getDouble("max-money-wager", 1000000.0));
        minExpWager = Math.max(1, config.getInt("min-exp-wager", 1));
        maxExpWager = Math.max(minExpWager, config.getInt("max-exp-wager", 500));
        minGcWager = Math.max(1L, config.getLong("min-gc-wager", 1L));
        maxGcWager = Math.max(minGcWager, config.getLong("max-gc-wager", 1_000_000L));
        maxItemStacksPerWager = Math.max(1, config.getInt("max-item-stacks-per-wager", 9));
        houseFeePercent = Math.max(0, Math.min(100, config.getDouble("house-fee-percent", 0.0)));
        selfBanDays = Math.max(1, config.getInt("self-ban-days", 30));
        itemIconCycleTicks = Math.max(1, config.getLong("item-icon-cycle-ticks", 20));
        // Browsers only rebuild for a state/icon change, so polling faster
        // than the icon cycle has no player-visible benefit. Keep a floor
        // here as well as in the default file so existing 1-tick configs do
        // not retain their old full-server polling cost after an update.
        guiRefreshIntervalTicks = Math.max(10, config.getLong("gui-refresh-interval-ticks", 20));
        logRetentionDays = Math.max(0, config.getLong("log-retention-days", 0));
        int animationSeconds = Math.max(5, Math.min(10, config.getInt("animation-duration-seconds", 6)));
        animationDurationTicks = TimeUnit.SECONDS.toSeconds(animationSeconds) * 20L;
        itemMatchApprovalTimeoutMillis = Duration.ofSeconds(
                Math.max(30, config.getInt("item-match-approval-timeout-seconds", 300))).toMillis();
    }

    /** Loads active coinflips, bans, durable payouts, and pending item-match approvals on startup. */
    public void loadState() {
        try {
            for (CoinflipStorage.CreationIntent intent : storage.loadCreationIntents()) {
                if ("PREPARED".equals(intent.state())) {
                    storage.deleteCreationIntent(intent.key());
                    continue;
                }
                if ("DEBITING".equals(intent.state())) {
                    plugin.getLogger().severe("Coinflip creation intent " + intent.key()
                            + " is awaiting staff reconciliation and was not replayed.");
                    continue;
                }
                int id = storage.activateCreationIntent(intent);
                if (id >= 0) activeCoinflips.put(id, coinflipFrom(intent, id));
            }
            for (Coinflip coinflip : storage.loadAllCoinflips()) {
                activeCoinflips.put(coinflip.id(), coinflip);
            }
            bans.putAll(storage.loadBans());
            for (CoinflipPendingMatch match : storage.loadAllPendingMatches()) {
                if (!activeCoinflips.containsKey(match.coinflipId())) {
                    // Its coinflip resolved or was cancelled through some
                    // other path before the server stopped -- nobody can
                    // ever approve this anymore, so refund it now instead
                    // of leaving it stuck forever.
                    refundPendingMatch(match);
                    continue;
                }
                pendingItemMatches.put(match.coinflipId(), match);
            }
            reportUncertainPayouts();
            browserChanged();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Coinflip state from the database.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double minMoneyWager() {
        return minMoneyWager;
    }

    public double maxMoneyWager() {
        return maxMoneyWager;
    }

    public int minExpWager() {
        return minExpWager;
    }

    public int maxExpWager() {
        return maxExpWager;
    }

    public long minGcWager() {
        return minGcWager;
    }

    public long maxGcWager() {
        return maxGcWager;
    }

    public int maxItemStacksPerWager() {
        return maxItemStacksPerWager;
    }

    public long itemIconCycleTicks() {
        return itemIconCycleTicks;
    }

    public long guiRefreshIntervalTicks() {
        return guiRefreshIntervalTicks;
    }

    public long animationDurationTicks() {
        return animationDurationTicks;
    }

    public long browserVersion() {
        return browserVersion.get();
    }

    private void browserChanged() {
        browserVersion.incrementAndGet();
    }

    public List<Coinflip> activeCoinflips() {
        return List.copyOf(activeCoinflips.values());
    }

    public Coinflip getCoinflip(int id) {
        return activeCoinflips.get(id);
    }

    public boolean hasPendingItemMatch(int coinflipId) {
        return pendingItemMatches.containsKey(coinflipId);
    }

    public CoinflipPendingMatch pendingItemMatch(int coinflipId) {
        return pendingItemMatches.get(coinflipId);
    }

    /** A player can only be trying to take one coinflip at a time -- proposing a second item match elsewhere is refused. */
    private boolean isTakingAMatch(UUID opponentUuid) {
        for (CoinflipPendingMatch match : pendingItemMatches.values()) {
            if (match.opponentUuid().equals(opponentUuid)) {
                return true;
            }
        }
        return false;
    }

    // ---- Self-ban ----

    public boolean isBanned(UUID uuid) {
        Long until = bans.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Millis remaining, or 0 if not banned. */
    public long banRemainingMillis(UUID uuid) {
        Long until = bans.get(uuid);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    /** Starts (or restarts) the 30-second window a "/cf ban confirm" must land in. */
    public void requestBanConfirmation(UUID uuid) {
        pendingBanConfirmations.put(uuid, System.currentTimeMillis() + Duration.ofSeconds(30).toMillis());
    }

    public boolean hasPendingBanConfirmation(UUID uuid) {
        Long deadline = pendingBanConfirmations.get(uuid);
        return deadline != null && System.currentTimeMillis() < deadline;
    }

    /** Applies the self-ban immediately; caller must have already confirmed via {@link #hasPendingBanConfirmation}. */
    public void applyBan(UUID uuid) {
        pendingBanConfirmations.remove(uuid);
        long until = System.currentTimeMillis() + Duration.ofDays(selfBanDays).toMillis();
        bans.put(uuid, until);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.saveBan(uuid, until);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist a Coinflip self-ban for " + uuid, e);
            }
        }));
    }

    /** @return true if the ban was actually lifted (false if still within its mandatory window, or not banned). */
    public boolean liftBanIfExpired(UUID uuid) {
        Long until = bans.get(uuid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() < until) {
            return false;
        }
        bans.remove(uuid);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteBan(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to remove a Coinflip self-ban for " + uuid, e);
            }
        }));
        return true;
    }

    // ---- Creation ----

    public enum CreateResult {
        OK, DISABLED, BANNED, OUT_OF_RANGE, NO_ECONOMY, CANNOT_AFFORD, EMPTY_WAGER, TOO_MANY_ITEMS, ALREADY_HOSTING,
        GC_UNAVAILABLE, PERSIST_FAILED, RECOVERY_REQUIRED
    }

    public record CreateOutcome(CreateResult result, Coinflip coinflip) {
        static CreateOutcome failure(CreateResult result) {
            return new CreateOutcome(result, null);
        }
    }

    /** A player can only have one coinflip open at a time -- hosting a second is refused, not queued. */
    private boolean hasActiveCoinflip(UUID hostUuid) {
        for (Coinflip coinflip : activeCoinflips.values()) {
            if (coinflip.hostUuid().equals(hostUuid)) {
                return true;
            }
        }
        return false;
    }

    public CreateOutcome createMoneyCoinflip(Player host, double amount, UUID targetUuid) {
        if (!enabled) {
            return CreateOutcome.failure(CreateResult.DISABLED);
        }
        if (isBanned(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.BANNED);
        }
        if (hasActiveCoinflip(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.ALREADY_HOSTING);
        }
        if (amount < minMoneyWager || amount > maxMoneyWager) {
            return CreateOutcome.failure(CreateResult.OUT_OF_RANGE);
        }
        if (!EconomyHook.isAvailable()) {
            return CreateOutcome.failure(CreateResult.NO_ECONOMY);
        }
        Economy economy = EconomyHook.getEconomy();
        CoinflipStorage.CreationIntent intent = prepareCreation(host, targetUuid, CoinflipType.MONEY, amount,
                new ItemStack[0]);
        if (intent == null) return CreateOutcome.failure(CreateResult.PERSIST_FAILED);
        if (!beginDebit(intent)) return recoveryIntent(intent);
        EconomyResponse response = economy.withdrawPlayer(host, amount);
        if (!response.transactionSuccess()) {
            cancelUnusedCreation(intent);
            return CreateOutcome.failure(CreateResult.CANNOT_AFFORD);
        }
        return finishCreateAfterDebit(host,intent,CompletableFuture.completedFuture(true));
    }

    public CreateOutcome createExpCoinflip(Player host, int levels, UUID targetUuid) {
        if (!enabled) {
            return CreateOutcome.failure(CreateResult.DISABLED);
        }
        if (isBanned(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.BANNED);
        }
        if (hasActiveCoinflip(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.ALREADY_HOSTING);
        }
        if (levels < minExpWager || levels > maxExpWager) {
            return CreateOutcome.failure(CreateResult.OUT_OF_RANGE);
        }
        if (host.getLevel() < levels) {
            return CreateOutcome.failure(CreateResult.CANNOT_AFFORD);
        }
        CoinflipStorage.CreationIntent intent = prepareCreation(host, targetUuid, CoinflipType.EXP, levels,
                new ItemStack[0]);
        if (intent == null) return CreateOutcome.failure(CreateResult.PERSIST_FAILED);
        if (!beginDebit(intent)) return recoveryIntent(intent);
        host.setLevel(host.getLevel() - levels);
        return finishCreateAfterDebit(host,intent,CompletableFuture.completedFuture(true));
    }

    /** GC wagers move through {@code GcManager} the same way a money wager moves through {@code EconomyHook}. */
    public CreateOutcome createGcCoinflip(Player host, long amount, UUID targetUuid) {
        if (!enabled) {
            return CreateOutcome.failure(CreateResult.DISABLED);
        }
        if (isBanned(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.BANNED);
        }
        if (hasActiveCoinflip(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.ALREADY_HOSTING);
        }
        if (amount < minGcWager || amount > maxGcWager) {
            return CreateOutcome.failure(CreateResult.OUT_OF_RANGE);
        }
        GcManager gc = gcManager;
        if (gc == null) {
            return CreateOutcome.failure(CreateResult.GC_UNAVAILABLE);
        }
        CoinflipStorage.CreationIntent intent = prepareCreation(host, targetUuid, CoinflipType.GC, amount,
                new ItemStack[0]);
        if (intent == null) return CreateOutcome.failure(CreateResult.PERSIST_FAILED);
        if (!beginDebit(intent)) return recoveryIntent(intent);
        GcManager.DurableDebit debit=gc.tryDebitDurably(host.getUniqueId(),host.getUniqueId(),
                GcAction.COINFLIP_WAGER,amount,intent.key(),"coinflip-create:"+intent.key());
        if (!debit.accepted()) {
            cancelUnusedCreation(intent);
            return CreateOutcome.failure(CreateResult.CANNOT_AFFORD);
        }
        return finishCreateAfterDebit(host,intent,debit.persisted());
    }

    /** {@code items} must already be removed from the host's real inventory (e.g. taken out of a wager-builder GUI). */
    public CreateOutcome createItemsCoinflip(Player host, ItemStack[] items, UUID targetUuid) {
        if (!enabled) {
            return CreateOutcome.failure(CreateResult.DISABLED);
        }
        if (isBanned(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.BANNED);
        }
        if (hasActiveCoinflip(host.getUniqueId())) {
            return CreateOutcome.failure(CreateResult.ALREADY_HOSTING);
        }
        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                nonEmpty.add(item);
            }
        }
        if (nonEmpty.isEmpty()) {
            return CreateOutcome.failure(CreateResult.EMPTY_WAGER);
        }
        if (nonEmpty.size() > maxItemStacksPerWager) {
            return CreateOutcome.failure(CreateResult.TOO_MANY_ITEMS);
        }
        ItemStack[] wager = nonEmpty.toArray(new ItemStack[0]);
        CoinflipStorage.CreationIntent intent = prepareCreation(host, targetUuid, CoinflipType.ITEMS, 0, wager,
                "ESCROWED");
        if (intent == null) return CreateOutcome.failure(CreateResult.PERSIST_FAILED);
        return finishCreate(host, intent);
    }

    private CoinflipStorage.CreationIntent prepareCreation(Player host, UUID targetUuid, CoinflipType type,
            double amount, ItemStack[] items) {
        return prepareCreation(host, targetUuid, type, amount, items, "PREPARED");
    }

    private CoinflipStorage.CreationIntent prepareCreation(Player host, UUID targetUuid, CoinflipType type,
            double amount, ItemStack[] items, String state) {
        try {
            return storage.insertCreationIntent(host.getUniqueId(), targetUuid, type, amount, items,
                    System.currentTimeMillis(), state);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist Coinflip creation intent; wager was untouched.",
                    error);
            return null;
        }
    }

    private boolean beginDebit(CoinflipStorage.CreationIntent intent) {
        try {
            return storage.setCreationIntentState(intent.key(), "PREPARED", "DEBITING");
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not reserve Coinflip creation intent " + intent.key(), error);
            return false;
        }
    }

    private CreateOutcome recoveryIntent(CoinflipStorage.CreationIntent intent) {
        plugin.getLogger().severe("Coinflip creation intent " + intent.key()
                + " is in DEBITING state and requires staff reconciliation.");
        return CreateOutcome.failure(CreateResult.RECOVERY_REQUIRED);
    }

    private void cancelUnusedCreation(CoinflipStorage.CreationIntent intent) {
        try { storage.deleteCreationIntent(intent.key()); }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE,
                "Could not remove unused Coinflip creation intent " + intent.key(), error); }
    }

    private CreateOutcome finishCreate(Player host, CoinflipStorage.CreationIntent intent) {
        return finishCreate(host,intent,CompletableFuture.completedFuture(true),false);
    }

    private CreateOutcome finishCreateAfterDebit(Player host,CoinflipStorage.CreationIntent intent,
            CompletableFuture<Boolean> debitCommitted){
        return finishCreate(host,intent,debitCommitted,true);
    }

    private CreateOutcome finishCreate(Player host,CoinflipStorage.CreationIntent intent,
            CompletableFuture<Boolean> debitCommitted,boolean transitionEscrow){
        // A negative placeholder id lets the coinflip render immediately
        // while the real id comes back from the database asynchronously;
        // swapped for the real one the moment the insert completes.
        int placeholderId = localIdCounter.getAndDecrement();
        Coinflip placeholder = coinflipFrom(intent, placeholderId);
        activeCoinflips.put(placeholderId, placeholder);
        browserChanged();

        CompletableFuture<CreationActivation> insert = debitCommitted.thenApplyAsync(committed -> {
            if(!committed)return new CreationActivation(-1,true);
            try {
                if(transitionEscrow&&!storage.setCreationIntentState(intent.key(),"DEBITING","ESCROWED"))
                    return new CreationActivation(-1,true);
                return new CreationActivation(storage.activateCreationIntent(intent),false);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        track(insert);
        insert.whenComplete((activation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            activeCoinflips.remove(placeholderId);
            browserChanged();
            if(activation!=null&&activation.recoveryRequired()){
                plugin.getLogger().severe("Coinflip creation intent "+intent.key()+" requires staff reconciliation.");
                if(host.isOnline())host.sendMessage(messages.get(host,"coinflip.create-recovery-required"));
                return;
            }
            if (error != null || activation == null || activation.id() < 0) {
                plugin.getLogger().log(Level.SEVERE, "Failed to activate Coinflip creation intent " + intent.key()
                        + "; keeping it as durable escrow for startup recovery.", error);
                activeCoinflips.put(placeholderId, placeholder);
                if (host.isOnline()) {
                    host.sendMessage(messages.get(host, "coinflip.create-failed"));
                }
                return;
            }
            int id=activation.id();
            Coinflip created = coinflipFrom(intent, id);
            activeCoinflips.put(id, created);
            browserChanged();
            if (announcements != null) {
                announcements.broadcast(AnnouncementCategory.COINFLIPS,
                        "coinflip.public-created-" + created.type().name().toLowerCase(java.util.Locale.ROOT),
                        "player", host.getName());
            }
        }));

        return new CreateOutcome(CreateResult.OK, placeholder);
    }

    private record CreationActivation(int id,boolean recoveryRequired){}

    private Coinflip coinflipFrom(CoinflipStorage.CreationIntent intent, int id) {
        return new Coinflip(id, intent.hostUuid(), intent.targetUuid(), intent.type(), intent.amount(), intent.items(),
                intent.createdAt());
    }

    // ---- Cancellation (self or admin) ----

    public boolean canCancel(Player actor, Coinflip coinflip) {
        return coinflip.hostUuid().equals(actor.getUniqueId()) || actor.hasPermission("vertex.coinflip.remove");
    }

    /**
     * Removes an active coinflip and returns its wager to the host. Caller
     * must already have checked {@link #canCancel}.
     *
     * @return false if it was already gone (played or cancelled by someone
     *         else in the same instant) -- nothing was refunded twice.
     */
    public boolean cancel(Coinflip coinflip, Player actor) {
        if (coinflip.isPending() || !activeCoinflips.remove(coinflip.id(), coinflip)) {
            return false;
        }
        browserChanged();
        boolean adminCancel = !coinflip.hostUuid().equals(actor.getUniqueId());
        try {
            if (!storage.cancelCoinflip(coinflip, adminCancel ? actor.getUniqueId() : null,
                    summarize(coinflip), System.currentTimeMillis())) {
                activeCoinflips.put(coinflip.id(), coinflip);
                browserChanged();
                return false;
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist cancelled Coinflip " + coinflip.id(), error);
            activeCoinflips.put(coinflip.id(), coinflip);
            browserChanged();
            return false;
        }
        processPendingPayouts(coinflip.hostUuid());
        return true;
    }

    // ---- Resolution ----

    public enum PlayResult {
        OK, GONE, BANNED, IS_HOST, NOT_TARGETED, CANNOT_AFFORD, NEEDS_ITEM_WAGER, ALREADY_PENDING_MATCH,
        ALREADY_TAKING_ONE, GC_UNAVAILABLE
    }

    public record PlayOutcome(PlayResult result, boolean opponentWon) {
        static PlayOutcome failure(PlayResult result) {
            return new PlayOutcome(result, false);
        }
    }

    private PlayResult validatePlay(Coinflip coinflip, Player opponent) {
        if (isBanned(opponent.getUniqueId())) {
            return PlayResult.BANNED;
        }
        if (coinflip.hostUuid().equals(opponent.getUniqueId())) {
            return PlayResult.IS_HOST;
        }
        if (!coinflip.isOpenTo(opponent.getUniqueId())) {
            return PlayResult.NOT_TARGETED;
        }
        return PlayResult.OK;
    }

    /** For MONEY/EXP coinflips: the opponent's matching wager is taken from their live balance/levels. */
    public PlayOutcome play(int coinflipId, Player opponent) {
        Coinflip coinflip = activeCoinflips.get(coinflipId);
        // Not durable yet: playing it would let the still-running insert
        // reopen the same wager once it lands.
        if (coinflip == null || coinflip.isPending()) {
            return PlayOutcome.failure(PlayResult.GONE);
        }
        PlayResult validation = validatePlay(coinflip, opponent);
        if (validation != PlayResult.OK) {
            return PlayOutcome.failure(validation);
        }
        if (coinflip.type() == CoinflipType.ITEMS) {
            return PlayOutcome.failure(PlayResult.NEEDS_ITEM_WAGER);
        }
        GcManager gc = gcManager;
        if (coinflip.type() == CoinflipType.GC && gc == null) {
            return PlayOutcome.failure(PlayResult.GC_UNAVAILABLE);
        }

        if (coinflip.type() == CoinflipType.MONEY) {
            if (!EconomyHook.isAvailable() || !EconomyHook.getEconomy().has(opponent, coinflip.amount())) {
                return PlayOutcome.failure(PlayResult.CANNOT_AFFORD);
            }
        } else if (coinflip.type() != CoinflipType.GC && opponent.getLevel() < coinflip.amount()) {
            return PlayOutcome.failure(PlayResult.CANNOT_AFFORD);
        }

        if (!activeCoinflips.remove(coinflipId, coinflip)) {
            return PlayOutcome.failure(PlayResult.GONE);
        }
        browserChanged();

        if (coinflip.type() == CoinflipType.MONEY) {
            Economy economy = EconomyHook.getEconomy();
            EconomyResponse response = economy.withdrawPlayer(opponent, coinflip.amount());
            if (!response.transactionSuccess()) {
                activeCoinflips.put(coinflipId, coinflip);
                browserChanged();
                return PlayOutcome.failure(PlayResult.CANNOT_AFFORD);
            }
        } else if (coinflip.type() != CoinflipType.GC) {
            opponent.setLevel(opponent.getLevel() - (int) coinflip.amount());
        }

        boolean opponentWon = ThreadLocalRandom.current().nextBoolean();
        UUID winnerUuid = opponentWon ? opponent.getUniqueId() : coinflip.hostUuid();
        long resolvedAt = System.currentTimeMillis();
        double keepFraction = 1.0 - (houseFeePercent / 100.0);
        double payout = coinflip.amount() + coinflip.amount() * keepFraction;

        // The outcome is committed before a single coin moves. If it cannot
        // be, both wagers go back and the coinflip returns to the list, so a
        // failure leaves the world exactly as it was.
        if (!persistResolution(coinflip, opponent.getUniqueId(), winnerUuid, resolvedAt, null, payout, null)) {
            if (coinflip.type() == CoinflipType.MONEY) {
                EconomyHook.getEconomy().depositPlayer(opponent, coinflip.amount());
            } else if (coinflip.type() != CoinflipType.GC) {
                opponent.setLevel(opponent.getLevel() + (int) coinflip.amount());
            }
            activeCoinflips.put(coinflipId, coinflip);
            browserChanged();
            return PlayOutcome.failure(PlayResult.GONE);
        }

        processPendingPayouts(winnerUuid);

        if (coinflip.type() == CoinflipType.GC) {
            gc.refreshBalance(opponent.getUniqueId());
            me.vertex.core.audit.LargeTransactionAudit.record(plugin, (long) coinflip.amount(),
                    "COINFLIP_GC", Bukkit.getOfflinePlayer(coinflip.hostUuid()), opponent);
        }

        finishResolution(coinflip, opponent.getUniqueId(), winnerUuid, resolvedAt);
        return new PlayOutcome(PlayResult.OK, opponentWon);
    }

    // ---- Item-match approval (ITEMS coinflips only) ----

    /**
     * A host's item wager can be inflated bait against junk from the
     * opponent, so an opponent joining an ITEMS coinflip never resolves
     * it immediately -- their chosen items (already pulled from their
     * real inventory, e.g. out of a confirmed wager-builder GUI) are
     * held here, persisted, and wait for the host to {@link
     * #approveItemMatch} or {@link #denyItemMatch}. Only one match can
     * be pending per coinflip at a time.
     */
    public PlayResult requestItemMatch(int coinflipId, Player opponent, ItemStack[] opponentItems) {
        Coinflip coinflip = activeCoinflips.get(coinflipId);
        if (coinflip == null || coinflip.isPending()) {
            return PlayResult.GONE;
        }
        PlayResult validation = validatePlay(coinflip, opponent);
        if (validation != PlayResult.OK) {
            return validation;
        }
        if (coinflip.type() != CoinflipType.ITEMS) {
            return PlayResult.NEEDS_ITEM_WAGER;
        }
        if (isTakingAMatch(opponent.getUniqueId())) {
            return PlayResult.ALREADY_TAKING_ONE;
        }

        long requestedAt = System.currentTimeMillis();
        UUID opponentUuid = opponent.getUniqueId();
        int placeholderId = localIdCounter.getAndDecrement();
        CoinflipPendingMatch placeholder = new CoinflipPendingMatch(placeholderId, coinflipId, opponentUuid, opponentItems, requestedAt);
        if (pendingItemMatches.putIfAbsent(coinflipId, placeholder) != null) {
            return PlayResult.ALREADY_PENDING_MATCH;
        }
        browserChanged();

        try {
            int id = storage.insertPendingMatch(coinflipId, opponentUuid, opponentItems, requestedAt);
            if (id < 0) throw new SQLException("No generated pending-match id was returned");
            pendingItemMatches.replace(coinflipId, placeholder,
                    new CoinflipPendingMatch(id, coinflipId, opponentUuid, opponentItems, requestedAt));
            browserChanged();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to persist a pending Coinflip item match for coinflip " + coinflipId
                            + " -- refunding the opponent.", error);
            pendingItemMatches.remove(coinflipId, placeholder);
            browserChanged();
            queueClaim(opponentUuid, opponentItems);
            return PlayResult.GONE;
        }

        Player host = Bukkit.getPlayer(coinflip.hostUuid());
        if (host != null) {
            host.sendMessage(messages.get(host, "coinflip.item-match-requested",
                    "player", opponent.getName(), "id", String.valueOf(coinflipId)));
        }
        return PlayResult.OK;
    }

    public enum ApprovalResult { OK, NOT_HOST, NO_PENDING_MATCH, GONE }

    public record ApproveOutcome(ApprovalResult result, boolean opponentWon) {
        static ApproveOutcome failure(ApprovalResult result) {
            return new ApproveOutcome(result, false);
        }
    }

    /** The host accepts the opponent's proposed item wager; the coinflip resolves immediately. */
    public ApproveOutcome approveItemMatch(int coinflipId, Player host) {
        Coinflip coinflip = activeCoinflips.get(coinflipId);
        if (coinflip == null || coinflip.isPending()) {
            discardOrphanedMatch(coinflipId);
            return ApproveOutcome.failure(ApprovalResult.GONE);
        }
        if (!coinflip.hostUuid().equals(host.getUniqueId())) {
            return ApproveOutcome.failure(ApprovalResult.NOT_HOST);
        }
        CoinflipPendingMatch match = pendingItemMatches.get(coinflipId);
        if (match == null || match.id() < 0) {
            return ApproveOutcome.failure(ApprovalResult.NO_PENDING_MATCH);
        }
        if (!activeCoinflips.remove(coinflipId, coinflip)) {
            return ApproveOutcome.failure(ApprovalResult.GONE);
        }
        pendingItemMatches.remove(coinflipId, match);
        browserChanged();

        boolean opponentWon = ThreadLocalRandom.current().nextBoolean();
        UUID winnerUuid = opponentWon ? match.opponentUuid() : coinflip.hostUuid();
        long resolvedAt = System.currentTimeMillis();
        List<ItemStack> combined = new ArrayList<>(List.of(coinflip.items()));
        combined.addAll(List.of(match.items()));

        // Committed before either side's items are handed to the winner. If it
        // fails, both wagers stay escrowed and the match goes back to pending,
        // so nobody gains or loses anything.
        if (!persistResolution(coinflip, match.opponentUuid(), winnerUuid, resolvedAt,
                combined.toArray(new ItemStack[0]), 0D, match.id())) {
            activeCoinflips.put(coinflipId, coinflip);
            pendingItemMatches.put(coinflipId, match);
            browserChanged();
            return ApproveOutcome.failure(ApprovalResult.GONE);
        }

        finishResolution(coinflip, match.opponentUuid(), winnerUuid, resolvedAt);
        return new ApproveOutcome(ApprovalResult.OK, opponentWon);
    }

    /** The host rejects the opponent's proposed item wager; their items are returned and the coinflip stays open. */
    public ApprovalResult denyItemMatch(int coinflipId, Player host) {
        Coinflip coinflip = activeCoinflips.get(coinflipId);
        if (coinflip == null) {
            discardOrphanedMatch(coinflipId);
            return ApprovalResult.GONE;
        }
        if (!coinflip.hostUuid().equals(host.getUniqueId())) {
            return ApprovalResult.NOT_HOST;
        }
        CoinflipPendingMatch match = pendingItemMatches.get(coinflipId);
        if (match == null) {
            return ApprovalResult.NO_PENDING_MATCH;
        }
        if (!refundPendingMatch(match)) return ApprovalResult.GONE;
        pendingItemMatches.remove(coinflipId, match);
        browserChanged();
        Player opponent = Bukkit.getPlayer(match.opponentUuid());
        if (opponent != null) {
            opponent.sendMessage(messages.get(opponent, "coinflip.item-match-denied"));
        }
        return ApprovalResult.OK;
    }

    /** A pending match whose coinflip is already gone (resolved/cancelled some other way) can never be decided; refund it. */
    private void discardOrphanedMatch(int coinflipId) {
        CoinflipPendingMatch orphan = pendingItemMatches.remove(coinflipId);
        if (orphan != null) {
            browserChanged();
            if (!refundPendingMatch(orphan)) pendingItemMatches.put(coinflipId, orphan);
        }
    }

    /** Every pending match older than {@code item-match-approval-timeout-seconds} is auto-denied and refunded. */
    public void sweepExpiredItemMatches() {
        long cutoff = System.currentTimeMillis() - itemMatchApprovalTimeoutMillis;
        for (CoinflipPendingMatch match : List.copyOf(pendingItemMatches.values())) {
            if (match.requestedAtMillis() > cutoff) {
                continue;
            }
            if (!refundPendingMatch(match) || !pendingItemMatches.remove(match.coinflipId(), match)) continue;
            browserChanged();
            Player opponent = Bukkit.getPlayer(match.opponentUuid());
            if (opponent != null) {
                opponent.sendMessage(messages.get(opponent, "coinflip.item-match-expired"));
            }
            Coinflip coinflip = activeCoinflips.get(match.coinflipId());
            Player host = coinflip == null ? null : Bukkit.getPlayer(coinflip.hostUuid());
            if (host != null) {
                host.sendMessage(messages.get(host, "coinflip.item-match-expired-host"));
            }
        }
    }

    private boolean refundPendingMatch(CoinflipPendingMatch match) {
        try {
            return storage.refundPendingMatch(match, System.currentTimeMillis());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to durably refund pending Coinflip match " + match.id(), error);
            return false;
        }
    }

    /**
     * Commits the decided outcome before anybody is paid.
     *
     * <p>Deliberately synchronous. The payout used to happen first and persist
     * afterwards, so a crash in between left the winner paid while the
     * coinflip row survived -- making the same wager playable again after a
     * restart. The result is decided server-side beforehand and written in one
     * transaction, so what is committed is exactly what gets paid.
     *
     * @return false when nothing committed, meaning the caller must not pay
     *         out and should return the wagers instead
     */
    private boolean persistResolution(Coinflip coinflip, UUID opponentUuid, UUID winnerUuid, long resolvedAt,
            ItemStack[] payoutItems, double payoutAmount, Integer pendingMatchId) {
        try {
            return storage.resolveCoinflip(coinflip, opponentUuid, winnerUuid, summarize(coinflip), resolvedAt,
                    payoutItems, payoutAmount, pendingMatchId);
        } catch (me.vertex.core.gc.GcStorage.BalanceRejectedException insufficient) {
            if (gcManager != null) gcManager.refreshBalance(opponentUuid);
            return false;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to persist resolved Coinflip " + coinflip.id() + " -- nobody was paid.", e);
            return false;
        }
    }

    /** Retries every durable currency payout, optionally for one player only. */
    public void processPendingPayouts(UUID ownerFilter) {
        if (shuttingDown.get()) return;
        CompletableFuture<List<CoinflipStorage.PendingPayout>> reserve = CompletableFuture.supplyAsync(() -> {
            List<CoinflipStorage.PendingPayout> reserved = new ArrayList<>();
            try {
                for (CoinflipStorage.PendingPayout payout : storage.loadPendingPayouts()) {
                    if (ownerFilter != null && !ownerFilter.equals(payout.ownerUuid())) continue;
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
                                "Could not reserve Coinflip payout " + payout.key(), error);
                    }
                }
                return reserved;
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, payoutExecutor);
        track(reserve);
        reserve.whenComplete((reserved, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load pending Coinflip payouts", error);
                return;
            }
            if (reserved.isEmpty()) return;
            if (shuttingDown.get() || !plugin.isEnabled()) {
                for (CoinflipStorage.PendingPayout payout : reserved) finishPendingPayout(payout.key(), false);
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

    /** Performs only Bukkit/Vault work; every SQL transition stays off the primary thread. */
    private void deliverPendingPayout(CoinflipStorage.PendingPayout payout) {
        if (shuttingDown.get()) {
            finishPendingPayout(payout.key(), false);
            return;
        }
        if (payout.currency() == CoinflipType.GC) {
            GcManager gc = gcManager;
            if (gc == null) {
                finishPendingPayout(payout.key(), false);
                return;
            }
            gc.creditDurably(payout.ownerUuid(), null, GcAction.COINFLIP_PAYOUT,
                    Math.round(payout.amount()), payout.key()).whenComplete((committed, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Coinflip GC payout " + payout.key() + " could not be resolved", error);
                    pendingPayoutsInProgress.remove(payout.key());
                } else {
                    finishPendingPayout(payout.key(), Boolean.TRUE.equals(committed));
                }
            });
            return;
        }

        boolean delivered = false;
        boolean definitelyNotDelivered = false;
        try {
            switch (payout.currency()) {
                case MONEY -> {
                    if (EconomyHook.isAvailable()) {
                        delivered = EconomyHook.getEconomy().depositPlayer(
                                Bukkit.getOfflinePlayer(payout.ownerUuid()), payout.amount()).transactionSuccess();
                        definitelyNotDelivered = !delivered;
                    } else definitelyNotDelivered = true;
                }
                case EXP -> {
                    Player online = Bukkit.getPlayer(payout.ownerUuid());
                    if (online != null) {
                        online.setLevel(online.getLevel() + (int) Math.round(payout.amount()));
                        delivered = true;
                    } else definitelyNotDelivered = true;
                }
                case ITEMS -> definitelyNotDelivered = true;
                case GC -> { /* handled above */ }
            }
        } catch (RuntimeException uncertain) {
            plugin.getLogger().log(Level.SEVERE, "Coinflip payout " + payout.key()
                    + " has an uncertain external result and will not be replayed automatically.", uncertain);
        }
        if (delivered) finishPendingPayout(payout.key(), true);
        else if (definitelyNotDelivered) finishPendingPayout(payout.key(), false);
        else pendingPayoutsInProgress.remove(payout.key());
    }

    private void finishPendingPayout(String key, boolean delivered) {
        CompletableFuture<Void> finish = CompletableFuture.runAsync(() -> {
            try {
                if (delivered) storage.deletePendingPayout(key);
                else storage.releasePendingPayout(key);
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Could not record Coinflip payout " + key, error);
            } finally {
                pendingPayoutsInProgress.remove(key);
            }
        }, payoutExecutor);
        track(finish);
    }

    public List<CoinflipStorage.PendingPayout> uncertainPayouts() {
        try { return storage.loadUncertainPayouts(); }
        catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect uncertain Coinflip payouts", error);
            return List.of();
        }
    }

    public boolean resolveUncertainPayout(String key, boolean paid) {
        try {
            if (paid) {
                if (!storage.acknowledgeUncertainPayout(key)) return false;
            } else if (!storage.releasePendingPayout(key)) return false;
            if (!paid) processPendingPayouts(null);
            plugin.getLogger().warning("Coinflip payout " + key + " was manually marked "
                    + (paid ? "PAID" : "RETRY") + ".");
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not reconcile Coinflip payout " + key, error);
            return false;
        }
    }

    private void reportUncertainPayouts() {
        List<CoinflipStorage.PendingPayout> uncertain = uncertainPayouts();
        if (!uncertain.isEmpty()) plugin.getLogger().severe("Coinflip has " + uncertain.size()
                + " uncertain external payout(s). Inspect with /cf payouts before retrying them.");
    }

    /** Currency-wager creations whose external debit result needs a human decision. */
    public List<CoinflipStorage.CreationIntent> unresolvedCreationIntents() {
        try {
            return storage.loadCreationIntents().stream()
                    .filter(intent -> "DEBITING".equals(intent.state()))
                    .toList();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect Coinflip creation intents", error);
            return List.of();
        }
    }

    public Optional<CoinflipStorage.CreationIntent> creationIntent(String key) {
        try {
            return storage.loadCreationIntent(key).filter(intent -> "DEBITING".equals(intent.state()));
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect Coinflip creation intent " + key, error);
            return Optional.empty();
        }
    }

    /** Applies one irreversible, permanently audited staff decision. */
    public CoinflipStorage.IntentResolution resolveCreationIntent(String key, boolean debited, Player actor) {
        try {
            CoinflipStorage.IntentResolution resolution = storage.resolveCreationIntent(key, debited,
                    actor.getUniqueId(), actor.getName(), System.currentTimeMillis());
            if (resolution.status() == CoinflipStorage.IntentResolutionStatus.ACTIVATED) {
                activeCoinflips.put(resolution.coinflipId(), coinflipFrom(resolution.intent(), resolution.coinflipId()));
                browserChanged();
            }
            if (resolution.status() == CoinflipStorage.IntentResolutionStatus.ACTIVATED
                    || resolution.status() == CoinflipStorage.IntentResolutionStatus.DISCARDED) {
                plugin.getLogger().warning(actor.getName() + " resolved Coinflip creation intent " + key + " as "
                        + (debited ? "DEBITED" : "NOT_DEBITED") + ".");
            }
            return resolution;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not reconcile Coinflip creation intent " + key, error);
            return new CoinflipStorage.IntentResolution(CoinflipStorage.IntentResolutionStatus.STORAGE_ERROR,
                    null, -1, null);
        }
    }

    private void finishResolution(Coinflip coinflip, UUID opponentUuid, UUID winnerUuid, long resolvedAt) {
        presentResolvedCoinflip(coinflip.hostUuid(), opponentUuid, winnerUuid.equals(coinflip.hostUuid()),
                Bukkit.getOfflinePlayer(coinflip.hostUuid()), Bukkit.getOfflinePlayer(opponentUuid), resolvedAt);
    }

    /**
     * Opens the same animation for both connected participants in this tick,
     * then reveals both chat results from one shared server timer. The timer
     * remains authoritative if a player closes the GUI, changes inventory,
     * or disconnects while the reel is running.
     */
    private void presentResolvedCoinflip(UUID hostUuid, UUID opponentUuid, boolean hostWon, OfflinePlayer host,
            OfflinePlayer opponent, long resolvedAt) {
        Player hostPlayer = Bukkit.getPlayer(hostUuid);
        Player opponentPlayer = Bukkit.getPlayer(opponentUuid);
        if (hostPlayer != null) {
            CoinflipAnimationMenu.play(plugin, hostPlayer, messages, host, opponent, hostWon, animationDurationTicks);
        }
        if (opponentPlayer != null) {
            CoinflipAnimationMenu.play(plugin, opponentPlayer, messages, host, opponent, hostWon, animationDurationTicks);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            deliverResultIfOnline(hostUuid, hostWon, resolvedAt);
            deliverResultIfOnline(opponentUuid, !hostWon, resolvedAt);
        }, animationDurationTicks);
    }

    private void deliverResultIfOnline(UUID uuid, boolean won, long resolvedAt) {
        Player player = Bukkit.getPlayer(uuid);
        ResultNotificationKey key = new ResultNotificationKey(uuid, won, resolvedAt);
        if (player == null || !resultNotificationsInFlight.add(key)) {
            return;
        }
        player.sendMessage(messages.get(player, won ? "coinflip.you-won" : "coinflip.you-lost"));
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteResultNotifications(uuid, won, resolvedAt);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear delivered Coinflip result notification for " + uuid, e);
            }
        }));
    }

    /**
     * Delivers a result held for a player who was offline when the shared
     * animation ended. If they reconnect before that time, this intentionally
     * waits out the remaining duration instead of spoiling the coinflip.
     */
    public void applyPendingResultNotifications(Player player) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<List<CoinflipResultNotification>> read = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.loadResultNotifications(uuid);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        track(read);
        read.whenComplete((notifications, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to load pending Coinflip results for " + uuid, error);
                return;
            }
            if (!player.isOnline() || notifications == null || notifications.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            long earliestFuture = Long.MAX_VALUE;
            List<Integer> deliveredIds = new ArrayList<>();
            for (CoinflipResultNotification notification : notifications) {
                long revealAt = notification.createdAtMillis() + (animationDurationTicks * 50L);
                if (revealAt > now) {
                    earliestFuture = Math.min(earliestFuture, revealAt);
                    continue;
                }
                ResultNotificationKey key = new ResultNotificationKey(notification.recipientUuid(), notification.won(),
                        notification.createdAtMillis());
                if (!resultNotificationsInFlight.add(key)) {
                    continue;
                }
                player.sendMessage(messages.get(player, notification.won() ? "coinflip.you-won" : "coinflip.you-lost"));
                deliveredIds.add(notification.id());
            }
            if (!deliveredIds.isEmpty()) {
                track(CompletableFuture.runAsync(() -> {
                    try {
                        storage.deleteResultNotificationsById(deliveredIds);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to clear delivered offline Coinflip result notifications for " + uuid, e);
                    }
                }));
            }
            if (earliestFuture != Long.MAX_VALUE) {
                long delayTicks = Math.max(1L, (earliestFuture - now + 49L) / 50L);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        applyPendingResultNotifications(player);
                    }
                }, delayTicks);
            }
        }));
    }

    private record ResultNotificationKey(UUID recipientUuid, boolean won, long createdAtMillis) {
    }

    private String summarize(Coinflip coinflip) {
        return switch (coinflip.type()) {
            case MONEY -> EconomyHook.format(coinflip.amount());
            case EXP -> (int) coinflip.amount() + " levels";
            case ITEMS -> summarizeItems(coinflip.items());
            case GC -> Numbers.formatFull((long) coinflip.amount()) + " GC";
        };
    }

    private static String summarizeItems(ItemStack[] items) {
        StringBuilder builder = new StringBuilder();
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(item.getAmount()).append("x ").append(item.getType());
        }
        return builder.isEmpty() ? "no items" : builder.toString();
    }

    // ---- Claims (item payouts) ----

    public boolean hasClaims(UUID uuid) {
        try {
            return storage.hasClaims(uuid);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check Coinflip claims for " + uuid, e);
            return false;
        }
    }

    public List<ItemStack> loadClaimItems(UUID uuid) {
        List<ItemStack> items = new ArrayList<>();
        try {
            for (CoinflipClaim claim : storage.loadClaims(uuid)) {
                items.addAll(List.of(claim.items()));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load Coinflip claims for " + uuid, e);
        }
        return items;
    }

    /** Loads SQL-backed claim contents without blocking an inventory click. */
    public CompletableFuture<List<ItemStack>> loadClaimItemsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> loadClaimItems(uuid));
    }

    /** Checks the claim stash without blocking GUI rendering. */
    public CompletableFuture<Boolean> hasClaimsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> hasClaims(uuid));
    }

    public void clearClaims(UUID uuid) {
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteClaims(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear Coinflip claims for " + uuid, e);
            }
        }));
    }

    public record ClaimBatch(List<Integer> ids, List<ItemStack> items) {
        static ClaimBatch empty() {
            return new ClaimBatch(List.of(), List.of());
        }
    }

    public enum ClaimResult { CLAIMED, EMPTY, FULL, OFFLINE, BUSY, FAILED }

    public boolean queueOverflow(Player player, java.util.Collection<ItemStack> items, String source) {
        return me.vertex.core.storage.DeliveryManager.queueOverflow(plugin, player, items, source);
    }

    Plugin plugin() {
        return plugin;
    }

    public CompletableFuture<ClaimResult> deliverClaims(Player player) {
        UUID owner = player.getUniqueId();
        if (!claimsInProgress.add(owner)) return CompletableFuture.completedFuture(ClaimResult.BUSY);
        CompletableFuture<ClaimDeliveryBatch> load = CompletableFuture.supplyAsync(() -> {
            try {
                List<CoinflipStorage.ClaimReservation> reservations = new ArrayList<>(
                        storage.loadDeliveringClaims(owner));
                CoinflipStorage.ClaimReservation fresh = storage.reserveClaims(owner);
                if (!fresh.claims().isEmpty()) reservations.add(fresh);
                return new ClaimDeliveryBatch(reservations,
                        fresh.claims().isEmpty() ? null : fresh.token());
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        track(load);
        CompletableFuture<ClaimResult> result = load.thenCompose(batch -> {
            CompletableFuture<ClaimResult> delivered = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> deliverReservations(player, batch, delivered));
            return delivered;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Failed to reserve Coinflip claims for " + owner, error);
            return ClaimResult.FAILED;
        });
        result.whenComplete((ignored, error) -> claimsInProgress.remove(owner));
        return result;
    }

    private record ClaimDeliveryBatch(List<CoinflipStorage.ClaimReservation> reservations, String freshToken) { }

    private void deliverReservations(Player player, ClaimDeliveryBatch batch,
            CompletableFuture<ClaimResult> result) {
        List<CoinflipStorage.ClaimReservation> reservations = batch.reservations();
        if (!player.isOnline()) {
            releaseFreshReservation(player.getUniqueId(), batch, result, ClaimResult.OFFLINE);
        } else if (reservations.isEmpty()) {
            ClaimDelivery.clearSourceMarkers(player, plugin, "coinflip");
            result.complete(ClaimResult.EMPTY);
        } else {
            deliverReservationAt(player, batch, 0, false, result);
        }
    }

    private void deliverReservationAt(Player player, ClaimDeliveryBatch batch, int index,
            boolean deliveredAny, CompletableFuture<ClaimResult> result) {
        List<CoinflipStorage.ClaimReservation> reservations = batch.reservations();
        if (index >= reservations.size()) {
            result.complete(deliveredAny ? ClaimResult.CLAIMED : ClaimResult.EMPTY);
            return;
        }
        CoinflipStorage.ClaimReservation reservation = reservations.get(index);
        List<ClaimDelivery.TaggedItem> expected = new ArrayList<>();
        for (CoinflipClaim claim : reservation.claims()) {
            for (int itemIndex = 0; itemIndex < claim.items().length; itemIndex++) {
                ItemStack item = claim.items()[itemIndex];
                if (item != null && !item.isEmpty()) expected.add(ClaimDelivery.tagged(plugin, "coinflip",
                        reservation.token(), claim.id(), itemIndex, item));
            }
        }
        List<ClaimDelivery.TaggedItem> missing = ClaimDelivery.missing(player, plugin, expected);
        if (!ClaimDelivery.canFit(player, missing) || !ClaimDelivery.add(player, missing)) {
            releaseFreshReservation(player.getUniqueId(), batch, result, ClaimResult.FULL);
            return;
        }
        CompletableFuture<Integer> complete = CompletableFuture.supplyAsync(() -> {
            try { return storage.completeReservation(player.getUniqueId(), reservation.token()); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        track(complete);
        complete.whenComplete((changed, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || changed == null || changed != reservation.claims().size()) {
                plugin.getLogger().log(Level.SEVERE, "Could not acknowledge Coinflip claim reservation "
                        + reservation.token() + "; tagged items remain reconcilable.", error);
                result.complete(ClaimResult.FAILED);
                return;
            }
            ClaimDelivery.clearMarkers(player, plugin, "coinflip", reservation.token());
            deliverReservationAt(player, batch, index + 1, true, result);
        }));
    }

    private void releaseFreshReservation(UUID owner, ClaimDeliveryBatch batch,
            CompletableFuture<ClaimResult> result, ClaimResult releasedResult) {
        if (batch.freshToken() == null) {
            result.complete(releasedResult);
            return;
        }
        List<CoinflipStorage.ClaimReservation> fresh = batch.reservations().stream()
                .filter(reservation -> reservation.token().equals(batch.freshToken())).toList();
        releaseReservations(owner, fresh, result, releasedResult);
    }

    private void releaseReservations(UUID owner, List<CoinflipStorage.ClaimReservation> reservations,
            CompletableFuture<ClaimResult> result, ClaimResult releasedResult) {
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            for (CoinflipStorage.ClaimReservation reservation : reservations) {
                try { storage.releaseReservation(owner, reservation.token()); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            }
        });
        track(release);
        release.whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not release Coinflip claim reservation for " + owner,
                        error);
                result.complete(ClaimResult.FAILED);
            } else result.complete(releasedResult);
        });
    }

    /** Compatibility API retained for tests and storage-only consumers. */
    public CompletableFuture<ClaimBatch> takeClaims(UUID uuid) {
        if (!claimsInProgress.add(uuid)) {
            return CompletableFuture.completedFuture(ClaimBatch.empty());
        }
        CompletableFuture<ClaimBatch> take = CompletableFuture.supplyAsync(() -> {
            try {
                List<CoinflipClaim> claims = storage.takeClaims(uuid);
                List<Integer> ids = new ArrayList<>(claims.size());
                List<ItemStack> items = new ArrayList<>();
                for (CoinflipClaim claim : claims) {
                    ids.add(claim.id());
                    for (ItemStack item : claim.items()) {
                        if (item != null && !item.isEmpty()) {
                            items.add(item.clone());
                        }
                    }
                }
                return new ClaimBatch(List.copyOf(ids), List.copyOf(items));
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        track(take);
        take.whenComplete((ignored, error) -> claimsInProgress.remove(uuid));
        return take;
    }

    /** Restores a reserved claim if its player disconnected before main-thread delivery. */
    public void restoreClaimBatch(UUID uuid, ClaimBatch batch) {
        if (batch == null || batch.items().isEmpty()) {
            return;
        }
        ItemStack[] items = batch.items().stream().map(ItemStack::clone).toArray(ItemStack[]::new);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.insertClaim(uuid, items, System.currentTimeMillis());
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to restore undelivered Coinflip claims for " + uuid, error);
            }
        }));
    }

    private void queueClaim(UUID winnerUuid, ItemStack[] items) {
        long wonAt = System.currentTimeMillis();
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.insertClaim(winnerUuid, items, wonAt);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to persist a Coinflip item payout for " + winnerUuid + " -- items: "
                                + java.util.Arrays.toString(items), e);
            }
        }));
    }

    /** Gives items directly if possible and retains all overflow in the durable claim stash. */
    private void giveOrClaim(Player player, ItemStack[] items) {
        if (player.isOnline()) {
            for (ItemStack leftover : player.getInventory().addItem(items).values()) {
                queueClaim(player.getUniqueId(), new ItemStack[] { leftover });
            }
        } else {
            queueClaim(player.getUniqueId(), items);
        }
    }

    // ---- Audit log ----

    public List<CoinflipLogEntry> loadLog(UUID playerFilter, int limit, int offset) {
        try {
            return storage.loadLog(playerFilter, limit, offset);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load the Coinflip audit log.", e);
            return List.of();
        }
    }

    public void pruneLog() {
        if (logRetentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(logRetentionDays).toMillis();
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteLogEntriesOlderThan(cutoff);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to prune the Coinflip audit log.", e);
            }
        }));
    }

    // ---- Shutdown / migration draining ----

    private void track(CompletableFuture<?> write) {
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    public void shutdown() {
        shuttingDown.set(true);
        for (String key : List.copyOf(pendingPayoutsInProgress)) {
            CompletableFuture<Void> clear = CompletableFuture.runAsync(() -> {
                try {
                    storage.releasePendingPayout(key);
                } catch (Exception error) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Could not release Coinflip payout " + key + " during shutdown", error);
                } finally {
                    pendingPayoutsInProgress.remove(key);
                }
            }, payoutExecutor);
            track(clear);
        }
        awaitWrites();
        payoutExecutor.shutdown();
        try {
            if (!payoutExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                payoutExecutor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            payoutExecutor.shutdownNow();
        }
    }

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
            plugin.getLogger().warning("Timed out waiting for pending Coinflip writes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while waiting for Coinflip writes.", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Coinflip writes.", e);
        }
    }
}
