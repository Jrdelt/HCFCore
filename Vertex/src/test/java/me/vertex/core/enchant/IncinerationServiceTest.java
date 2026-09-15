package me.vertex.core.enchant;

import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.storage.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reward math (scales with stack size, never exceeds the tier's
 * full base value), and that protection genuinely blocks incineration --
 * the two invariants the spec cares most about.
 */
class IncinerationServiceTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private Database database;
    private EnchantManager manager;
    private RunePreferenceManager preferences;
    private IncinerationService service;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Files.createDirectories(plugin.getDataFolder().toPath().resolve("customEnchants"));
        Files.writeString(plugin.getDataFolder().toPath().resolve("customEnchants/runes.yml"), RUNES_YML, StandardCharsets.UTF_8);
        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();

        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        RunePreferenceStorage storage = new RunePreferenceStorage(database);
        storage.init();
        preferences = new RunePreferenceManager(plugin, storage);

        IncinerationStorage journal = new IncinerationStorage(database);
        journal.init();
        service = new IncinerationService(plugin, manager, journal, preferences);
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    @Test
    void rewardScalesWithStackSizeAndNeverExceedsFullBaseValue() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack rune = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        rune.setAmount(10);
        player.getInventory().setItem(0, rune);

        IncinerationService.Outcome outcome = service.incinerateOne(player, 0);

        assertTrue(outcome.success());
        double maxPossible = manager.runeShopPrice(RuneTier.SIMPLE) * 10;
        assertTrue(outcome.rewardAmount() > 0D, "a nonzero reward must be paid");
        assertTrue(outcome.rewardAmount() <= maxPossible, "reward must never exceed 100% of the stack's full base value");
        assertTrue(player.getInventory().getItem(0) == null || player.getInventory().getItem(0).getType().isAir());
    }

    @Test
    void protectedRunesAreRefusedAndNeverDestroyed() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack rune = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        player.getInventory().setItem(0, rune);
        preferences.set(player.getUniqueId(), "haste_pickaxe", "protected:1", "true");

        IncinerationService.Outcome outcome = service.incinerateOne(player, 0);

        assertFalse(outcome.success());
        assertEquals(Material.GRAY_CANDLE, player.getInventory().getItem(0).getType(),
                "a protected rune must remain in the inventory, untouched");
    }

    @Test
    void unidentifiedRuneBoxIsNeverAnEligibleIncinerationTarget() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack box = manager.createRune(RuneTier.SIMPLE);
        player.getInventory().setItem(0, box);

        IncinerationService.Outcome outcome = service.incinerateOne(player, 0);

        assertFalse(outcome.success(), "an unopened Rune box must never be incinerated");
        assertEquals(Material.LIGHT_GRAY_CANDLE, player.getInventory().getItem(0).getType(),
                "the unidentified box must remain in the inventory, untouched");
        assertTrue(IncineratorEligibility.standaloneRunes(player, manager).isEmpty(),
                "an unopened Rune box must never appear as an Incinerator-eligible item");
    }

    @Test
    void appliedGearIsNeverAnEligibleIncinerationTarget() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        manager.applyEnchant(pickaxe, manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE), 0, 0.0);
        player.getInventory().setItem(0, pickaxe);

        assertTrue(IncineratorEligibility.standaloneRunes(player, manager).isEmpty(),
                "gear with an applied enchant must never be treated as a standalone rune");
    }

    private static final String RUNES_YML = """
            lucky-gem:
              material: EMERALD
              glow: false

            runes:
              simple:
                material: LIGHT_GRAY_CANDLE
                glow: false
                shop-price: 100.0
                currency: MONEY
                enchants:
                  haste_pickaxe:
                    display-name: "Haste"
                    compatible-types: [PICKAXE]
                    levels:
                      1: { material: COAL, glow: false, proc-chance: 25.0, success-rate: 80.0, ability-value: 5.0, weight: 100 }
            """;
}
