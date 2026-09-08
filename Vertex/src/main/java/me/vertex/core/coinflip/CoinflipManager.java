package me.vertex.core.coinflip;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Owns every Coinflip rule and all of its state. In-memory maps
 * ({@link #activeCoinflips}, bans, pending experience) are the moment-to-
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
    private final File file;

    private volatile boolean enabled;
    private volatile double minMoneyWager;
    private volatile double maxMoneyWager;
    private volatile int minExpWager;
    private volatile int maxExpWager;
    private volatile int maxItemStacksPerWager;
    private volatile double houseFeePercent;
    private volatile int selfBanDays;
    private volatile long itemIconCycleTicks;
    private volatile long guiRefreshIntervalTicks;
    private volatile long logRetentionDays;
    private volatile boolean broadcastResults;
    private volatile long itemMatchApprovalTimeoutMillis;

    private final Map<Integer, Coinflip> activeCoinflips = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bans = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingExp = new ConcurrentHashMap<>();
    /** uuid -> the deadline for a "/cf ban confirm" to actually apply. */
    private final Map<UUID, Long> pendingBanConfirmations = new ConcurrentHashMap<>();
    /** uuid -> whether that player wants the coin-flip result animation. Defaults to on. */
    private final Map<UUID, Boolean> animationsEnabled = new ConcurrentHashMap<>();
    /** coinflip id -> the one opponent item-wager awaiting that coinflip's host to approve or deny. */
    private final Map<Integer, CoinflipPendingMatch> pendingItemMatches = new ConcurrentHashMap<>();

    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final AtomicInteger localIdCounter = new AtomicInteger(-1);
    /** Increments only when a browser-visible listing or pending-match state changes. */
    private final AtomicLong browserVersion = new AtomicLong();

    public CoinflipManager(Plugin plugin, CoinflipStorage storage, Messages messages, CombatManager combatManager) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
        this.combatManager = combatManager;
        this.file = new File(plugin.getDataFolder(), "coinflips.yml");
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
        broadcastResults = config.getBoolean("broadcast-results", true);
        itemMatchApprovalTimeoutMillis = Duration.ofSeconds(
                Math.max(30, config.getInt("item-match-approval-timeout-seconds", 300))).toMillis();
    }

    /** Loads active coinflips, bans, pending experience, and pending item-match approvals from the database on startup. */
    public void loadState() {
        try {
            for (Coinflip coinflip : storage.loadAllCoinflips()) {
                activeCoinflips.put(coinflip.id(), coinflip);
            }
            bans.putAll(storage.loadBans());
            pendingExp.putAll(storage.loadPendingExp());
            for (CoinflipPendingMatch match : storage.loadAllPendingMatches()) {
                if (!activeCoinflips.containsKey(match.coinflipId())) {
                    // Its coinflip resolved or was cancelled through some
                    // other path before the server stopped -- nobody can
                    // ever approve this anymore, so refund it now instead
                    // of leaving it stuck forever.
                    queueClaim(match.opponentUuid(), match.items());
                    deletePendingMatchRow(match.id());
                    continue;
                }
                pendingItemMatches.put(match.coinflipId(), match);
            }
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

    public int maxItemStacksPerWager() {
        return maxItemStacksPerWager;
    }

    public long itemIconCycleTicks() {
        return itemIconCycleTicks;
    }

    public long guiRefreshIntervalTicks() {
        return guiRefreshIntervalTicks;
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

    // ---- Animation preference ----

    public boolean animationsEnabled(UUID uuid) {
        return animationsEnabled.getOrDefault(uuid, true);
    }

    public void toggleAnimations(UUID uuid) {
        animationsEnabled.put(uuid, !animationsEnabled(uuid));
    }

    // ---- Creation ----

    public enum CreateResult {
        OK, DISABLED, BANNED, OUT_OF_RANGE, NO_ECONOMY, CANNOT_AFFORD, EMPTY_WAGER, TOO_MANY_ITEMS, ALREADY_HOSTING
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
        EconomyResponse response = economy.withdrawPlayer(host, amount);
        if (!response.transactionSuccess()) {
            return CreateOutcome.failure(CreateResult.CANNOT_AFFORD);
        }
        return finishCreate(host, targetUuid, CoinflipType.MONEY, amount, new ItemStack[0],
                () -> economy.depositPlayer(host, amount));
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
        host.setLevel(host.getLevel() - levels);
        return finishCreate(host, targetUuid, CoinflipType.EXP, levels, new ItemStack[0],
                () -> host.setLevel(host.getLevel() + levels));
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
        return finishCreate(host, targetUuid, CoinflipType.ITEMS, 0, wager,
                () -> giveOrDrop(host, wager));
    }

    private CreateOutcome finishCreate(Player host, UUID targetUuid, CoinflipType type, double amount,
            ItemStack[] items, Runnable refundIfPersistFails) {
        long createdAt = System.currentTimeMillis();
        // A negative placeholder id lets the coinflip render immediately
        // while the real id comes back from the database asynchronously;
        // swapped for the real one the moment the insert completes.
        int placeholderId = localIdCounter.getAndDecrement();
        Coinflip placeholder = new Coinflip(placeholderId, host.getUniqueId(), targetUuid, type, amount, items, createdAt);
        activeCoinflips.put(placeholderId, placeholder);
        browserChanged();

        CompletableFuture<Integer> insert = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.insertCoinflip(host.getUniqueId(), targetUuid, type, amount, items, createdAt);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        track(insert);
        insert.whenComplete((id, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            activeCoinflips.remove(placeholderId);
            browserChanged();
            if (error != null || id == null || id < 0) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Coinflip -- refunding the host.", error);
                if (host.isOnline()) {
                    refundIfPersistFails.run();
                    host.sendMessage(messages.get(host, "coinflip.create-failed"));
                }
                return;
            }
            activeCoinflips.put(id, new Coinflip(id, host.getUniqueId(), targetUuid, type, amount, items, createdAt));
            browserChanged();
        }));

        return new CreateOutcome(CreateResult.OK, placeholder);
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
        if (!activeCoinflips.remove(coinflip.id(), coinflip)) {
            return false;
        }
        browserChanged();
        refund(coinflip);

        boolean adminCancel = !coinflip.hostUuid().equals(actor.getUniqueId());
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteCoinflip(coinflip.id());
                storage.insertLogEntry(coinflip.hostUuid(), null, coinflip.type(), summarize(coinflip), null,
                        System.currentTimeMillis(), CoinflipLogEntry.Status.CANCELLED,
                        adminCancel ? actor.getUniqueId() : null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist a cancelled Coinflip " + coinflip.id(), e);
            }
        }));
        return true;
    }

    private void refund(Coinflip coinflip) {
        OfflinePlayer host = Bukkit.getOfflinePlayer(coinflip.hostUuid());
        switch (coinflip.type()) {
            case MONEY -> {
                if (EconomyHook.isAvailable()) {
                    EconomyHook.getEconomy().depositPlayer(host, coinflip.amount());
                }
            }
            case EXP -> creditExp(coinflip.hostUuid(), (int) coinflip.amount());
            case ITEMS -> queueClaim(coinflip.hostUuid(), coinflip.items());
        }
    }

    // ---- Resolution ----

    public enum PlayResult {
        OK, GONE, BANNED, IS_HOST, NOT_TARGETED, CANNOT_AFFORD, NEEDS_ITEM_WAGER, ALREADY_PENDING_MATCH, ALREADY_TAKING_ONE
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
        if (coinflip == null) {
            return PlayOutcome.failure(PlayResult.GONE);
        }
        PlayResult validation = validatePlay(coinflip, opponent);
        if (validation != PlayResult.OK) {
            return PlayOutcome.failure(validation);
        }
        if (coinflip.type() == CoinflipType.ITEMS) {
            return PlayOutcome.failure(PlayResult.NEEDS_ITEM_WAGER);
        }

        if (coinflip.type() == CoinflipType.MONEY) {
            if (!EconomyHook.isAvailable() || !EconomyHook.getEconomy().has(opponent, coinflip.amount())) {
                return PlayOutcome.failure(PlayResult.CANNOT_AFFORD);
            }
        } else if (opponent.getLevel() < coinflip.amount()) {
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
        } else {
            opponent.setLevel(opponent.getLevel() - (int) coinflip.amount());
        }

        boolean opponentWon = ThreadLocalRandom.current().nextBoolean();
        UUID winnerUuid = opponentWon ? opponent.getUniqueId() : coinflip.hostUuid();
        double keepFraction = 1.0 - (houseFeePercent / 100.0);
        double payout = coinflip.amount() + coinflip.amount() * keepFraction;

        if (coinflip.type() == CoinflipType.MONEY) {
            EconomyHook.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(winnerUuid), payout);
        } else {
            creditExp(winnerUuid, (int) Math.round(payout));
        }

        finishResolution(coinflip, opponent.getUniqueId(), winnerUuid);
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
        if (coinflip == null) {
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

        CompletableFuture<Integer> insert = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.insertPendingMatch(coinflipId, opponentUuid, opponentItems, requestedAt);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        track(insert);
        insert.whenComplete((id, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || id == null || id < 0) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to persist a pending Coinflip item match for coinflip " + coinflipId
                                + " -- refunding the opponent.", error);
                pendingItemMatches.remove(coinflipId, placeholder);
                browserChanged();
                // Denied/expired/undeliverable item wagers always land in the
                // claim stash -- never straight into a live inventory -- so
                // every refund path here behaves identically regardless of
                // whether the opponent happens to be online at the moment.
                queueClaim(opponentUuid, opponentItems);
                return;
            }
            pendingItemMatches.replace(coinflipId, placeholder,
                    new CoinflipPendingMatch(id, coinflipId, opponentUuid, opponentItems, requestedAt));
            browserChanged();
        }));

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
        if (coinflip == null) {
            discardOrphanedMatch(coinflipId);
            return ApproveOutcome.failure(ApprovalResult.GONE);
        }
        if (!coinflip.hostUuid().equals(host.getUniqueId())) {
            return ApproveOutcome.failure(ApprovalResult.NOT_HOST);
        }
        CoinflipPendingMatch match = pendingItemMatches.get(coinflipId);
        if (match == null) {
            return ApproveOutcome.failure(ApprovalResult.NO_PENDING_MATCH);
        }
        if (!activeCoinflips.remove(coinflipId, coinflip)) {
            return ApproveOutcome.failure(ApprovalResult.GONE);
        }
        pendingItemMatches.remove(coinflipId, match);
        browserChanged();

        boolean opponentWon = ThreadLocalRandom.current().nextBoolean();
        UUID winnerUuid = opponentWon ? match.opponentUuid() : coinflip.hostUuid();
        List<ItemStack> combined = new ArrayList<>(List.of(coinflip.items()));
        combined.addAll(List.of(match.items()));
        queueClaim(winnerUuid, combined.toArray(new ItemStack[0]));

        finishResolution(coinflip, match.opponentUuid(), winnerUuid);
        deletePendingMatchRow(match.id());
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
        CoinflipPendingMatch match = pendingItemMatches.remove(coinflipId);
        if (match == null) {
            return ApprovalResult.NO_PENDING_MATCH;
        }
        browserChanged();
        queueClaim(match.opponentUuid(), match.items());
        deletePendingMatchRow(match.id());
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
            queueClaim(orphan.opponentUuid(), orphan.items());
            deletePendingMatchRow(orphan.id());
        }
    }

    /** Every pending match older than {@code item-match-approval-timeout-seconds} is auto-denied and refunded. */
    public void sweepExpiredItemMatches() {
        long cutoff = System.currentTimeMillis() - itemMatchApprovalTimeoutMillis;
        for (CoinflipPendingMatch match : List.copyOf(pendingItemMatches.values())) {
            if (match.requestedAtMillis() > cutoff || !pendingItemMatches.remove(match.coinflipId(), match)) {
                continue;
            }
            browserChanged();
            queueClaim(match.opponentUuid(), match.items());
            deletePendingMatchRow(match.id());
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

    private void deletePendingMatchRow(int id) {
        if (id < 0) {
            // Still an unpersisted placeholder -- there's no row to delete yet.
            return;
        }
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deletePendingMatch(id);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to remove a resolved Coinflip pending item match " + id, e);
            }
        }));
    }

    private void finishResolution(Coinflip coinflip, UUID opponentUuid, UUID winnerUuid) {
        boolean hostWon = winnerUuid.equals(coinflip.hostUuid());
        OfflinePlayer hostOffline = Bukkit.getOfflinePlayer(coinflip.hostUuid());
        OfflinePlayer opponentOffline = Bukkit.getOfflinePlayer(opponentUuid);
        Player host = Bukkit.getPlayer(coinflip.hostUuid());
        Player opponentPlayer = Bukkit.getPlayer(opponentUuid);

        revealResult(host, hostWon, hostWon, hostOffline, opponentOffline);
        revealResult(opponentPlayer, !hostWon, hostWon, hostOffline, opponentOffline);

        if (broadcastResults) {
            String winnerName = Bukkit.getOfflinePlayer(winnerUuid).getName();
            Component broadcast = messages.get(Bukkit.getConsoleSender(), "coinflip.broadcast-result",
                    "winner", winnerName == null ? "?" : winnerName, "wager", summarize(coinflip));
            Bukkit.broadcast(broadcast);
        }

        String summary = summarize(coinflip);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteCoinflip(coinflip.id());
                storage.insertLogEntry(coinflip.hostUuid(), opponentUuid, coinflip.type(), summary, winnerUuid,
                        System.currentTimeMillis(), CoinflipLogEntry.Status.RESOLVED, null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist resolved Coinflip " + coinflip.id(), e);
            }
        }));
    }

    /**
     * Sends the win/lose message immediately, unless {@code player} is
     * about to see the result animation -- in that case the message
     * waits for the animation to actually land on the result instead of
     * spoiling it up front. A player who's offline, has animations off,
     * or is in combat just gets the message right away, same as before.
     */
    private void revealResult(Player player, boolean playerWon, boolean hostWon, OfflinePlayer host, OfflinePlayer opponent) {
        if (player == null) {
            return;
        }
        String messageKey = playerWon ? "coinflip.you-won" : "coinflip.you-lost";
        if (animationsEnabled(player.getUniqueId()) && !combatManager.isTagged(player.getUniqueId())) {
            CoinflipAnimationMenu.play(plugin, player, messages, host, opponent, hostWon,
                    () -> player.sendMessage(messages.get(player, messageKey)));
        } else {
            player.sendMessage(messages.get(player, messageKey));
        }
    }

    private String summarize(Coinflip coinflip) {
        return switch (coinflip.type()) {
            case MONEY -> EconomyHook.format(coinflip.amount());
            case EXP -> (int) coinflip.amount() + " levels";
            case ITEMS -> summarizeItems(coinflip.items());
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

    /** Gives items directly if the player is online, otherwise queues them as a claim. */
    private void giveOrDrop(Player player, ItemStack[] items) {
        if (player.isOnline()) {
            for (ItemStack leftover : player.getInventory().addItem(items).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        } else {
            queueClaim(player.getUniqueId(), items);
        }
    }

    // ---- Experience credit (handles the recipient being offline) ----

    private void creditExp(UUID uuid, int levels) {
        if (levels <= 0) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.setLevel(online.getLevel() + levels);
            return;
        }
        int total = pendingExp.merge(uuid, levels, Integer::sum);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.savePendingExp(uuid, total);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist pending Coinflip experience for " + uuid, e);
            }
        }));
    }

    /** Applies any experience owed from while this player was offline. Call this on join. */
    public void applyPendingExp(Player player) {
        Integer levels = pendingExp.remove(player.getUniqueId());
        if (levels == null || levels <= 0) {
            return;
        }
        player.setLevel(player.getLevel() + levels);
        player.sendMessage(messages.get(player, "coinflip.pending-exp-applied", "levels", String.valueOf(levels)));
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deletePendingExp(player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear pending Coinflip experience for "
                        + player.getUniqueId(), e);
            }
        }));
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

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Coinflip writes.", e);
        }
    }
}
