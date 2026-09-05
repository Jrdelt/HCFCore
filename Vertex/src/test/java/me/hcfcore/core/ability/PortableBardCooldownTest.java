package me.hcfcore.core.ability;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableBardCooldownTest {

    private static final String PORTABLE_BARD = "portable-bard";

    private ServerMock server;
    private PluginMock plugin;
    private UserManager userManager;
    private AbilityManager abilityManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        userManager = new UserManager(plugin, new InMemoryStorage());
        abilityManager = new AbilityManager(plugin, new InMemoryStorage());
        abilityManager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void fullGoldArmorGetsTheShortBardCooldown() {
        PlayerMock player = server.addPlayer("Bard");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);

        assertEquals(6, abilityManager.effectiveCooldownSeconds(player, portableBard()));
    }

    @Test
    void withoutTheBardSetTheLongCooldownStillApplies() {
        PlayerMock player = server.addPlayer("Naked");

        assertEquals(300, abilityManager.effectiveCooldownSeconds(player, portableBard()));
    }

    @Test
    void theMageSetIsNotABardDespiteItsGoldHelmetAndBoots() {
        // The mage kit wears a gold helmet and gold boots over a chainmail
        // chestplate and leggings, so a check looser than "all four gold"
        // would hand every mage the bard's short cooldown.
        PlayerMock player = server.addPlayer("Mage");
        wear(player, Material.GOLDEN_BOOTS, Material.CHAINMAIL_LEGGINGS,
                Material.CHAINMAIL_CHESTPLATE, Material.GOLDEN_HELMET);

        assertEquals(300, abilityManager.effectiveCooldownSeconds(player, portableBard()));
    }

    @Test
    void aPartialGoldSetIsNotABard() {
        PlayerMock player = server.addPlayer("Partial");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, null);

        assertEquals(300, abilityManager.effectiveCooldownSeconds(player, portableBard()));
    }

    @Test
    void wornGoldArmorStillCountsAsTheBardSet() {
        // Matching is by material precisely so that combat damage and the
        // donator variant's extra enchantments don't drop a player out of
        // their class mid-fight.
        PlayerMock player = server.addPlayer("Veteran");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta) {
                meta.setDamage(meta.getDamage() + 7);
                piece.setItemMeta(meta);
            }
        }

        assertEquals(6, abilityManager.effectiveCooldownSeconds(player, portableBard()));
    }

    @Test
    void otherAbilitiesAreUnaffectedByWearingGold() {
        PlayerMock player = server.addPlayer("Bard2");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);

        Ability leap = abilityManager.get("leap");
        assertNotNull(leap);
        assertEquals(leap.getCooldownSeconds(),
                abilityManager.effectiveCooldownSeconds(player, leap),
                "the bard discount is specific to Portable Bard");
    }

    @Test
    void startCooldownStoresTheShortExpiryForABard() {
        // The end-to-end effect: what actually lands in the player's
        // cooldown map is the 6s expiry, not the 300s one.
        PlayerMock player = server.addPlayer("Bard3");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);
        userManager.load(player.getUniqueId());
        User user = userManager.get(player.getUniqueId());
        assertNotNull(user);

        Ability bard = portableBard();
        abilityManager.startCooldown(player, user, bard);

        long remainingSeconds = abilityManager.remainingCooldownMillis(user, bard) / 1000;
        assertTrue(remainingSeconds <= 6 && remainingSeconds >= 4,
                "expected roughly 6s of cooldown, got " + remainingSeconds + "s");
    }

    @Test
    void eachBuffItemIsItsOwnAbilityWithASevenSecondCooldown() {
        // Each buff is now a standalone physical item/ability (its own
        // cooldown, material, effect config) rather than a GUI-only
        // construct sharing the parent ability's cooldown bucket.
        for (String buffId : List.of("bard-buff-speed", "bard-buff-strength", "bard-buff-resistance",
                "bard-buff-regeneration", "bard-buff-jump-boost")) {
            Ability buff = abilityManager.get(buffId);
            assertNotNull(buff, "abilities.yml should define " + buffId);
            assertEquals(7, buff.getCooldownSeconds(), buffId + " should have its own 7s cooldown");
            assertNotNull(PotionEffectType.getByName(buff.getString("effect-type", "")),
                    buffId + " should have a valid effect-type");
        }
    }

    @Test
    void gearingIntoGoldShortensAnOutOfClassCooldown() {
        // Used the item in another kit (300s), then geared into gold: the
        // out-of-class penalty shouldn't follow them into the bard kit.
        PlayerMock player = server.addPlayer("Swapper");
        User user = loadedUser(player);
        Ability bard = portableBard();
        abilityManager.startCooldown(player, user, bard);
        assertTrue(abilityManager.remainingCooldownMillis(user, bard) > 200_000L,
                "expected the long out-of-class cooldown to be in place first");

        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);
        long remaining = abilityManager.shortenBardCooldownForKitSwap(player, user);

        assertTrue(remaining > 0 && remaining <= 6, "expected the 6s bard wait, got " + remaining + "s");
        assertTrue(abilityManager.remainingCooldownMillis(user, bard) <= 6_000L);
    }

    @Test
    void theDiscountDoesNothingWithoutTheGoldSet() {
        PlayerMock player = server.addPlayer("StillDiamond");
        User user = loadedUser(player);
        Ability bard = portableBard();
        abilityManager.startCooldown(player, user, bard);

        assertEquals(-1L, abilityManager.shortenBardCooldownForKitSwap(player, user));
        assertTrue(abilityManager.remainingCooldownMillis(user, bard) > 200_000L,
                "a non-bard keeps the full cooldown");
    }

    @Test
    void theDiscountNeverExtendsAShorterCooldown() {
        // A bard who just used it has ~6s left; re-equipping a gold piece
        // must not push that back up to a fresh 6s every time.
        PlayerMock player = server.addPlayer("Bard6");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);
        User user = loadedUser(player);
        Ability bard = portableBard();
        abilityManager.startCooldown(player, user, bard);
        long before = abilityManager.remainingCooldownMillis(user, bard);

        assertEquals(-1L, abilityManager.shortenBardCooldownForKitSwap(player, user));
        assertTrue(abilityManager.remainingCooldownMillis(user, bard) <= before);
    }

    private User loadedUser(PlayerMock player) {
        userManager.load(player.getUniqueId());
        User user = userManager.get(player.getUniqueId());
        assertNotNull(user);
        return user;
    }

    private Ability portableBard() {
        Ability ability = abilityManager.get(PORTABLE_BARD);
        assertNotNull(ability, "the bundled abilities.yml should define " + PORTABLE_BARD);
        return ability;
    }

    /** Sets armor in Bukkit's boots-to-helmet order. */
    private static void wear(PlayerMock player, Material boots, Material leggings,
                             Material chestplate, Material helmet) {
        player.getInventory().setArmorContents(new ItemStack[]{
                boots == null ? null : new ItemStack(boots),
                leggings == null ? null : new ItemStack(leggings),
                chestplate == null ? null : new ItemStack(chestplate),
                helmet == null ? null : new ItemStack(helmet),
        });
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
