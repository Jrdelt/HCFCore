package me.hcfcore.core.kit;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitManagerTest {

    private ServerMock server;
    private PluginMock plugin;
    private UserManager userManager;
    private KitManager kitManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        userManager = new UserManager(plugin, new InMemoryStorage());
        kitManager = new KitManager(plugin, new InMemoryStorage(), userManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void saveThenDeletePersistsAcrossAFreshLoad() {
        // Regression test: save()/delete() moved their kits.yml write onto a
        // dedicated IO thread. shutdown() must flush that write before this
        // assertion re-reads the file, or it'll see stale/no data.
        PlayerMock player = server.addPlayer("Alice");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));

        kitManager.save("Starter", player, "hcfcore.kit.starter", 30, Kit.Cost.NONE);
        kitManager.shutdown();

        KitManager reloaded = new KitManager(plugin, new InMemoryStorage(), userManager);
        reloaded.load();
        Kit kit = reloaded.get("starter");
        assertEquals("Starter", kit.getName());
        assertEquals("hcfcore.kit.starter", kit.getPermission());
        assertEquals(30, kit.getCooldownSeconds());

        reloaded.delete("Starter");
        reloaded.shutdown();

        KitManager reloadedAgain = new KitManager(plugin, new InMemoryStorage(), userManager);
        reloadedAgain.load();
        assertNull(reloadedAgain.get("starter"));
    }

    @Test
    void applyRespectsTheKitsOwnPermission() {
        PlayerMock player = server.addPlayer("Bob");
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("VIP", "hcfcore.kit.vip", 0, new ItemStack[0], new ItemStack[0]);

        kitManager.apply(player, kit);

        assertTrue(player.nextMessage().contains("permission"));
    }

    @Test
    void applyEnforcesCooldownUntilItExpiresOrIsBypassed() {
        PlayerMock player = server.addPlayer("Carol");
        player.addAttachment(plugin, "hcfcore.kit.bypasscooldown", false);
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("Grinder", "", 60, new ItemStack[0], new ItemStack[0]);

        kitManager.apply(player, kit);
        player.nextMessage(); // consume "Applied kit ..."

        // Second attempt, still on cooldown.
        kitManager.apply(player, kit);
        assertTrue(player.nextMessage().contains("again in"));

        // A player with the bypass permission skips the cooldown entirely.
        player.addAttachment(plugin, "hcfcore.kit.bypasscooldown", true);
        kitManager.apply(player, kit);
        assertFalse(player.nextMessage().contains("again in"));
    }

    @Test
    void applyChargesMoneyOnlyWhenAffordableAndSkippedWhenBypassed() {
        PlayerMock player = server.addPlayer("Dana");
        player.addAttachment(plugin, "hcfcore.kit.bypasscost", false);
        userManager.load(player.getUniqueId());
        FakeEconomy economy = new FakeEconomy(100.0);
        Bukkit.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);
        Kit kit = new Kit("Rich", "", 0, new ItemStack[0], new ItemStack[0], new Kit.Cost(500.0, null, 0));

        // Too poor: denied, nothing withdrawn.
        kitManager.apply(player, kit);
        assertTrue(player.nextMessage().contains("need"));
        assertEquals(100.0, economy.balance);

        // Affordable: charged and applied.
        economy.balance = 1_000.0;
        kitManager.apply(player, kit);
        assertEquals(500.0, economy.balance);
        assertTrue(player.nextMessage().contains("Applied kit"));

        // Bypass permission: kit applies again with no further charge.
        player.addAttachment(plugin, "hcfcore.kit.bypasscost", true);
        kitManager.apply(player, kit);
        assertEquals(500.0, economy.balance, "bypass should not withdraw anything");
    }

    @Test
    void applyChargesItemCostOnlyWhenTheInventoryHasEnough() {
        PlayerMock player = server.addPlayer("Eve");
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("Gemstone", "", 0, new ItemStack[0], new ItemStack[0],
                new Kit.Cost(0.0, Material.DIAMOND, 4));

        // No diamonds yet.
        kitManager.apply(player, kit);
        assertTrue(player.nextMessage().contains("need"));

        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        kitManager.apply(player, kit);
        assertTrue(player.nextMessage().contains("Applied kit"));
        assertFalse(player.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 1),
                "the 4 diamonds should have been consumed as payment");
    }

    private static final class FakeEconomy implements Economy {
        private double balance;

        private FakeEconomy(double balance) {
            this.balance = balance;
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return balance >= amount;
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            balance -= amount;
            return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
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
            return balance;
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return balance;
        }

        @Override
        public double getBalance(String playerName, String world) {
            return balance;
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return balance;
        }

        @Override
        public boolean has(String playerName, double amount) {
            return balance >= amount;
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return balance >= amount;
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return balance >= amount;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            throw new UnsupportedOperationException();
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
        public java.util.List<String> getBanks() {
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
        private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return cooldowns.getOrDefault(uuid, Map.of());
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
            cooldowns.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>()).put(kitName, availableAt);
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public void close() {
        }
    }
}
