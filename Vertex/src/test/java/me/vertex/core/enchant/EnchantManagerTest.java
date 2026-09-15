package me.vertex.core.enchant;

import me.vertex.core.dupe.DupeManager;
import me.vertex.core.dupe.DupeStorage;
import me.vertex.core.enchant.listener.RuneEffectListener;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link EnchantManager}'s rolling, level-replacement, success/
 * failure consumption, Lucky Gem math, world-restriction suppression,
 * vanilla-transform persistence, and stack-safe item identity behavior --
 * everything sections 12/16/17/18/24/23/26 require.
 *
 * <p>Uses a small hand-written {@code customEnchants/runes.yml} (not the
 * shipped defaults) written into the mock plugin's data folder before
 * {@link EnchantManager#load()} runs, so every test's odds/levels stay
 * fixed regardless of how the shipped resource file is tuned later.
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
        writeConfig(plugin.getDataFolder().toPath().resolve("customEnchants/runes.yml"), RUNES_YML);

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
    void identicalRolledItemsCanStackButDifferentStoredDataCannot() {
        ItemStack first = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        ItemStack identical = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        ItemStack differentLevel = manager.createEnchantItem("haste_pickaxe", 2, RuneTier.SIMPLE);

        assertTrue(first.isSimilar(identical));
        assertFalse(first.isSimilar(differentLevel));
        assertTrue(trackedItemIds.instanceId(first).isEmpty());
    }

    @Test
    void baseRunesAndLuckyGemsRemainStackable() {
        assertTrue(manager.createRune(RuneTier.SIMPLE).isSimilar(manager.createRune(RuneTier.SIMPLE)));
        assertTrue(manager.createLuckyGem().isSimilar(manager.createLuckyGem()));
    }

    @Test
    void anEmptyTierTableRollsNothing() {
        EnchantManager.RollOutcome outcome = manager.rollRune(null, 0.0);
        assertFalse(outcome.ok());
        assertNull(outcome.createdItem());
    }

    @Test
    void rollingJittersSuccessAroundTheLevelsConfiguredRateAndVariesPerRoll() {
        // haste_pickaxe level 1's configured success-rate is 80%; the default
        // jitter is +/-10, so every roll must land in [70, 90] and, across
        // enough rolls, actually vary instead of always landing on 80 exactly.
        boolean sawDifferentValue = false;
        double first = manager.rollRune(RuneTier.SIMPLE, 0.0).createdItem() == null ? -1
                : manager.successChance(manager.rollRune(RuneTier.SIMPLE, 0.0).createdItem());
        for (int i = 0; i < 25; i++) {
            double success = manager.successChance(manager.rollRune(RuneTier.SIMPLE, 0.0).createdItem());
            assertTrue(success >= 70.0 && success <= 90.0, "success " + success + " must stay within the jitter band");
            if (Math.abs(success - first) > 0.0001) {
                sawDifferentValue = true;
            }
        }
        assertTrue(sawDifferentValue, "repeated rolls of the same enchant+level must not all land on the exact same success value");
    }

    @Test
    void skyStepperRunsBeforeDasherWhenBothMovementRunesAreReady() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOnGround(false);
        player.setInWater(false);
        player.setFlying(false);
        player.setVelocity(new Vector());

        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        assertEquals(EnchantManager.ApplyResult.SUCCESS,
                manager.applyEnchant(boots, manager.createEnchantItem("sky_stepper", 1, RuneTier.ELITE), 0, 0D).result());
        assertEquals(EnchantManager.ApplyResult.SUCCESS,
                manager.applyEnchant(boots, manager.createEnchantItem("dasher", 1, RuneTier.ELITE), 0, 0D).result());
        player.getInventory().setBoots(boots);

        // No User is ever loaded for this UUID, so RuneEffectListener treats the
        // player as never on cooldown -- exactly what this priority-ordering test needs.
        UserManager users = new UserManager(plugin, null);
        new RuneEffectListener(manager, null, users, new RuneCooldownStore(plugin, null))
                .onSneak(new PlayerToggleSneakEvent(player, true));

        assertEquals(1D, player.getVelocity().getY(), 0.0001,
                "Sky Stepper's upward launch proves it won over Dasher's small hop");
        assertEquals(0.2D, player.getVelocity().getZ(), 0.0001,
                "the first ready movement Rune must supply the full velocity");
    }

    @Test
    void crouchingAgainWhileBothMovementRunesAreCoolingDownSendsTheExactCountdown() throws Exception {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOnGround(false);
        player.setInWater(false);
        player.setFlying(false);
        player.setVelocity(new Vector());

        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        manager.applyEnchant(boots, manager.createEnchantItem("sky_stepper", 1, RuneTier.ELITE), 0, 0D);
        manager.applyEnchant(boots, manager.createEnchantItem("dasher", 1, RuneTier.ELITE), 0, 0D);
        player.getInventory().setBoots(boots);

        Database database = new Database(new YamlConfiguration(), dataFolder.toFile());
        SqlStorage storage = new SqlStorage(database);
        storage.init();
        UserManager userManager = new UserManager(plugin, storage);
        userManager.load(player.getUniqueId());
        RuneCooldownStore cooldownStore = new RuneCooldownStore(plugin, storage);
        Messages messages = new Messages(plugin, new UserManager(plugin, null));
        messages.load();

        RuneEffectListener listener = new RuneEffectListener(manager, null, userManager, cooldownStore);
        listener.setMessages(messages);

        // Seed both runes' cooldowns directly -- only the higher-priority
        // rune ever actually fires per crouch, so reaching "both on
        // cooldown" by crouching twice would never happen naturally; this
        // simulates the player having used each of them recently.
        User user = userManager.get(player.getUniqueId());
        cooldownStore.start(player, user, "sky_stepper", 17_000L);
        cooldownStore.start(player, user, "dasher", 17_000L);

        listener.onSneak(new PlayerToggleSneakEvent(player, true));

        assertEquals(new Vector(), player.getVelocity(), "a rune fully on cooldown must not move the player at all");
        String sent = player.nextMessage();
        assertNotNull(sent, "a cooldown message must be sent");
        assertTrue(sent.contains("Sky Stepper"), "the message must name the rune that's on cooldown: " + sent);
        assertTrue(sent.matches(".*\\d+\\.\\d+s.*"), "the message must include an exact numeric countdown: " + sent);
        database.close();
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
    void luckyGemsIncreaseStoredSuccessByTheUniversalAmount() {
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        ItemStack upgraded = manager.addLuckyGem(enchantItem);
        assertNotNull(upgraded);
        assertEquals(80.0, manager.successChance(enchantItem), 0.0001);
        assertEquals(83.5, manager.successChance(upgraded), 0.0001);
        assertFalse(enchantItem.isSimilar(upgraded), "different stored success values must not stack together");
    }

    @Test
    void luckyGemsNeverPushTheChanceAboveOneHundred() {
        ItemStack enchantItem = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        for (int index = 0; index < 6; index++) {
            enchantItem = manager.addLuckyGem(enchantItem);
            assertNotNull(enchantItem);
        }
        assertEquals(100.0, manager.successChance(enchantItem), 0.0001);
        assertNull(manager.addLuckyGem(enchantItem), "a capped Rune must not consume another Lucky Gem");
    }

    @Test
    void everyRuneTierGetsTheSameUniversalPerGemBoost() {
        ItemStack fromSimple = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.SIMPLE);
        ItemStack fromLegendary = manager.createEnchantItem("haste_pickaxe", 1, RuneTier.LEGENDARY);
        double simpleBoost = manager.successChance(manager.addLuckyGem(fromSimple)) - manager.successChance(fromSimple);
        double legendaryBoost = manager.successChance(manager.addLuckyGem(fromLegendary)) - manager.successChance(fromLegendary);
        assertEquals(3.5D, simpleBoost, 0.0001);
        assertEquals(3.5D, legendaryBoost, 0.0001);
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
        assertEquals(2, after.lore().size(), "only the base lore and one clean custom-enchant line should remain");
        Component enchantLine = after.lore().get(1);
        assertEquals("ʜᴀꜱᴛᴇ I", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(enchantLine));
        assertEquals(TextDecoration.State.FALSE, enchantLine.style().decoration(TextDecoration.ITALIC));
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
    // Duplication-tagging integration with DupeManager (applied gear only)
    // ------------------------------------------------------------------

    @Test
    void onlyAppliedGearReportsThroughToDupeManager() throws Exception {
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
            assertNull(dupeManager.ensureIdentity(rune), "stackable base Runes must not receive an instance ID");

            ItemStack enchantItem = manager.rollRune(RuneTier.SIMPLE, 0.0).createdItem();
            assertNull(dupeManager.ensureIdentity(enchantItem),
                    "stackable rolled enchant items must not receive an instance ID");

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

    private static final String RUNES_YML = """
            lucky-gem:
              material: EMERALD
              glow: false

            runes:
              simple:
                material: AMETHYST_SHARD
                glow: false
                shop-price: 100.0
                enchants:
                  haste_pickaxe:
                    display-name: "Haste"
                    compatible-types: [PICKAXE]
                    enabled-worlds: []
                    disabled-worlds: []
                    levels:
                      1: { material: COAL, glow: false, proc-chance: 25.0, success-rate: 80.0, ability-value: 5.0, weight: 100 }
                      2: { material: COAL_BLOCK, glow: false, proc-chance: 20.0, success-rate: 60.0, ability-value: 10.0 }
                      3: { material: DIAMOND, glow: true, proc-chance: 15.0, success-rate: 40.0, ability-value: 15.0 }
                  frozen_step:
                    display-name: "Frozen Step"
                    compatible-types: [BOOTS]
                    enabled-worlds: []
                    disabled-worlds: ["nether_test_world"]
                    levels:
                      1: { material: ICE, glow: false, proc-chance: 30.0, success-rate: 90.0, ability-value: 1.0 }
              elite:
                material: QUARTZ
                glow: false
                shop-price: 200.0
                enchants:
                  sky_stepper:
                    display-name: "Sky Stepper"
                    compatible-types: [BOOTS]
                    effect: SKY_STEPPER
                    priority: 200
                    levels:
                      1:
                        material: DIAMOND
                        proc-chance: 100.0
                        success-rate: 100.0
                        ability-value: 1.0
                        effect-settings: { upward-velocity: 1.0, forward-velocity: 0.2, cooldown-seconds: 17 }
                  dasher:
                    display-name: "Dasher"
                    compatible-types: [BOOTS]
                    effect: DASHER
                    priority: 100
                    levels:
                      1:
                        material: GOLD_INGOT
                        proc-chance: 100.0
                        success-rate: 100.0
                        ability-value: 2.0
                        effect-settings: { upward-velocity: 0.1, forward-velocity: 2.0, cooldown-seconds: 17 }
              rare:
                material: AMETHYST_CLUSTER
                glow: false
                shop-price: 300.0
              legendary:
                material: NETHER_STAR
                glow: true
                shop-price: 400.0
              arena:
                material: BLACK_CANDLE
                glow: false
                shop-price: 100
                currency: XP_LEVELS
            """;
}
