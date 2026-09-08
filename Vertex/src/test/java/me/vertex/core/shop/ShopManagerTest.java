package me.vertex.core.shop;

import me.vertex.core.storage.Database;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link ShopManager} against a real local (SQLite) database, matching CoinflipManagerTest's approach. */
class ShopManagerTest {

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private PluginMock plugin;
    private Database database;
    private ShopManager manager;
    private FakeEconomy economy;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        YamlConfiguration dbConfig = new YamlConfiguration();
        database = new Database(dbConfig, dataFolder.toFile());

        ShopStorage storage = new ShopStorage(database);
        storage.init();

        manager = new ShopManager(plugin, storage);
        manager.load();
        manager.loadState();

        economy = new FakeEconomy();
        Bukkit.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);

        player = server.addPlayer("Buyer");
        economy.set(player.getUniqueId(), 1000.0);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.awaitWrites();
        }
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void loadParsesBundledBlocks() {
        assertTrue(manager.isEnabled());
        assertTrue(manager.isTradeable(Material.DIRT));
        assertTrue(manager.isTradeable(Material.STONE));
    }

    @Test
    void loadParsesCategoriesAndTheyFitInASingleRow() {
        List<ShopCategory> categories = manager.categories();

        assertTrue(!categories.isEmpty(), "the bundled shop.yml should define at least one category");
        assertTrue(categories.size() <= 9, "the /shop picker is a single row -- more than 9 categories can't fit");
        for (ShopCategory category : categories) {
            assertTrue(!category.entries().isEmpty(), "category '" + category.id() + "' should have at least one item");
        }
    }

    @Test
    void everyTradeableMaterialBelongsToExactlyOneCategory() {
        List<ShopEntry> allEntries = manager.entries();
        long fromCategories = manager.categories().stream().mapToLong(category -> category.entries().size()).sum();

        assertEquals(allEntries.size(), fromCategories,
                "every globally tradeable material must show up in exactly one category, no more and no less");
    }

    @Test
    void categoryLooksUpByIdAndReturnsNullForAnUnknownOne() {
        ShopCategory buildingBlocks = manager.category("building_blocks");

        assertTrue(buildingBlocks != null, "the bundled shop.yml should define a building_blocks category");
        assertTrue(buildingBlocks.entries().stream().anyMatch(entry -> entry.material() == Material.DIRT));
        assertEquals(null, manager.category("not_a_real_category"));
    }

    @Test
    void buyingWithdrawsTheTotalCostAndGivesTheItems() {
        double startBalance = economy.get(player.getUniqueId());
        double expectedCost = manager.totalBuyCost(Material.DIRT, 10);

        ShopManager.TradeOutcome outcome = manager.buy(player, Material.DIRT, 10);

        assertEquals(ShopManager.TradeResult.OK, outcome.result());
        assertEquals(expectedCost, outcome.total(), 0.0001);
        assertEquals(startBalance - expectedCost, economy.get(player.getUniqueId()), 0.0001);
        assertEquals(10, countInInventory(Material.DIRT));
    }

    @Test
    void buyingFailsWithoutEnoughMoneyAndChangesNothing() {
        economy.set(player.getUniqueId(), 0.5);

        ShopManager.TradeOutcome outcome = manager.buy(player, Material.OBSIDIAN, 5);

        assertEquals(ShopManager.TradeResult.CANNOT_AFFORD, outcome.result());
        assertEquals(0.5, economy.get(player.getUniqueId()), 0.0001);
        assertEquals(0, countInInventory(Material.OBSIDIAN));
    }

    @Test
    void buyingTheSameBlockAgainCostsMoreAfterPriceHasRisen() {
        double firstUnitPrice = manager.buyPrice(Material.DIRT);
        // Past price-change-threshold-units (50 by default) so this actually
        // crosses the dead zone instead of landing entirely inside it.
        manager.buy(player, Material.DIRT, 80);
        double priceAfter = manager.buyPrice(Material.DIRT);

        assertTrue(priceAfter > firstUnitPrice, "buying enough to cross the dead zone must push the price up");
    }

    @Test
    void buyingWithinTheDeadZoneDoesNotMoveThePriceAtAll() {
        double firstUnitPrice = manager.buyPrice(Material.DIRT);
        manager.buy(player, Material.DIRT, 10);
        double priceAfter = manager.buyPrice(Material.DIRT);

        assertEquals(firstUnitPrice, priceAfter, 0.0001,
                "a small purchase within price-change-threshold-units must not move the price");
    }

    @Test
    void priceDirectionReflectsWhetherTheCurrentPriceIsAboveBelowOrAtBase() {
        assertEquals(0, manager.priceDirection(Material.DIRT), "a fresh block starts exactly at its base price");

        manager.buy(player, Material.DIRT, 80);
        assertEquals(1, manager.priceDirection(Material.DIRT));

        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.STONE, 80));
        manager.sell(player, Material.STONE, 80);
        assertEquals(-1, manager.priceDirection(Material.STONE));
    }

    @Test
    void buyingABatchCostsMoreThanTheStartingUnitPriceTimesTheAmount() {
        double startingUnitPrice = manager.buyPrice(Material.DIRT);
        double batchCost = manager.totalBuyCost(Material.DIRT, 64);

        assertTrue(batchCost > startingUnitPrice * 64,
                "each unit within a batch should cost slightly more than the last, not one flat price");
    }

    @Test
    void sellingDepositsThePayoutAndRemovesTheItems() {
        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.STONE, 20));
        double expectedPayout = manager.totalSellPayout(Material.STONE, 20);
        double startBalance = economy.get(player.getUniqueId());

        ShopManager.TradeOutcome outcome = manager.sell(player, Material.STONE, 20);

        assertEquals(ShopManager.TradeResult.OK, outcome.result());
        assertEquals(expectedPayout, outcome.total(), 0.0001);
        assertEquals(startBalance + expectedPayout, economy.get(player.getUniqueId()), 0.0001);
        assertEquals(0, countInInventory(Material.STONE));
    }

    @Test
    void sellingFailsWithoutEnoughItemsAndChangesNothing() {
        double startBalance = economy.get(player.getUniqueId());

        ShopManager.TradeOutcome outcome = manager.sell(player, Material.STONE, 20);

        assertEquals(ShopManager.TradeResult.NOT_ENOUGH_ITEMS, outcome.result());
        assertEquals(startBalance, economy.get(player.getUniqueId()), 0.0001);
    }

    @Test
    void sellingTheSameBlockAgainPaysLessAfterThePriceHasFallen() {
        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.STONE, 80));
        double firstSellPrice = manager.sellPrice(Material.STONE);
        // Past price-change-threshold-units (50 by default) so this actually
        // crosses the dead zone instead of landing entirely inside it.
        manager.sell(player, Material.STONE, 80);
        double sellPriceAfter = manager.sellPrice(Material.STONE);

        assertTrue(sellPriceAfter < firstSellPrice, "selling enough to cross the dead zone must push the price down");
    }

    @Test
    void decayTickGraduallyRestoresThePriceTowardBase() {
        double base = manager.buyPrice(Material.DIRT);
        manager.buy(player, Material.DIRT, 64);
        double afterBuying = manager.buyPrice(Material.DIRT);
        assertTrue(afterBuying > base);

        for (int i = 0; i < 50; i++) {
            manager.decayTick();
        }
        double afterDecay = manager.buyPrice(Material.DIRT);

        assertTrue(afterDecay < afterBuying, "repeated decay ticks must bring the price back down toward base");
        assertTrue(afterDecay >= base - 0.01, "decay should not overshoot below the original base price");
    }

    @Test
    void marketStatePersistsAcrossAFreshManagerLoadingFromTheSameDatabase() throws Exception {
        manager.buy(player, Material.DIRT, 32);
        double priceBeforeReload = manager.buyPrice(Material.DIRT);
        manager.awaitWrites();

        ShopStorage sameStorage = new ShopStorage(database);
        ShopManager reloaded = new ShopManager(plugin, sameStorage);
        reloaded.load();
        reloaded.loadState();

        assertEquals(priceBeforeReload, reloaded.buyPrice(Material.DIRT), 0.0001,
                "the market must survive a restart exactly as it was left, not reset to base");
    }

    private int countInInventory(Material material) {
        int count = 0;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
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
}
