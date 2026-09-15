package me.vertex.core.enchant;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the shipped Rune pool structure and the deliberate movement order. */
class ShippedRuneConfigurationTest {

    private static final File RUNES_FILE = new File("src/main/resources/runes.yml");
    private static final File ENCHANTS_FILE = new File("src/main/resources/enchants.yml");

    @Test
    void normalTierPoolsHaveFiveDistinctRuneTypesWithNoCrossTierOverlap() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(RUNES_FILE);
        Map<RuneTier, Set<String>> byTier = new EnumMap<>(RuneTier.class);

        for (RuneTier tier : RuneTier.values()) {
            ConfigurationSection section = config.getConfigurationSection("runes." + tier.name());
            assertNotNull(section, tier + " must have a Rune pool");
            Set<String> ids = new HashSet<>();
            for (Map<?, ?> entry : section.getMapList("table")) {
                Object id = entry.get("enchant");
                assertNotNull(id, tier + " entries must name an enchant");
                ids.add(String.valueOf(id));
            }
            assertEquals(5, ids.size(), tier + " must contain exactly five Rune types");
            byTier.put(tier, ids);
        }

        Set<String> allIds = new HashSet<>();
        for (Map.Entry<RuneTier, Set<String>> entry : byTier.entrySet()) {
            for (String id : entry.getValue()) {
                assertTrue(allIds.add(id), id + " must not appear in more than one normal Rune tier");
            }
        }
    }

    @Test
    void skyStepperIsConfiguredAheadOfDasherAndBothHaveLiveSettings() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(ENCHANTS_FILE);
        ConfigurationSection skyStepper = config.getConfigurationSection("enchants.sky_stepper");
        ConfigurationSection dasher = config.getConfigurationSection("enchants.dasher");
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
