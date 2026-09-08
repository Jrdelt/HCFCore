package me.vertex.core.coinflip;

import me.vertex.core.lang.Messages;
import me.vertex.core.staff.Death;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CoinflipManager} against a real local (SQLite) database
 * -- not a fake -- since this is the one subsystem in the plugin where a
 * silently-wrong query would mean an actually lost wager. RNG-dependent
 * outcomes (who wins a given flip) are asserted as invariants (money/items
 * conserved, exactly one side is up) rather than a specific winner, since
 * {@code ThreadLocalRandom} isn't seeded/injectable here.
 */
class CoinflipManagerTest {

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private PluginMock plugin;
    private Database database;
    private CoinflipManager manager;
    private me.vertex.core.pvp.CombatManager combatManager;
    private FakeEconomy economy;
    private PlayerMock host;
    private PlayerMock opponent;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        YamlConfiguration dbConfig = new YamlConfiguration();
        database = new Database(dbConfig, dataFolder.toFile());

        CoinflipStorage storage = new CoinflipStorage(database);
        storage.init();

        UserManager userManager = new UserManager(plugin, new InMemoryStorage());
        Messages messages = new Messages(plugin, userManager);
        messages.load();

        combatManager = new me.vertex.core.pvp.CombatManager(plugin, messages, 15, 5, true, 4, "", "", "");
        manager = new CoinflipManager(plugin, storage, messages, combatManager);
        manager.load();

        economy = new FakeEconomy();
        Bukkit.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);

        host = server.addPlayer("Host");
        opponent = server.addPlayer("Opponent");
        economy.set(host.getUniqueId(), 1000.0);
        economy.set(opponent.getUniqueId(), 1000.0);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            // Mirrors VertexPlugin#onDisable's ordering: drain in-flight
            // async writes before the connection pool underneath them closes.
            settle();
        }
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void createMoneyCoinflipWithdrawsFromHostAndListsIt() {
        CoinflipManager.CreateOutcome outcome = manager.createMoneyCoinflip(host, 100.0, null);
        settle();

        assertEquals(CoinflipManager.CreateResult.OK, outcome.result());
        assertEquals(900.0, economy.get(host.getUniqueId()));
        assertEquals(1, manager.activeCoinflips().size());
    }

    @Test
    void createMoneyCoinflipFailsWithoutEnoughBalance() {
        CoinflipManager.CreateOutcome outcome = manager.createMoneyCoinflip(host, 5000.0, null);
        settle();

        assertEquals(CoinflipManager.CreateResult.CANNOT_AFFORD, outcome.result());
        assertEquals(1000.0, economy.get(host.getUniqueId()), "a failed create must not touch the balance");
        assertTrue(manager.activeCoinflips().isEmpty());
    }

    @Test
    void createMoneyCoinflipRejectsAWagerOutsideConfiguredBounds() {
        CoinflipManager.CreateOutcome tooSmall = manager.createMoneyCoinflip(host, 0.01, null);
        settle();
        CoinflipManager.CreateOutcome tooLarge = manager.createMoneyCoinflip(host, 10_000_000.0, null);
        settle();

        assertEquals(CoinflipManager.CreateResult.OUT_OF_RANGE, tooSmall.result());
        assertEquals(CoinflipManager.CreateResult.OUT_OF_RANGE, tooLarge.result());
        assertEquals(1000.0, economy.get(host.getUniqueId()));
    }

    @Test
    void hostingASecondCoinflipWhileTheFirstIsStillOpenIsRefused() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();

        CoinflipManager.CreateOutcome second = manager.createMoneyCoinflip(host, 50.0, null);
        settle();

        assertEquals(CoinflipManager.CreateResult.ALREADY_HOSTING, second.result());
        assertEquals(900.0, economy.get(host.getUniqueId()), "the refused second wager must not touch the balance");
        assertEquals(1, manager.activeCoinflips().size());
    }

    @Test
    void hostingAgainSucceedsOnceThePreviousCoinflipIsGone() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip first = manager.activeCoinflips().get(0);
        manager.cancel(first, host);

        CoinflipManager.CreateOutcome second = manager.createMoneyCoinflip(host, 50.0, null);
        settle();

        assertEquals(CoinflipManager.CreateResult.OK, second.result());
    }

    @Test
    void playMoneyCoinflipConservesTotalMoneyAndPaysExactlyOneWinner() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        CoinflipManager.PlayOutcome outcome = manager.play(coinflip.id(), opponent);

        assertEquals(CoinflipManager.PlayResult.OK, outcome.result());
        assertTrue(manager.activeCoinflips().isEmpty(), "a resolved coinflip must leave the active list");

        double hostBalance = economy.get(host.getUniqueId());
        double opponentBalance = economy.get(opponent.getUniqueId());
        // Both started at 1000, both wagered 100: total is conserved at 0% fee.
        assertEquals(2000.0, hostBalance + opponentBalance, 0.001);
        // Exactly one side is up 100 and the other is down 100 -- winner take all.
        assertTrue((hostBalance == 1100.0 && opponentBalance == 900.0)
                || (hostBalance == 900.0 && opponentBalance == 1100.0));
    }

    @Test
    void playMoneyCoinflipFailsAndStaysActiveIfOpponentCannotAfford() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        economy.set(opponent.getUniqueId(), 10.0);

        CoinflipManager.PlayOutcome outcome = manager.play(coinflip.id(), opponent);

        assertEquals(CoinflipManager.PlayResult.CANNOT_AFFORD, outcome.result());
        assertEquals(1, manager.activeCoinflips().size(), "a failed play must not remove the coinflip");
        assertEquals(10.0, economy.get(opponent.getUniqueId()), "a failed play must not touch the balance");
    }

    @Test
    void hostCannotPlayTheirOwnCoinflip() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        CoinflipManager.PlayOutcome outcome = manager.play(coinflip.id(), host);

        assertEquals(CoinflipManager.PlayResult.IS_HOST, outcome.result());
        assertEquals(1, manager.activeCoinflips().size());
    }

    @Test
    void targetedCoinflipRejectsAnyoneElse() {
        PlayerMock stranger = server.addPlayer("Stranger");
        economy.set(stranger.getUniqueId(), 1000.0);
        manager.createMoneyCoinflip(host, 100.0, opponent.getUniqueId());
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        CoinflipManager.PlayOutcome outcome = manager.play(coinflip.id(), stranger);

        assertEquals(CoinflipManager.PlayResult.NOT_TARGETED, outcome.result());
    }

    @Test
    void playingAnAlreadyResolvedCoinflipReportsGone() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.play(coinflip.id(), opponent);

        CoinflipManager.PlayOutcome second = manager.play(coinflip.id(), opponent);

        assertEquals(CoinflipManager.PlayResult.GONE, second.result());
    }

    @Test
    void cancelRefundsTheHostAndRemovesTheListing() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        boolean cancelled = manager.cancel(coinflip, host);

        assertTrue(cancelled);
        assertEquals(1000.0, economy.get(host.getUniqueId()));
        assertTrue(manager.activeCoinflips().isEmpty());
    }

    @Test
    void cancelFailsIfTheCoinflipWasAlreadyPlayed() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.play(coinflip.id(), opponent);

        boolean cancelled = manager.cancel(coinflip, host);

        assertFalse(cancelled, "a coinflip already resolved must not be cancellable, and never refunded twice");
    }

    @Test
    void onlyHostOrPermittedStaffCanCancel() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        PlayerMock stranger = server.addPlayer("Stranger");

        assertFalse(manager.canCancel(stranger, coinflip));
        assertTrue(manager.canCancel(host, coinflip));
    }

    @Test
    void expCoinflipDeductsFromHostAndConservesLevelsBetweenBothPlayers() {
        host.setLevel(50);
        opponent.setLevel(50);

        CoinflipManager.CreateOutcome outcome = manager.createExpCoinflip(host, 10, null);
        settle();
        assertEquals(CoinflipManager.CreateResult.OK, outcome.result());
        assertEquals(40, host.getLevel());

        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.play(coinflip.id(), opponent);

        // Both started at 50 (100 total); the wager only ever moves levels
        // between these two, so the pre-wager total must be conserved.
        assertEquals(100, host.getLevel() + opponent.getLevel(), "levels wagered must be conserved between the two players");
        assertTrue((host.getLevel() == 60 && opponent.getLevel() == 40)
                || (host.getLevel() == 40 && opponent.getLevel() == 60));
    }

    @Test
    void expCoinflipFailsWithoutEnoughLevels() {
        host.setLevel(5);

        CoinflipManager.CreateOutcome outcome = manager.createExpCoinflip(host, 10, null);
        settle();

        assertEquals(CoinflipManager.CreateResult.CANNOT_AFFORD, outcome.result());
        assertEquals(5, host.getLevel());
    }

    @Test
    void itemCoinflipRequiresHostApprovalBeforeItResolves() {
        ItemStack[] hostItems = { new ItemStack(Material.DIAMOND, 3) };
        manager.createItemsCoinflip(host, hostItems, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        ItemStack[] opponentItems = { new ItemStack(Material.EMERALD, 2) };
        CoinflipManager.PlayResult requestResult = manager.requestItemMatch(coinflip.id(), opponent, opponentItems);

        assertEquals(CoinflipManager.PlayResult.OK, requestResult);
        assertTrue(manager.hasPendingItemMatch(coinflip.id()), "the match must wait on the host, not resolve immediately");
        assertFalse(manager.hasClaims(host.getUniqueId()), "nobody has won anything yet");
        assertFalse(manager.hasClaims(opponent.getUniqueId()));
        assertEquals(1, manager.activeCoinflips().size(), "the coinflip listing itself must still be there, unresolved");
    }

    @Test
    void secondOpponentCannotProposeAMatchWhileOneIsAlreadyPending() {
        manager.createItemsCoinflip(host, new ItemStack[] { new ItemStack(Material.DIAMOND) }, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.requestItemMatch(coinflip.id(), opponent, new ItemStack[] { new ItemStack(Material.EMERALD) });

        PlayerMock third = server.addPlayer("Third");
        CoinflipManager.PlayResult result =
                manager.requestItemMatch(coinflip.id(), third, new ItemStack[] { new ItemStack(Material.GOLD_INGOT) });

        assertEquals(CoinflipManager.PlayResult.ALREADY_PENDING_MATCH, result);
    }

    @Test
    void anOpponentCannotProposeASecondMatchOnADifferentCoinflipWhileWaitingOnTheFirst() {
        manager.createItemsCoinflip(host, new ItemStack[] { new ItemStack(Material.DIAMOND) }, null);
        settle();
        Coinflip first = manager.activeCoinflips().get(0);
        manager.requestItemMatch(first.id(), opponent, new ItemStack[] { new ItemStack(Material.EMERALD) });

        PlayerMock secondHost = server.addPlayer("SecondHost");
        manager.createItemsCoinflip(secondHost, new ItemStack[] { new ItemStack(Material.NETHERITE_INGOT) }, null);
        settle();
        Coinflip second = manager.activeCoinflips().stream().filter(c -> c.id() != first.id()).findFirst().orElseThrow();

        CoinflipManager.PlayResult result =
                manager.requestItemMatch(second.id(), opponent, new ItemStack[] { new ItemStack(Material.GOLD_INGOT) });

        assertEquals(CoinflipManager.PlayResult.ALREADY_TAKING_ONE, result);
    }

    @Test
    void approvingAnItemMatchResolvesItAndTheWinnerReceivesBothSidesViaTheClaimStash() {
        ItemStack[] hostItems = { new ItemStack(Material.DIAMOND, 3) };
        manager.createItemsCoinflip(host, hostItems, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        ItemStack[] opponentItems = { new ItemStack(Material.EMERALD, 2) };
        manager.requestItemMatch(coinflip.id(), opponent, opponentItems);

        CoinflipManager.ApproveOutcome outcome = manager.approveItemMatch(coinflip.id(), host);
        settle();

        assertEquals(CoinflipManager.ApprovalResult.OK, outcome.result());
        assertFalse(manager.hasPendingItemMatch(coinflip.id()));
        assertTrue(manager.activeCoinflips().isEmpty(), "a resolved coinflip must no longer be listed");
        boolean hostWon = manager.hasClaims(host.getUniqueId());
        boolean opponentWon = manager.hasClaims(opponent.getUniqueId());
        assertTrue(hostWon ^ opponentWon, "exactly one side should have a claimable payout");

        List<ItemStack> claimed = manager.loadClaimItems(hostWon ? host.getUniqueId() : opponent.getUniqueId());
        long diamonds = claimed.stream().filter(i -> i.getType() == Material.DIAMOND).mapToLong(ItemStack::getAmount).sum();
        long emeralds = claimed.stream().filter(i -> i.getType() == Material.EMERALD).mapToLong(ItemStack::getAmount).sum();
        assertEquals(3, diamonds, "the winner must receive every item from both sides, not just their own");
        assertEquals(2, emeralds);
    }

    @Test
    void onlyTheHostCanApproveOrDenyAMatch() {
        manager.createItemsCoinflip(host, new ItemStack[] { new ItemStack(Material.DIAMOND) }, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.requestItemMatch(coinflip.id(), opponent, new ItemStack[] { new ItemStack(Material.EMERALD) });

        CoinflipManager.ApproveOutcome outcome = manager.approveItemMatch(coinflip.id(), opponent);

        assertEquals(CoinflipManager.ApprovalResult.NOT_HOST, outcome.result());
        assertTrue(manager.hasPendingItemMatch(coinflip.id()), "an unauthorized approval attempt must not resolve anything");
    }

    @Test
    void denyingAnItemMatchReturnsTheOpponentsItemsAndLeavesTheCoinflipOpen() {
        manager.createItemsCoinflip(host, new ItemStack[] { new ItemStack(Material.DIAMOND) }, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.requestItemMatch(coinflip.id(), opponent, new ItemStack[] { new ItemStack(Material.EMERALD, 2) });

        CoinflipManager.ApprovalResult result = manager.denyItemMatch(coinflip.id(), host);
        settle();

        assertEquals(CoinflipManager.ApprovalResult.OK, result);
        assertFalse(manager.hasPendingItemMatch(coinflip.id()));
        assertEquals(1, manager.activeCoinflips().size(), "a denied match must not cancel the underlying coinflip");
        // Denied items always land in the claim stash -- never straight into
        // a live inventory -- so every refund path behaves identically
        // regardless of whether the opponent happens to be online.
        assertTrue(manager.hasClaims(opponent.getUniqueId()));
        List<ItemStack> claimed = manager.loadClaimItems(opponent.getUniqueId());
        long emeralds = claimed.stream().filter(i -> i.getType() == Material.EMERALD).mapToLong(ItemStack::getAmount).sum();
        assertEquals(2, emeralds, "denied items must be claimable by the opponent");
    }

    @Test
    void sweepExpiredItemMatchesAutoDeniesAndRefundsAfterTheTimeout() throws Exception {
        manager.createItemsCoinflip(host, new ItemStack[] { new ItemStack(Material.DIAMOND) }, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.requestItemMatch(coinflip.id(), opponent, new ItemStack[] { new ItemStack(Material.EMERALD, 4) });
        settle();

        // Force the in-memory match to look old enough to have expired,
        // the same way a real clock eventually would.
        CoinflipPendingMatch live = manager.pendingItemMatch(coinflip.id());
        java.lang.reflect.Field field = CoinflipManager.class.getDeclaredField("pendingItemMatches");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, CoinflipPendingMatch> map = (Map<Integer, CoinflipPendingMatch>) field.get(manager);
        map.put(coinflip.id(), new CoinflipPendingMatch(live.id(), live.coinflipId(), live.opponentUuid(),
                live.items(), System.currentTimeMillis() - java.time.Duration.ofHours(1).toMillis()));

        manager.sweepExpiredItemMatches();
        settle();

        assertFalse(manager.hasPendingItemMatch(coinflip.id()));
        assertTrue(manager.hasClaims(opponent.getUniqueId()));
        List<ItemStack> claimed = manager.loadClaimItems(opponent.getUniqueId());
        long emeralds = claimed.stream().filter(i -> i.getType() == Material.EMERALD).mapToLong(ItemStack::getAmount).sum();
        assertEquals(4, emeralds, "an expired match must return the opponent's items just like an explicit deny");
    }

    @Test
    void itemCoinflipCreationRejectsAnEmptyWager() {
        CoinflipManager.CreateOutcome outcome = manager.createItemsCoinflip(host, new ItemStack[0], null);
        settle();

        assertEquals(CoinflipManager.CreateResult.EMPTY_WAGER, outcome.result());
    }

    @Test
    void selfBanBlocksCreatingAndPlayingButNotBeforeConfirmation() {
        UUID hostId = host.getUniqueId();
        assertFalse(manager.isBanned(hostId));

        manager.requestBanConfirmation(hostId);
        assertFalse(manager.isBanned(hostId), "requesting confirmation alone must not apply the ban");
        assertTrue(manager.hasPendingBanConfirmation(hostId));

        manager.applyBan(hostId);
        assertTrue(manager.isBanned(hostId));

        CoinflipManager.CreateOutcome outcome = manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        assertEquals(CoinflipManager.CreateResult.BANNED, outcome.result());
    }

    @Test
    void banCannotBeLiftedBeforeItExpires() {
        UUID hostId = host.getUniqueId();
        manager.requestBanConfirmation(hostId);
        manager.applyBan(hostId);

        boolean lifted = manager.liftBanIfExpired(hostId);

        assertFalse(lifted, "a fresh 30-day ban must not be liftable immediately");
        assertTrue(manager.isBanned(hostId));
        assertTrue(manager.banRemainingMillis(hostId) > 0);
    }

    @Test
    void bannedOpponentCannotPlayEitherSide() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.requestBanConfirmation(opponent.getUniqueId());
        manager.applyBan(opponent.getUniqueId());

        CoinflipManager.PlayOutcome outcome = manager.play(coinflip.id(), opponent);

        assertEquals(CoinflipManager.PlayResult.BANNED, outcome.result());
        assertEquals(1, manager.activeCoinflips().size());
    }

    @Test
    void resultAnimationAlsoOpensForAParticipantInCombat() {
        combatManager.tagAgainstServer(host);

        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.play(coinflip.id(), opponent);
        beginResultAnimation();

        boolean animationOpen = host.getOpenInventory() != null
                && host.getOpenInventory().getTopInventory() != null
                && host.getOpenInventory().getTopInventory().getHolder() instanceof CoinflipAnimationMenu.Holder;
        assertTrue(animationOpen, "both coinflip participants must see the same animation, even while combat-tagged");
    }

    @Test
    void resultAnimationOpensForAParticipantNotInCombat() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);
        manager.play(coinflip.id(), opponent);
        beginResultAnimation();

        assertTrue(host.getOpenInventory().getTopInventory().getHolder() instanceof CoinflipAnimationMenu.Holder);
        assertTrue(opponent.getOpenInventory().getTopInventory().getHolder() instanceof CoinflipAnimationMenu.Holder);
    }

    @Test
    void winLoseMessageIsDeferredUntilTheAnimationActuallyLands() {
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        manager.play(coinflip.id(), opponent);
        beginResultAnimation();

        assertNull(host.nextComponentMessage(), "the result must not be revealed to a participant before their animation lands");
        assertNull(opponent.nextComponentMessage(), "the result must not be revealed to a participant before their animation lands");

        ((BukkitSchedulerMock) server.getScheduler()).performTicks(manager.animationDurationTicks() + 2L);

        assertNotNull(host.nextComponentMessage(), "the animation must have landed and revealed the result by now");
        assertNotNull(opponent.nextComponentMessage());
    }

    @Test
    void winLoseMessageRemainsDeferredForAParticipantInCombat() {
        combatManager.tagAgainstServer(host);
        manager.createMoneyCoinflip(host, 100.0, null);
        settle();
        Coinflip coinflip = manager.activeCoinflips().get(0);

        manager.play(coinflip.id(), opponent);
        beginResultAnimation();

        assertNull(host.nextComponentMessage(), "combat must not bypass the shared result animation");
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(manager.animationDurationTicks() + 2L);
        assertNotNull(host.nextComponentMessage(), "the result is revealed only once both animations finish");
    }

    /**
     * Flushes the atomic result write, then executes its main-thread animation
     * start.
     *
     * <p>Ticks until the animation is actually open rather than assuming a
     * single tick is enough: the tracked write future can complete a moment
     * before the follow-up task it schedules has been queued, which made a
     * one-tick wait intermittently too early. The bound is far below the
     * animation's own duration, so this can never run long enough to reveal
     * a result that the deferral tests expect to still be hidden.
     */
    private void beginResultAnimation() {
        settle();
        BukkitSchedulerMock scheduler = (BukkitSchedulerMock) server.getScheduler();
        for (int attempt = 0; attempt < 20 && !animationOpen(host); attempt++) {
            scheduler.performTicks(1L);
        }
    }

    private static boolean animationOpen(PlayerMock player) {
        return player.getOpenInventory() != null
                && player.getOpenInventory().getTopInventory() != null
                && player.getOpenInventory().getTopInventory().getHolder() instanceof CoinflipAnimationMenu.Holder;
    }

    private static final class FakeEconomy implements Economy {
        private final Map<UUID, Double> balances = new ConcurrentHashMap<>();

        void set(UUID uuid, double amount) {
            balances.put(uuid, amount);
        }

        double get(UUID uuid) {
            return balances.getOrDefault(uuid, 0.0);
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return get(player.getUniqueId()) >= amount;
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (get(player.getUniqueId()) < amount) {
                return new EconomyResponse(0, get(player.getUniqueId()), EconomyResponse.ResponseType.FAILURE, "insufficient funds");
            }
            balances.merge(player.getUniqueId(), -amount, Double::sum);
            return new EconomyResponse(amount, get(player.getUniqueId()), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            balances.merge(player.getUniqueId(), amount, Double::sum);
            return new EconomyResponse(amount, get(player.getUniqueId()), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public String format(double amount) {
            return "$" + amount;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "FakeEconomy";
        }

        @Override
        public boolean hasBankSupport() {
            return false;
        }

        @Override
        public int fractionalDigits() {
            return 2;
        }

        @Override
        public String currencyNamePlural() {
            return "Dollars";
        }

        @Override
        public String currencyNameSingular() {
            return "Dollar";
        }

        @Override
        public boolean hasAccount(String playerName) {
            return true;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return true;
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return true;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return true;
        }

        @Override
        public double getBalance(String playerName) {
            return 0;
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return get(player.getUniqueId());
        }

        @Override
        public double getBalance(String playerName, String world) {
            return 0;
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return get(player.getUniqueId());
        }

        @Override
        public boolean has(String playerName, double amount) {
            return false;
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return false;
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount);
        }

        @Override
        public EconomyResponse createBank(String name, String player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse createBank(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse deleteBank(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankBalance(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankHas(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankWithdraw(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankDeposit(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankOwner(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankMember(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankMember(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getBanks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return true;
        }
    }

    private static final class InMemoryStorage implements Storage {
        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }

        @Override
        public void saveDeath(UUID uuid, Death death) {
        }

        @Override
        public List<Death> loadDeaths(UUID uuid, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Waits for the insert AND runs the main-thread task that swaps the
     * in-memory placeholder for its real database id. Until that task runs the
     * entry is still pending, and pending entries deliberately refuse every
     * action -- exactly as a player would find them.
     */
    private void settle() {
        manager.awaitWrites();
        BukkitSchedulerMock scheduler = (BukkitSchedulerMock) server.getScheduler();
        // awaitWrites returns once the insert itself completes, which can be
        // a moment before its callback has even been queued onto the main
        // thread -- so wait for the placeholder to actually be gone rather
        // than assuming a fixed number of ticks is enough.
        for (int attempt = 0; attempt < 100 && manager.activeCoinflips().stream().anyMatch(Coinflip::isPending); attempt++) {
            scheduler.performTicks(1L);
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        scheduler.performTicks(1L);
    }

}
