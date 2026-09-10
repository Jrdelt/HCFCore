package me.vertex.core.enchant;

import me.vertex.core.dupe.DupeManager;
import me.vertex.core.dupe.DupeStorage;
import me.vertex.core.item.ItemKind;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link EnchantManager}'s rolling, level-replacement, success/
 * failure consumption, Lucky Gem math, world-restriction suppression,
 * vanilla-transform persistence, and the {@code DupeManager} integration --
 * everything sections 12/16/17/18/24/23/26 require.
 *
 * <p>Uses a small hand-written {@code enchants.yml}/{@code runes.yml} (not
 * the shipped defaults) written into the mock plugin's data folder before
 * {@link EnchantManager#load()} runs, so every test's odds/levels stay
 * fixed regardless of how the shipped resource files are tuned later.
 */
class EnchantManagerTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private TrackedItemIds trackedItemIds;
    private EnchantManager manager;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        writeConfig(plugin.getDataFolder().toPath().resolve("enchants.yml"), ENCHANTS_YML);
        writeConfig(plugin.getDataFolder().toPath().resolve("runes.yml"), RUNES_YML);

        trackedItemIds = new TrackedItemIds(plugin);
        manager = new EnchantManager(plugin, trackedItemIds);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static void writeConfig(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Rune rolling
    // ------------------------------------------------------------------

    @Test
    void rollingATierAlwaysUsesThatTiersTable() {
        EnchantManager.RollOutcome outcome = manager.rollRune(RuneTier.SIMPLE, 0.0);
        assertTrue(outcome.ok());
        assertEquals("haste_pickaxe", outcome.enchantId());
        assertEquals(1, outcome.level());
        assertNotNull(outcome.createdItem());
    }

    @Test
    void rolledItemIsTaggedAsAnEnchantmentItemForDuplicationTracking() {
        EnchantManager.RollOutcome outcome = manager.rollRune(RuneTier.SIMPLE, 0.0);
        assertEquals(ItemKind.ENCHANTMENT_ITEM, trackedItemIds.kind(outcome.createdItem()).orElse(null));
        assertTrue(trackedItemIds.instanceId(outcome.createdItem()).isPresent());
    }

    @Test
    void aRuneIsTaggedAsARuneForDuplicationTracking() {
        ItemStack rune = manager.createRune(RuneTier.SIMPLE);
        assertEquals(ItemKind.RUNE, trackedItemIds.kind(rune).orElse(null));
    }

    @Test
    void anEmptyTierTableRollsNothing() {
        EnchantManager.RollOutcome outcome = manager.rollRune(null, 0.0);
        assertFalse(outcome.ok());
        assertNull(outcome.createdItem());
    }

    // ------------------------------------------------------------------
    // Level-replacement rules (section 16)
    // ------------------------------------------------------------------

    @Test
    void higherLevelReplacesLowerAndConsumesOnlyOnSuccess() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemStack levelOne = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        EnchantManager.ApplyOutcome first = manager.applyEnchant(pickaxe, levelOne, 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.SUCCESS, first.result());
        assertEquals(1, manager.levelOf(pickaxe, "haste_pickaxe"));

        ItemStack levelTwo = manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE);
        EnchantManager.ApplyOutcome second = manager.applyEnchant(pickaxe, levelTwo, 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.SUCCESS, second.result());
        assertTrue(second.consumedEnchantItem());
        assertEquals(2, manager.levelOf(pickaxe, "haste_pickaxe"));
    }

    @Test
    void lowerLevelAgainstHigherRejectsWithoutConsuming() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        manager.applyEnchant(pickaxe, manager.createEnchantItem("haste_pickaxe", 3, RuneTier.SIMPLE), 0, 0.0);
        assertEquals(3, manager.levelOf(pickaxe, "haste_pickaxe"));

        ItemStack levelTwo = manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE);
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(pickaxe, levelTwo, 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.REJECT_HIGHER_EXISTS, outcome.result());
        assertFalse(outcome.consumedEnchantItem());
        assertEquals(3, manager.levelOf(pickaxe, "haste_pickaxe"), "the higher level must be kept, not overwritten");
    }

    @Test
    void equalLevelRejectsWithoutConsuming() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        manager.applyEnchant(pickaxe, manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE), 0, 0.0);

        ItemStack anotherLevelTwo = manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE);
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(pickaxe, anotherLevelTwo, 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.REJECT_EQUAL_LEVEL, outcome.result());
        assertFalse(outcome.consumedEnchantItem());
    }

    @Test
    void incompatibleItemTypeRejectsWithoutConsuming() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(dirt, enchantItem, 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.REJECT_INCOMPATIBLE, outcome.result());
        assertFalse(outcome.consumedEnchantItem());
        assertEquals(0, manager.levelOf(dirt, "haste_pickaxe"));
    }

    // ------------------------------------------------------------------
    // Application success/failure consumption (section 17)
    // ------------------------------------------------------------------

    @Test
    void aSuccessfulApplicationConsumesTheEnchantItemAndUpdatesTheTarget() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(pickaxe, enchantItem, 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.SUCCESS, outcome.result());
        assertTrue(outcome.consumedEnchantItem());
        assertEquals(1, manager.levelOf(pickaxe, "haste_pickaxe"));
    }

    @Test
    void aFailedApplicationStillConsumesTheEnchantItemButChangesNothing() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE); // 80% success rate
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(pickaxe, enchantItem, 0, 0.999);
        assertEquals(EnchantManager.ApplyResult.FAILURE, outcome.result());
        assertTrue(outcome.consumedEnchantItem(), "a valid attempt consumes the item even when the roll fails");
        assertEquals(0, manager.levelOf(pickaxe, "haste_pickaxe"), "a failed application must not apply the enchant");
    }

    @Test
    void rejectionsNeverConsumeTheEnchantItem() {
        ItemStack dirt = new ItemStack(Material.DIRT);
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        // roll=0.0 would guarantee success if this were a valid attempt -- proving
        // the rejection short-circuits before any random roll is even consulted.
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(dirt, enchantItem, 0, 0.0);
        assertFalse(outcome.consumedEnchantItem());
    }

    // ------------------------------------------------------------------
    // Lucky Gem success-boost math (section 18)
    // ------------------------------------------------------------------

    @Test
    void luckyGemsIncreaseTheEffectiveChanceByThePerTierAmount() {
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE); // base 80%, SIMPLE = +10/gem
        assertEquals(80.0, manager.effectiveChance(enchantItem, 0), 0.0001);
        assertEquals(90.0, manager.effectiveChance(enchantItem, 1), 0.0001);
    }

    @Test
    void luckyGemsNeverPushTheChanceAboveOneHundred() {
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE); // base 80%, +10/gem
        assertEquals(100.0, manager.effectiveChance(enchantItem, 3), 0.0001, "80 + 3*10 = 110 must clamp to 100");
    }

    @Test
    void higherRuneTiersGetASmallerPerGemBoostThanLowerTiers() {
        ItemStack fromSimple = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        ItemStack fromLegendary = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.LEGENDARY);
        double simpleBoost = manager.effectiveChance(fromSimple, 1) - manager.effectiveChance(fromSimple, 0);
        double legendaryBoost = manager.effectiveChance(fromLegendary, 1) - manager.effectiveChance(fromLegendary, 0);
        assertTrue(simpleBoost > legendaryBoost,
                "SIMPLE (configured 10/gem) must boost more per gem than LEGENDARY (configured 1/gem)");
    }

    // ------------------------------------------------------------------
    // World-restriction effect suppression (section 24)
    // ------------------------------------------------------------------

    @Test
    void aDisabledWorldSuppressesTheEffectButKeepsTheEnchantOnTheItem() {
        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
        ItemStack enchantItem = manager.createEnchantItem("frozen_step", 1, RuneTier.SIMPLE);
        manager.applyEnchant(boots, enchantItem, 0, 0.0);

        assertEquals(1, manager.levelOf(boots, "frozen_step"), "the enchant must remain stored regardless of world");
        assertFalse(manager.isEffectActive(boots, "frozen_step", "nether_test_world"),
                "frozen_step is configured disabled in nether_test_world");
        assertTrue(manager.isEffectActive(boots, "frozen_step", "overworld_test_world"));
    }

    // ------------------------------------------------------------------
    // Lore / vanilla-enchant coexistence (sections 17/21/22)
    // ------------------------------------------------------------------

    @Test
    void successfulApplicationPreservesPreExistingLoreAndVanillaEnchants() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        meta.lore(List.of(Component.text("A trusty pickaxe")));
        meta.addEnchant(Enchantment.EFFICIENCY, 3, true);
        pickaxe.setItemMeta(meta);

        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        manager.applyEnchant(pickaxe, enchantItem, 0, 0.0);

        ItemMeta after = pickaxe.getItemMeta();
        assertTrue(after.hasEnchant(Enchantment.EFFICIENCY), "a pre-existing vanilla enchant must survive");
        assertEquals(3, after.getEnchantLevel(Enchantment.EFFICIENCY));
        assertTrue(after.lore().stream().anyMatch(EnchantManagerTest::isTrustyPickaxeLine),
                "pre-existing lore must be preserved beneath the new custom-enchant lore");
        assertTrue(after.lore().size() > 1, "the custom enchant's own lore lines must have been appended");
    }

    /**
     * Compares plain text rather than exact {@link Component} equality: the
     * base-lore snapshot round-trips through {@code MessageFormatter}'s
     * (de)serializer, which -- like every other rendered line in this
     * codebase -- normalises the italic decoration to explicitly-off. That
     * is the correct, desired behaviour (it is what every other lore line
     * in Vertex already gets), not a loss of the original content.
     */
    private static boolean isTrustyPickaxeLine(Component line) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(line).equals("A trusty pickaxe");
    }

    @Test
    void reapplyingAHigherLevelStillPreservesTheOriginalBaseLore() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        meta.lore(List.of(Component.text("A trusty pickaxe")));
        pickaxe.setItemMeta(meta);

        manager.applyEnchant(pickaxe, manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE), 0, 0.0);
        manager.applyEnchant(pickaxe, manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE), 0, 0.0);

        List<Component> lore = pickaxe.getItemMeta().lore();
        assertTrue(lore.stream().anyMatch(EnchantManagerTest::isTrustyPickaxeLine),
                "the base lore snapshot must survive a second (level-replacing) application");
    }

    // ------------------------------------------------------------------
    // Persistence across legitimate vanilla transformations (section 23)
    // ------------------------------------------------------------------

    @Test
    void preserveAcrossTransformCopiesEnchantDataOntoAFreshResultItem() {
        ItemStack from = new ItemStack(Material.DIAMOND_PICKAXE);
        manager.applyEnchant(from, manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE), 0, 0.0);
        assertEquals(2, manager.levelOf(from, "haste_pickaxe"));

        // Simulates a vanilla anvil/smithing result: a distinct ItemStack this
        // plugin cannot assume already carries the source's PDC.
        ItemStack to = new ItemStack(Material.NETHERITE_PICKAXE);

        boolean copied = manager.preserveAcrossTransform(from, to);
        assertTrue(copied);
        assertEquals(2, manager.levelOf(to, "haste_pickaxe"));
        assertTrue(trackedItemIds.instanceId(to).isPresent());
        assertEquals(trackedItemIds.instanceId(from), trackedItemIds.instanceId(to));
    }

    @Test
    void preserveAcrossTransformNeverTouchesVanillaEnchantsAlreadyOnTheResult() {
        ItemStack from = new ItemStack(Material.DIAMOND_PICKAXE);
        manager.applyEnchant(from, manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE), 0, 0.0);

        ItemStack to = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta toMeta = to.getItemMeta();
        toMeta.addEnchant(Enchantment.EFFICIENCY, 4, true);
        to.setItemMeta(toMeta);

        manager.preserveAcrossTransform(from, to);

        assertTrue(to.getItemMeta().hasEnchant(Enchantment.EFFICIENCY),
                "vanilla enchants already on the computed result must never be touched");
        assertEquals(4, to.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY));
    }

    @Test
    void preserveAcrossTransformIsANoOpForAnUnrelatedItem() {
        ItemStack from = new ItemStack(Material.DIRT);
        ItemStack to = new ItemStack(Material.DIRT);
        assertFalse(manager.preserveAcrossTransform(from, to));
    }

    // ------------------------------------------------------------------
    // Duplication-tagging integration with DupeManager (section 26)
    // ------------------------------------------------------------------

    @Test
    void taggedRuneAndEnchantItemsReportThroughToDupeManager() throws Exception {
        Database database = new Database(new YamlConfiguration(), dataFolder.toFile());
        DupeStorage dupeStorage = new DupeStorage(database);
        dupeStorage.init();
        SqlStorage sqlStorage = new SqlStorage(database);
        sqlStorage.init();
        Messages messages = new Messages(plugin, new UserManager(plugin, sqlStorage));
        DupeManager dupeManager = new DupeManager(plugin, dupeStorage, messages, trackedItemIds);
        dupeManager.load();
        try {
            ItemStack rune = manager.createRune(RuneTier.SIMPLE);
            String runeId = dupeManager.ensureIdentity(rune);
            assertNotNull(runeId, "a Rune must be recognised as trackable via its TrackedItemIds kind marker");

            ItemStack enchantItem = manager.rollRune(RuneTier.SIMPLE, 0.0).createdItem();
            String enchantItemId = dupeManager.ensureIdentity(enchantItem);
            assertNotNull(enchantItemId, "a physical enchant item must be recognised as trackable");

            ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
            manager.applyEnchant(pickaxe, manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE), 0, 0.0);
            String enchantedId = dupeManager.ensureIdentity(pickaxe);
            assertNotNull(enchantedId, "a successfully-enchanted target item must be recognised as trackable");

            ItemStack plain = new ItemStack(Material.DIRT);
            assertNull(dupeManager.ensureIdentity(plain), "an untagged, non-configured item must remain untracked");
        } finally {
            dupeManager.awaitWrites();
            database.close();
        }
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------

    private static final String ENCHANTS_YML = """
            enchants:
              haste_pickaxe:
                display-name: "Haste"
                compatible-types: [PICKAXE]
                enabled-worlds: []
                disabled-worlds: []
                levels:
                  1:
                    material: COAL
                    name: "Haste I"
                    lore: ["Level {level}", "Success {success_rate}"]
                    glow: false
                    proc-chance: 25.0
                    success-rate: 80.0
                    ability-value: 5.0
                  2:
                    material: COAL_BLOCK
                    name: "Haste II"
                    lore: ["Level {level}"]
                    glow: false
                    proc-chance: 20.0
                    success-rate: 60.0
                    ability-value: 10.0
                  3:
                    material: DIAMOND
                    name: "Haste III"
                    lore: ["Level {level}"]
                    glow: true
                    proc-chance: 15.0
                    success-rate: 40.0
                    ability-value: 15.0
              frozen_step:
                display-name: "Frozen Step"
                compatible-types: [BOOTS]
                enabled-worlds: []
                disabled-worlds: ["nether_test_world"]
                levels:
                  1:
                    material: ICE
                    name: "Frozen Step I"
                    lore: ["Level {level}"]
                    glow: false
                    proc-chance: 30.0
                    success-rate: 90.0
                    ability-value: 1.0
            """;

    private static final String RUNES_YML = """
            lucky-gem:
              material: EMERALD
              name: "Lucky Gem"
              glow: false
              lore: ["Boost"]

            lucky-gem-effectiveness:
              SIMPLE: 10.0
              ELITE: 5.0
              RARE: 2.0
              LEGENDARY: 1.0

            runes:
              SIMPLE:
                material: AMETHYST_SHARD
                name: "Simple Rune"
                glow: false
                shop-price: 100.0
                lore: ["Tier {tier}"]
                table:
                  - { enchant: haste_pickaxe, level: 1, weight: 100 }
              ELITE:
                material: QUARTZ
                name: "Elite Rune"
                glow: false
                shop-price: 200.0
                lore: []
                table:
                  - { enchant: haste_pickaxe, level: 2, weight: 100 }
              RARE:
                material: AMETHYST_CLUSTER
                name: "Rare Rune"
                glow: false
                shop-price: 300.0
                lore: []
                table:
                  - { enchant: haste_pickaxe, level: 3, weight: 100 }
              LEGENDARY:
                material: NETHER_STAR
                name: "Legendary Rune"
                glow: false
                shop-price: 400.0
                lore: []
                table:
                  - { enchant: haste_pickaxe, level: 3, weight: 100 }
            """;
}
