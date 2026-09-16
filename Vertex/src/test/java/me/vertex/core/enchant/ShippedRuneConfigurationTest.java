package me.vertex.core.enchant;

import me.vertex.core.item.TrackedItemIds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the shipped Rune pool structure -- one file, every tier, no cross-tier overlap -- and the deliberate movement order. */
class ShippedRuneConfigurationTest {

    private static final File RUNES_FILE = new File("src/main/resources/customEnchants/runes.yml");

    @Test
    void everyNormalTierHasItsExpectedDistinctRuneTypesWithNoCrossTierOverlap() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        Map<RuneTier, Set<String>> byTier = new EnumMap<>(RuneTier.class);

        // Seasonal is the admin/event-distributed crate set: 10 gear-applied
        // items (4 armor + 3 weapon + 3 farming/mining tools). War Chest is an
        // 11th item but is configured separately under `war-chest:` since it's
        // a backpack tier, not something applied to gear. Simple/Elite each
        // carry 4 base enchants (Sky Stepper/Dasher's movement-burst pair);
        // Rare/Legendary/Arena are cut to 3 -- but every effect id below still
        // appears in exactly one tier, so no ability is a stronger reskin of
        // another tier's ability under a different name.
        Map<RuneTier, Integer> expectedCounts = Map.of(
                RuneTier.SIMPLE, 4, RuneTier.ELITE, 4, RuneTier.RARE, 3,
                RuneTier.LEGENDARY, 3, RuneTier.ARENA, 3, RuneTier.SEASONAL, 10);

        for (RuneTier tier : RuneTier.values()) {
            String tierKey = tier.name().toLowerCase(Locale.ROOT);
            ConfigurationSection enchants = config.getConfigurationSection("runes." + tierKey + ".enchants");
            assertNotNull(enchants, tier + " must have a Rune pool");
            Set<String> ids = enchants.getKeys(false);
            int expected = expectedCounts.get(tier);
            assertEquals(expected, ids.size(), tier + " must contain exactly " + expected + " Rune types");
            byTier.put(tier, new HashSet<>(ids));
        }

        Set<String> allIds = new HashSet<>();
        for (Map.Entry<RuneTier, Set<String>> entry : byTier.entrySet()) {
            for (String id : entry.getValue()) {
                assertTrue(allIds.add(id), id + " must not appear in more than one Rune tier");
            }
        }
    }

    /**
     * The 3-per-tier cut is only meaningful if the effect each rune performs
     * is also unique server-wide -- otherwise two differently-named runes
     * could still be "the same ability, again, but bigger" under the hood.
     */
    @Test
    void everyNormalTierEffectIsUniqueAcrossTheWholeFile() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        Set<String> effectsSeen = new HashSet<>();
        for (RuneTier tier : RuneTier.values()) {
            if (tier == RuneTier.SEASONAL) {
                continue;
            }
            String tierKey = tier.name().toLowerCase(Locale.ROOT);
            ConfigurationSection enchants = config.getConfigurationSection("runes." + tierKey + ".enchants");
            assertNotNull(enchants, tier + " must have a Rune pool");
            for (String id : enchants.getKeys(false)) {
                String effect = enchants.getConfigurationSection(id).getString("effect");
                assertNotNull(effect, tier + "." + id + " must declare an effect");
                assertTrue(effectsSeen.add(effect), "effect " + effect + " (from " + tier + "." + id
                        + ") must not be reused by any other base-tier rune");
            }
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Loading the real shipped file through {@link EnchantManager} end to
     * end -- every tier (including the generated Arena levels/roll-table
     * rows) must produce a non-empty roll table, since a typo here would
     * otherwise only surface as an empty Rune Shop tier in production.
     */
    @Test
    void shippedFileLoadsIntoANonEmptyRollTableForEveryTier() throws IOException {
        MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        Path target = plugin.getDataFolder().toPath().resolve("customEnchants/runes.yml");
        Files.createDirectories(target.getParent());
        Files.copy(RUNES_FILE.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

        EnchantManager manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();

        for (RuneTier tier : RuneTier.values()) {
            if (tier == RuneTier.SEASONAL) {
                // Deliberately never rollable -- every level omits `weight`.
                assertTrue(manager.rollTable(tier).isEmpty(), "Seasonal must never be rollable");
                continue;
            }
            assertFalse(manager.rollTable(tier).isEmpty(), tier + "'s roll table must not be empty");
        }
        assertEquals(60, manager.rollTable(RuneTier.ARENA).entries().size(),
                "3 Arena enchants x 20 generated levels each");
    }

    @Test
    void riftwalkerIsConfiguredWithLiveMovementSettings() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        ConfigurationSection riftwalker = config.getConfigurationSection("runes.legendary.enchants.riftwalker");
        assertNotNull(riftwalker);

        assertEquals("RIFTWALKER", riftwalker.getString("effect"));
        assertTrue(riftwalker.getBoolean("blocked-in-combat"));
        assertFalse(riftwalker.getConfigurationSection("levels.1.effect-settings").getKeys(false).isEmpty());
    }

    /**
     * Every enchant the 3-per-tier cut retired lives on in {@code legacy:}
     * (Arena's retired enchants excepted -- see the file's own header
     * comment) purely so an already-owned item keeps rendering; this is
     * never a second roll pool.
     */
    @Test
    void everyLegacyEnchantHasNoWeightAnywhereAndNeverJoinsARollTable() throws IOException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        ConfigurationSection legacy = config.getConfigurationSection("legacy");
        assertNotNull(legacy);
        assertFalse(legacy.getKeys(false).isEmpty(), "legacy: must retain at least the enchants the 3-per-tier cut removed");
        for (String id : legacy.getKeys(false)) {
            ConfigurationSection levels = legacy.getConfigurationSection(id + ".levels");
            assertNotNull(levels, "legacy." + id + " must still declare its levels");
            for (String level : levels.getKeys(false)) {
                assertFalse(levels.getConfigurationSection(level).contains("weight"),
                        "legacy." + id + ".levels." + level + " must not carry a roll weight");
            }
        }

        MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        Path target = plugin.getDataFolder().toPath().resolve("customEnchants/runes.yml");
        Files.createDirectories(target.getParent());
        Files.copy(RUNES_FILE.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        EnchantManager manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        for (RuneTier tier : RuneTier.values()) {
            for (var entry : manager.rollTable(tier).entries()) {
                assertFalse(legacy.contains(entry.enchantId()),
                        entry.enchantId() + " is in legacy: and must never appear in a live roll table");
            }
        }
    }
}
