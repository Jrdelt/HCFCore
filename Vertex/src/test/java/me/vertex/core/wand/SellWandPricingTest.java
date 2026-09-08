package me.vertex.core.wand;

import me.vertex.core.shop.ShopManager;
import me.vertex.core.shop.ShopStorage;
import me.vertex.core.storage.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason Sell Wands exist as a shop concern rather than a wand one: a
 * container must not be a way around dynamic pricing.
 */
class SellWandPricingTest {

    private static final Material GOODS = Material.DIRT;

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private Database database;
    private ShopManager shop;
    private PlayerMock seller;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        ShopStorage storage = new ShopStorage(database);
        storage.init();
        shop = new ShopManager(plugin, storage, null);
        shop.load();
        shop.loadState();
        seller = server.addPlayer("Seller");
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    /**
     * Selling a container's worth in one go must pay the same as selling the
     * same amount unit by unit -- never the starting price times the count,
     * which is how a big container would otherwise dodge the market.
     */
    @Test
    void containerSaleWalksThePriceDownInsteadOfUsingOneFlatRate() {
        int amount = 512;
        double firstUnit = shop.sellPrice(GOODS);
        double flatRate = firstUnit * amount;
        double actual = shop.sellFromContainer(seller, GOODS, amount);

        assertEquals(shop.totalSellPayout(GOODS, 0), 0D, 0.0001);
        assertTrue(actual < flatRate,
                "a large container sale must pay less than the starting price times the count");
        assertTrue(actual > 0, "it should still pay something");
    }

    @Test
    void containerSaleMovesTheMarketSoThePriceKeepsFalling() {
        double before = shop.sellPrice(GOODS);
        shop.sellFromContainer(seller, GOODS, 512);
        double after = shop.sellPrice(GOODS);

        assertTrue(after < before, "selling should have pushed the sell price down");
    }

    @Test
    void twoHalfContainersPayTheSameAsOneWholeOne() {
        double whole = shop.sellFromContainer(seller, GOODS, 256);

        // A fresh market, so the second run starts from the same place.
        ShopManager other = freshShop();
        double firstHalf = other.sellFromContainer(seller, GOODS, 128);
        double secondHalf = other.sellFromContainer(seller, GOODS, 128);

        assertEquals(whole, firstHalf + secondHalf, 0.01,
                "splitting a sale must not change what it earns");
    }

    @Test
    void paysNothingForAnUntradeableMaterial() {
        assertEquals(0D, shop.sellFromContainer(seller, Material.BEDROCK, 64), 0.0001);
    }

    @Test
    void paysNothingForAnEmptyOrNegativeAmount() {
        assertEquals(0D, shop.sellFromContainer(seller, GOODS, 0), 0.0001);
        assertEquals(0D, shop.sellFromContainer(seller, GOODS, -5), 0.0001);
    }

    private ShopManager freshShop() {
        try {
            Database other = new Database(new YamlConfiguration(),
                    java.nio.file.Files.createTempDirectory("shop").toFile());
            ShopStorage storage = new ShopStorage(other);
            storage.init();
            ShopManager manager = new ShopManager(MockBukkit.createMockPlugin("Other"), storage, null);
            manager.load();
            manager.loadState();
            return manager;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
