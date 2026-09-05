package me.hcfcore.core.ability;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.User;
import me.hcfcore.core.worldguard.WorldGuardHook;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityManagerTest {

    private static final String[] EXPECTED_IDS = {
            "anti-blockup-bone", "fake-pearl", "grappling-hook", "leap",
            "pearl-stunner", "portable-bard", "rabbits-feed", "repair", "rogue-backstab",
            "mage-wither", "mage-slowness", "mage-poison",
            "switcher-snowball", "time-warp-pearl", "ninja-star",
            "bard-buff-speed", "bard-buff-strength", "bard-buff-resistance",
            "bard-buff-regeneration", "bard-buff-jump-boost", "jump-boost-feather"
    };

    private ServerMock server;
    private PluginMock plugin;
    private AbilityManager abilityManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        abilityManager = new AbilityManager(plugin, new InMemoryStorage());
        abilityManager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadRegistersAllShippedAbilities() {
        assertEquals(EXPECTED_IDS.length, abilityManager.getAbilities().size());
        for (String id : EXPECTED_IDS) {
            assertNotNull(abilityManager.get(id), "expected ability '" + id + "' to be registered");
        }
    }

    @Test
    void getIsCaseInsensitive() {
        assertNotNull(abilityManager.get("Grappling-Hook"));
        assertNotNull(abilityManager.get("GRAPPLING-HOOK"));
    }

    @Test
    void createItemTagsTheItemWithItsAbilityId() {
        Ability ability = abilityManager.get("leap");

        ItemStack item = abilityManager.createItem(ability);

        assertEquals(Material.FIREWORK_ROCKET, item.getType());
        NamespacedKey key = new NamespacedKey(plugin, AbilityManager.ABILITY_ID_KEY);
        String taggedId = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        assertEquals("leap", taggedId);
    }

    @Test
    void perAbilityCooldownBlocksUntilItExpires() {
        PlayerMock player = server.addPlayer("Alice");
        User user = new User(player.getUniqueId(), new HashMap<>(), null);
        Ability ability = abilityManager.get("repair");

        assertFalse(abilityManager.isOnCooldown(user, ability));

        abilityManager.startCooldown(player, user, ability);

        assertTrue(abilityManager.isOnCooldown(user, ability));
        long remaining = abilityManager.remainingCooldownMillis(user, ability);
        assertTrue(remaining > 0 && remaining <= ability.getCooldownSeconds() * 1000L,
                "expected remaining cooldown within the configured window, got " + remaining + "ms");
    }

    @Test
    void globalCooldownBlocksAnyAbilityRegardlessOfWhichOneTriggeredIt() {
        UUID uuid = UUID.randomUUID();

        assertFalse(abilityManager.isOnGlobalCooldown(uuid));

        abilityManager.markGlobalCooldown(uuid);

        assertTrue(abilityManager.isOnGlobalCooldown(uuid));
        long remaining = abilityManager.globalCooldownRemainingMillis(uuid);
        assertTrue(remaining > 0 && remaining <= 3_000L,
                "expected remaining time within the default 3s global cooldown, got " + remaining + "ms");
    }

    @Test
    void worldGuardHookGracefullyAllowsEverythingWhenWorldGuardIsNotInstalled() {
        PlayerMock player = server.addPlayer("Bob");

        assertFalse(WorldGuardHook.isInDisabledRegion(player, Set.of("spawn")));
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
