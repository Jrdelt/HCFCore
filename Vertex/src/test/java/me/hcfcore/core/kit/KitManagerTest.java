package me.hcfcore.core.kit;

import me.hcfcore.core.ability.AbilityManager;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import static me.hcfcore.core.lang.MessageAssertions.isChatMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitManagerTest {

    private ServerMock server;
    private PluginMock plugin;
    private UserManager userManager;
    private Messages messages;
    private AbilityManager abilityManager;
    private KitManager kitManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        userManager = new UserManager(plugin, new InMemoryStorage());
        messages = new Messages(plugin, userManager);
        messages.load();
        abilityManager = new AbilityManager(plugin, new InMemoryStorage());
        abilityManager.load();
        kitManager = new KitManager(plugin, new InMemoryStorage(), userManager, messages, abilityManager);
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

        KitManager reloaded = new KitManager(plugin, new InMemoryStorage(), userManager, messages, abilityManager);
        reloaded.load();
        Kit kit = reloaded.get("starter");
        assertEquals("Starter", kit.getName());
        assertEquals("hcfcore.kit.starter", kit.getPermission());
        assertEquals(30, kit.getCooldownSeconds());

        reloaded.delete("Starter");
        reloaded.shutdown();

        KitManager reloadedAgain = new KitManager(plugin, new InMemoryStorage(), userManager, messages, abilityManager);
        reloadedAgain.load();
        assertNull(reloadedAgain.get("starter"));
    }

    @Test
    void saveWritesToTheCurrentPluginDataFolder() {
        PlayerMock player = server.addPlayer("Frank");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));

        File liveFile = new File(plugin.getDataFolder(), "kits.yml");
        assertFalse(liveFile.exists(), "test starts without a generated kits.yml");

        kitManager.save("Starter", player, "hcfcore.kit.starter", 0, Kit.Cost.NONE);
        kitManager.shutdown();

        assertTrue(liveFile.exists(), "save should create the live kits.yml in the active plugin data folder");
    }

    @Test
    void savedKitIsNotChangedWhenPlayerInventoryChanges() {
        PlayerMock player = server.addPlayer("Frank");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));

        kitManager.save("Starter", player, "hcfcore.kit.starter", 0, Kit.Cost.NONE);
        player.getInventory().clear();

        Kit kit = kitManager.get("starter");
        assertEquals(Material.DIAMOND_SWORD, kit.getContents()[0].getType());
    }

    @Test
    void kitArmorUsesCorrectBukkitSlots() {
        PlayerMock player = server.addPlayer("Grace");
        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
        ItemStack leggings = new ItemStack(Material.LEATHER_LEGGINGS);
        ItemStack chestplate = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        player.getInventory().setArmorContents(new ItemStack[]{boots, leggings, chestplate, helmet});

        kitManager.save("Armor", player, "", 0, Kit.Cost.NONE);
        Kit kit = kitManager.get("armor");

        assertEquals(Material.LEATHER_HELMET, kit.getArmor()[0].getType());
        assertEquals(Material.LEATHER_CHESTPLATE, kit.getArmor()[1].getType());
        assertEquals(Material.LEATHER_LEGGINGS, kit.getArmor()[2].getType());
        assertEquals(Material.LEATHER_BOOTS, kit.getArmor()[3].getType());
        assertTrue(Arrays.stream(kit.getContents()).noneMatch(item -> item != null && switch (item.getType()) {
            case LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS -> true;
            default -> false;
        }),
            "armor should not be saved as kit contents");

        userManager.load(player.getUniqueId());
        player.getInventory().setArmorContents(new ItemStack[4]);
        kitManager.apply(player, kit);
        ItemStack[] appliedArmor = player.getInventory().getArmorContents();
        assertEquals(Material.LEATHER_BOOTS, appliedArmor[0].getType());
        assertEquals(Material.LEATHER_LEGGINGS, appliedArmor[1].getType());
        assertEquals(Material.LEATHER_CHESTPLATE, appliedArmor[2].getType());
        assertEquals(Material.LEATHER_HELMET, appliedArmor[3].getType());
    }

    @Test
    void applyRespectsTheKitsOwnPermission() {
        PlayerMock player = server.addPlayer("Bob");
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("VIP", "hcfcore.kit.vip", 0, new ItemStack[0], new ItemStack[0]);

        kitManager.apply(player, kit);

        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.no-kit-permission"));
    }

    @Test
    void applyEnforcesCooldownUntilItExpiresOrIsBypassed() {
        PlayerMock player = server.addPlayer("Carol");
        player.addAttachment(plugin, "hcfcore.kit.bypasscooldown", false);
        userManager.load(player.getUniqueId());
        Kit kit = new Kit("Grinder", "", 60, new ItemStack[0], new ItemStack[0]);

        kitManager.apply(player, kit);
        player.nextComponentMessage(); // consume kit.applied

        // Second attempt, still on cooldown.
        kitManager.apply(player, kit);
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.cooldown"));

        // A player with the bypass permission skips the cooldown entirely.
        player.addAttachment(plugin, "hcfcore.kit.bypasscooldown", true);
        kitManager.apply(player, kit);
        assertFalse(isChatMessage(messages, player, player.nextComponentMessage(), "kit.cooldown"));
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
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.cost-money-needed"));
        assertEquals(100.0, economy.balance);

        // Affordable: charged and applied.
        economy.balance = 1_000.0;
        kitManager.apply(player, kit);
        assertEquals(500.0, economy.balance);
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.applied"));

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
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.cost-item-needed"));

        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        kitManager.apply(player, kit);
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.applied"));
        assertFalse(player.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 1),
                "the 4 diamonds should have been consumed as payment");
    }

    @Test
    void applyGrantsTheKitsPotionEffects() {
        PlayerMock player = server.addPlayer("Grant");
        player.addAttachment(plugin, "hcfcore.kit.archer", true);
        userManager.load(player.getUniqueId());
        kitManager.load();
        Kit kit = kitManager.get("archer");

        kitManager.start();
        kitManager.apply(player, kit);

        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(61);
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(2, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertTrue(player.hasPotionEffect(PotionEffectType.JUMP_BOOST));
        assertEquals(2, player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier());

        player.nextComponentMessage();
        player.nextComponentMessage();
        ItemStack[] archerArmor = player.getInventory().getArmorContents();
        player.getInventory().setArmorContents(new ItemStack[4]);
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();
        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.effects-removed"));

        player.getInventory().setArmorContents(archerArmor);
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(60);
        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
    }

    @Test
    void abilityTaggedKitContentsUseTheAbilitysOwnNameAndLore() {
        // Regression test: kit content items with an "ability:" tag used to
        // be built as bare vanilla items (no name, no lore) with only a
        // hidden PDC tag -- indistinguishable in the player's inventory
        // from the plain material it's made of. They should look exactly
        // like the same item granted via /getitem.
        PlayerMock player = server.addPlayer("Merlin");
        player.addAttachment(plugin, "hcfcore.kit.mage", true);
        userManager.load(player.getUniqueId());
        kitManager.load();
        Kit kit = kitManager.get("mage");

        kitManager.apply(player, kit);

        ItemStack witherSkull = Arrays.stream(player.getInventory().getContents())
                .filter(item -> item != null && item.getType() == Material.WITHER_SKELETON_SKULL)
                .findFirst()
                .orElse(null);
        assertTrue(witherSkull != null && witherSkull.hasItemMeta() && witherSkull.getItemMeta().hasDisplayName(),
                "the mage-wither item should carry the ability's own display name");
        assertTrue(witherSkull.getItemMeta().hasLore(), "the mage-wither item should carry the ability's own lore");
    }

    @Test
    void externalPotionOverridingAKitEffectDoesNotRetriggerTheWarmupMessage() {
        // Regression test: an external potion (e.g. a PvP splash potion)
        // sharing an effect type with the kit used to be misread as the
        // kit's own effect having "fallen off" armor, since the old check
        // compared amplifiers exactly. That re-cleared tracking and
        // resent the equip warmup message mid-fight, without the player
        // ever touching their armor.
        PlayerMock player = server.addPlayer("Hank");
        player.addAttachment(plugin, "hcfcore.kit.archer", true);
        userManager.load(player.getUniqueId());
        kitManager.load();
        Kit kit = kitManager.get("archer");

        kitManager.start();
        kitManager.apply(player, kit);
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(61);
        assertEquals(2, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        player.nextComponentMessage();
        player.nextComponentMessage();

        // Simulate a stronger Speed splash potion landing on the player
        // mid-fight, overriding the kit's own SPEED amplifier.
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 3));
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();

        assertEquals(3, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier(),
                "the external potion's stronger amplifier should not be immediately stomped");
        assertTrue(player.hasPotionEffect(PotionEffectType.JUMP_BOOST),
                "the untouched kit effect should never be cleared just because SPEED was overridden");
        assertNull(player.nextComponentMessage(), "no equip/warmup message should fire since the kit armor never changed");

        // The external potion naturally expires (simulated directly,
        // since MockBukkit doesn't tick down real potion durations).
        player.removePotionEffect(PotionEffectType.SPEED);
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();

        assertEquals(2, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier(),
                "the kit's own SPEED should silently reappear the moment the override is gone");
        assertTrue(player.hasPotionEffect(PotionEffectType.JUMP_BOOST), "still never should have been cleared");
        assertNull(player.nextComponentMessage(), "restoring a stripped effect on unchanged armor should stay silent");
    }

    @Test
    void armorDurabilityDamageDoesNotDropTheKitsEffects() {
        // Regression test: kit armor was matched with ItemStack.isSimilar,
        // which compares ItemMeta -- and durability lives in the meta as
        // Damageable#damage. So anything that chewed durability off the
        // worn set (a creeper explosion hitting all four pieces at once,
        // or just ordinary combat) stopped matching the kit, and the
        // player was told their class effects were removed "because the
        // full kit armor is no longer equipped" while still wearing it.
        PlayerMock player = server.addPlayer("Ivy");
        player.addAttachment(plugin, "hcfcore.kit.archer", true);
        userManager.load(player.getUniqueId());
        kitManager.load();
        Kit kit = kitManager.get("archer");

        kitManager.start();
        kitManager.apply(player, kit);
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(61);
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        player.nextComponentMessage();
        player.nextComponentMessage();

        ItemStack[] worn = player.getInventory().getArmorContents();
        for (ItemStack piece : worn) {
            if (piece != null && piece.getItemMeta() instanceof Damageable meta) {
                meta.setDamage(meta.getDamage() + 5);
                piece.setItemMeta(meta);
            }
        }
        player.getInventory().setArmorContents(worn);
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();

        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED),
                "worn-down kit armor is still the kit's armor");
        assertTrue(player.hasPotionEffect(PotionEffectType.JUMP_BOOST),
                "worn-down kit armor is still the kit's armor");
        assertNull(player.nextComponentMessage(),
                "durability loss should not read as the player taking the armor off");
    }

    @Test
    void normalizingWearDoesNotStopEnchantmentsFromDiscriminatingKits() {
        // Guards the other side of the durability fix: wear is ignored,
        // but nothing else is. Enchantment levels are the only thing
        // separating some kits from their donator variants, so armor that
        // differs there must still fail to match.
        PlayerMock player = server.addPlayer("Jonas");
        player.addAttachment(plugin, "hcfcore.kit.archer", true);
        userManager.load(player.getUniqueId());
        kitManager.load();
        Kit kit = kitManager.get("archer");

        kitManager.start();
        kitManager.apply(player, kit);
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(61);
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        player.nextComponentMessage();
        player.nextComponentMessage();

        // Same set, worn down, but re-enchanted beyond the kit's spec.
        ItemStack[] worn = player.getInventory().getArmorContents();
        ItemStack helmet = worn[3];
        if (helmet.getItemMeta() instanceof Damageable meta) {
            meta.setDamage(meta.getDamage() + 5);
            helmet.setItemMeta(meta);
        }
        helmet.addUnsafeEnchantment(Enchantment.PROTECTION, 5);
        player.getInventory().setArmorContents(worn);
        ((BukkitSchedulerMock) server.getScheduler()).performOneTick();

        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED),
                "a differently enchanted helmet is not the archer kit's helmet");
        assertTrue(isChatMessage(messages, player, player.nextComponentMessage(), "kit.effects-removed"));
    }

    @Test
    void loadParsesTheBundledArcherAndDonatorKits() {
        kitManager.load();

        Kit archer = kitManager.get("archer");
        assertEquals(2, archer.getEffects().size());
        assertTrue(archer.getEffects().contains(new Kit.Effect(PotionEffectType.SPEED, 2)));
        assertTrue(archer.getEffects().contains(new Kit.Effect(PotionEffectType.JUMP_BOOST, 2)));
        ItemStack[] archerArmor = archer.getArmor();
        assertEquals(2, archerArmor[0].getEnchantmentLevel(Enchantment.PROTECTION));
        assertEquals(1, archerArmor[1].getEnchantmentLevel(Enchantment.PROTECTION));
        assertEquals(1, archerArmor[2].getEnchantmentLevel(Enchantment.PROTECTION));
        assertEquals(2, archerArmor[3].getEnchantmentLevel(Enchantment.PROTECTION));

        Kit archerDonator = kitManager.get("archer-donator");
        assertTrue(Arrays.stream(archerDonator.getArmor()).allMatch(
            item -> item.getEnchantmentLevel(Enchantment.UNBREAKING) == 3
                && item.getEnchantmentLevel(Enchantment.PROTECTION) == 2),
            "the donator archer kit's armor should have Unbreaking III and Protection II");
    }

    @Test
    void mageKitOverridesItsGuiIconAwayFromItsHelmet() {
        // The mage kit's first armor slot (helmet) is a plain golden
        // helmet -- kits.yml overrides the /kits GUI icon to its more
        // distinctive chainmail leggings via the "icon" field, since the
        // default icon picker just takes the first non-air armor piece.
        kitManager.load();

        Kit mage = kitManager.get("mage");
        assertEquals("CHAINMAIL_LEGGINGS", mage.getIcon());
        assertEquals(Material.GOLDEN_HELMET, mage.getArmor()[0].getType(),
            "the icon override shouldn't change what's actually worn");
    }

    @Test
    void kitIconOverridePersistsAcrossAFreshLoad() throws java.io.IOException {
        File liveFile = new File(plugin.getDataFolder(), "kits.yml");
        liveFile.getParentFile().mkdirs();
        java.nio.file.Files.writeString(liveFile.toPath(), """
                kits:
                  scout:
                    permission: ''
                    icon: CHAINMAIL_LEGGINGS
                    armor: []
                    contents: []
                """);

        kitManager.load();
        assertEquals("CHAINMAIL_LEGGINGS", kitManager.get("scout").getIcon());

        KitManager reloaded = new KitManager(plugin, new InMemoryStorage(), userManager, messages, abilityManager);
        reloaded.load();
        assertEquals("CHAINMAIL_LEGGINGS", reloaded.get("scout").getIcon());
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
        public String loadLocale(UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }
        @Override
        public void saveDeath(UUID uuid, me.hcfcore.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.hcfcore.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
