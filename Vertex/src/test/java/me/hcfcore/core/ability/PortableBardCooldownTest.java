package me.hcfcore.core.ability;

import me.hcfcore.core.storage.Storage;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PortableBardCooldownTest {

    private static final String PORTABLE_BARD = "portable-bard";
    private static final List<String> BUFF_IDS = List.of("bard-buff-speed", "bard-buff-strength",
            "bard-buff-resistance", "bard-buff-regeneration", "bard-buff-jump-boost");

    private PluginMock plugin;
    private AbilityManager abilityManager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        abilityManager = new AbilityManager(plugin, new InMemoryStorage());
        abilityManager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void theMasterItemHasNoCooldown() {
        // Opening the buff menu and receiving a buff item is free -- the
        // cooldown lives on each buff item once it's actually used, not on
        // reopening the menu.
        assertEquals(0, portableBard().getCooldownSeconds());
    }

    @Test
    void eachBuffItemIsItsOwnAbilityWithASevenSecondCooldown() {
        for (String buffId : BUFF_IDS) {
            Ability buff = abilityManager.get(buffId);
            assertNotNull(buff, "abilities.yml should define " + buffId);
            assertEquals(7, buff.getCooldownSeconds(), buffId + " should have its own 7s cooldown");
            assertNotNull(PotionEffectType.getByName(buff.getString("effect-type", "")),
                    buffId + " should have a valid effect-type");
        }
    }

    private Ability portableBard() {
        Ability ability = abilityManager.get(PORTABLE_BARD);
        assertNotNull(ability, "the bundled abilities.yml should define " + PORTABLE_BARD);
        return ability;
    }

    private static final class InMemoryStorage implements Storage {
        private final Map<UUID, Map<String, Long>> abilityCooldowns = new ConcurrentHashMap<>();

        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return abilityCooldowns.getOrDefault(uuid, Map.of());
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
            abilityCooldowns.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>()).put(abilityId, availableAt);
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
