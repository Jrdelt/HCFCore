package me.hcfcore.core.ability;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbilityTest {

    private final Ability ability = new Ability(
            "leap", Material.FIREWORK_ROCKET, "&fLeap", List.of(), 45,
            Map.of("forward-multiplier", 1.4, "label", "up-and-away"));

    @Test
    void getDoubleReturnsTheConfiguredValue() {
        assertEquals(1.4, ability.getDouble("forward-multiplier", 99.0));
    }

    @Test
    void getDoubleFallsBackWhenTheKeyIsAbsentOrTheWrongType() {
        assertEquals(0.6, ability.getDouble("y-multiplier", 0.6));
        assertEquals(2.0, ability.getDouble("label", 2.0), "a non-numeric value should fall back too");
    }

    @Test
    void getIntCoercesAWholeNumberSetting() {
        Ability withInt = new Ability("bone", Material.BONE, "&fBone", List.of(), 60,
                Map.of("hits-required", 3));

        assertEquals(3, withInt.getInt("hits-required", 1));
        assertEquals(15, withInt.getInt("deny-seconds", 15));
    }

    @Test
    void getStringReturnsTheConfiguredValueOrTheDefault() {
        Ability withString = new Ability("repair", Material.ANVIL, "&fRepair", List.of(), 600,
                Map.of("permission-node", "essentials.fix"));

        assertEquals("essentials.fix", withString.getString("permission-node", "none"));
        assertEquals("none", withString.getString("missing-key", "none"));
    }
}
