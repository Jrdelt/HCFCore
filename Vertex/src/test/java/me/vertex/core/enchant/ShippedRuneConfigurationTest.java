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
    void everyNormalTierHasTenDistinctRuneTypesWithNoCrossTierOverlap() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        Map<RuneTier, Set<String>> byTier = new EnumMap<>(RuneTier.class);

        for (RuneTier tier : RuneTier.values()) {
            String tierKey = tier.name().toLowerCase(Locale.ROOT);
            ConfigurationSection enchants = config.getConfigurationSection("runes." + tierKey + ".enchants");
            assertNotNull(enchants, tier + " must have a Rune pool");
            Set<String> ids = enchants.getKeys(false);
            // Seasonal is the admin/event-distributed crate set: 11 gear-applied
            // items (4 armor + 4 weapon + 3 farming tools). War Chest is the
            // set's 12th item but is configured separately under `war-chest:`
            // since it's a backpack tier, not something applied to gear.
            int expected = tier == RuneTier.SEASONAL ? 11 : 10;
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
        assertEquals(200, manager.rollTable(RuneTier.ARENA).entries().size(),
                "10 Arena enchants x 20 generated levels each");
    }

    @Test
    void skyStepperIsConfiguredAheadOfDasherAndBothHaveLiveSettings() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        ConfigurationSection skyStepper = config.getConfigurationSection("runes.elite.enchants.sky_stepper");
        ConfigurationSection dasher = config.getConfigurationSection("runes.elite.enchants.dasher");
        assertNotNull(skyStepper);
        assertNotNull(dasher);

        assertEquals("SKY_STEPPER", skyStepper.getString("effect"));
        assertEquals("DASHER", dasher.getString("effect"));
        assertTrue(skyStepper.getInt("priority") > dasher.getInt("priority"),
                "Sky Stepper must be checked before Dasher when both can activate");
        assertTrue(skyStepper.getBoolean("blocked-in-combat"));
        assertTrue(dasher.getBoolean("blocked-in-combat"));
        assertFalse(skyStepper.getConfigurationSection("levels.1.effect-settings").getKeys(false).isEmpty());
        assertFalse(dasher.getConfigurationSection("levels.1.effect-settings").getKeys(false).isEmpty());
    }
}
