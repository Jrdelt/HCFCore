package me.vertex.core.enchant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneProtectionTest {

    private static EnchantDefinition definitionWithTags(RuneTag... tags) {
        return new EnchantDefinition("dasher", "Dasher", "desc", Set.of("BOOTS"), Set.of(), Set.of(),
                Set.of(), Set.of(), "SPEED_BOOST", EnchantDefinition.EffectScope.ANY, EnchantDefinition.EffectScope.ANY,
                0, false, false, true, Set.of(tags),
                List.of(new EnchantDefinition.Level(1, org.bukkit.Material.FEATHER, null, false, 25.0, 80.0, 1.0, Map.of())));
    }

    @Test
    void emptyZoneListsAllowEverything() {
        EnchantDefinition definition = definitionWithTags(RuneTag.MOVEMENT);
        assertTrue(RuneProtection.tagsAllowed(definition, Set.of(), Set.of()));
    }

    @Test
    void blockedTagWinsRegardlessOfAllowedList() {
        EnchantDefinition definition = definitionWithTags(RuneTag.MOVEMENT, RuneTag.PASSIVE);
        assertFalse(RuneProtection.tagsAllowed(definition, Set.of("MOVEMENT"), Set.of("MOVEMENT")));
    }

    @Test
    void nonEmptyAllowedListActsAsWhitelist() {
        EnchantDefinition definition = definitionWithTags(RuneTag.MOVEMENT);
        assertFalse(RuneProtection.tagsAllowed(definition, Set.of(), Set.of("COMBAT")));
        assertTrue(RuneProtection.tagsAllowed(definition, Set.of(), Set.of("MOVEMENT")));
    }

    @Test
    void nullDefinitionIsAllowed() {
        assertTrue(RuneProtection.tagsAllowed(null, Set.of("MOVEMENT"), Set.of()));
    }
}
