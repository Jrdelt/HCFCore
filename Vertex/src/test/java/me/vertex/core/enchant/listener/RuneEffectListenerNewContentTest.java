package me.vertex.core.enchant.listener;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the mechanisms newly applied to the tier-expansion content
 * (damage-source: PLAYER and target-filter: PLAYER on ordinary, non-Arena
 * tiers, a manually-authored, non-generated CORRUPTED_DETONATION, and
 * CROSSBOW as a compatible-types token) actually fire through the real
 * pipeline -- not just that the config parses. Every reused effect string
 * itself (DAMAGE_REDUCTION, PROJECTILE_DAMAGE, ORE_FORTUNE, CROP_BOUNTY,
 * KILL_HEAL, LIFESTEAL, EXECUTE, CORRUPTED_DETONATION) is already covered
 * generically by {@link me.vertex.core.enchant.EnchantManagerTest} and this
 * suite via the tier-1 enchants that first introduced them; this class only
 * targets the combinations genuinely new to the 5-to-10-per-tier content
 * expansion.
 */
class RuneEffectListenerNewContentTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private EnchantManager manager;
    private RuneEffectListener listener;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Path path = plugin.getDataFolder().toPath().resolve("customEnchants/runes.yml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, RUNES_YML, StandardCharsets.UTF_8);

        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        UserManager users = new UserManager(plugin, null);
        listener = new RuneEffectListener(manager, null, users, new RuneCooldownStore(plugin, null));
        world = MockBukkit.getMock().addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void damageSourcePlayerReducesOnlyPlayerDealtDamageNotMobDealtDamage() {
        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setLocation(new Location(world, 0, 64, 0));
        ItemStack chestplate = new ItemStack(Material.IRON_CHESTPLATE);
        manager.applyEnchant(chestplate, manager.createEnchantItem("guard_ward", 1, RuneTier.SIMPLE), 0, 0.0);
        victim.getInventory().setChestplate(chestplate);

        PlayerMock playerAttacker = MockBukkit.getMock().addPlayer();
        EntityDamageByEntityEvent fromPlayer = new EntityDamageByEntityEvent(
                playerAttacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
        listener.onDamage(fromPlayer);
        assertEquals(5D, fromPlayer.getDamage(), 0.0001, "a 50% PvP damage-reduction rune must halve player-dealt damage");

        Zombie mobAttacker = world.spawn(new Location(world, 1, 64, 0), Zombie.class);
        EntityDamageByEntityEvent fromMob = new EntityDamageByEntityEvent(
                mobAttacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
        listener.onDamage(fromMob);
        assertEquals(10D, fromMob.getDamage(), 0.0001, "a PvP-only damage-reduction rune must not touch mob-dealt damage");
    }

    @Test
    void targetFilterPlayerBoostsProjectileDamageOnlyAgainstPlayersNotMobs() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack bow = new ItemStack(Material.BOW);
        manager.applyEnchant(bow, manager.createEnchantItem("arrow_edge", 1, RuneTier.SIMPLE), 0, 0.0);
        attacker.getInventory().setItemInMainHand(bow);

        Arrow arrow = world.spawn(new Location(world, 0, 64, 0), Arrow.class);
        arrow.setShooter(attacker);

        PlayerMock playerVictim = MockBukkit.getMock().addPlayer();
        EntityDamageByEntityEvent atPlayer = new EntityDamageByEntityEvent(
                arrow, playerVictim, EntityDamageEvent.DamageCause.PROJECTILE, 5D);
        listener.onDamageByEntity(atPlayer);
        assertEquals(10D, atPlayer.getDamage(), 0.0001, "a PvP-only projectile rune must double damage against a player");

        Zombie mobVictim = world.spawn(new Location(world, 2, 64, 0), Zombie.class);
        EntityDamageByEntityEvent atMob = new EntityDamageByEntityEvent(
                arrow, mobVictim, EntityDamageEvent.DamageCause.PROJECTILE, 5D);
        listener.onDamageByEntity(atMob);
        assertEquals(5D, atMob.getDamage(), 0.0001, "a PvP-only projectile rune must not boost damage against a mob");
    }

    @Test
    void manuallyAuthoredCorruptedDetonationDamagesNearbyMobsOnKill() {
        PlayerMock killer = MockBukkit.getMock().addPlayer();
        killer.setLocation(new Location(world, 0, 64, 0));
        ItemStack chestplate = new ItemStack(Material.IRON_CHESTPLATE);
        manager.applyEnchant(chestplate, manager.createEnchantItem("echo_test", 1, RuneTier.SIMPLE), 0, 0.0);
        killer.getInventory().setChestplate(chestplate);

        Zombie killed = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        Zombie nearby = world.spawn(new Location(world, 1, 64, 0), Zombie.class);
        double nearbyHealthBefore = nearby.getHealth();

        DamageSource damageSource = DamageSource.builder(DamageType.MOB_ATTACK).build();
        EntityDeathEvent death = new EntityDeathEvent(killed, damageSource, List.of());
        killed.setKiller(killer);
        listener.onDeath(death);

        assertTrue(nearby.getHealth() < nearbyHealthBefore, "the AoE detonation must damage the nearby mob on kill");
    }

    @Test
    void crossbowResolvesAsAValidCompatibleTypeToken() {
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(
                crossbow, manager.createEnchantItem("arrow_edge_crossbow", 1, RuneTier.SIMPLE), 0, 0.0);
        assertEquals(EnchantManager.ApplyResult.SUCCESS, outcome.result(),
                "CROSSBOW must resolve as a valid compatible-types token, not fall through to REJECT_INCOMPATIBLE");
    }

    private static final String RUNES_YML = """
            lucky-gem:
              material: EMERALD
              glow: false

            runes:
              simple:
                material: LIGHT_GRAY_CANDLE
                glow: false
                shop-price: 100.0
                currency: MONEY
                enchants:
                  guard_ward:
                    display-name: "Guard Ward"
                    compatible-types: [CHESTPLATE]
                    effect: DAMAGE_REDUCTION
                    damage-source: PLAYER
                    tags: [DEFENSIVE, PLAYER, PASSIVE, EQUIPMENT]
                    levels:
                      1: { material: IRON_CHESTPLATE, proc-chance: 100, success-rate: 100, ability-value: 50, weight: 100 }
                  arrow_edge:
                    display-name: "Arrow Edge"
                    compatible-types: [BOW]
                    effect: PROJECTILE_DAMAGE
                    target-filter: PLAYER
                    tags: [COMBAT, OFFENSIVE, PLAYER, PROJECTILE, PASSIVE]
                    levels:
                      1: { material: BOW, proc-chance: 100, success-rate: 100, ability-value: 100, weight: 100 }
                  arrow_edge_crossbow:
                    display-name: "Arrow Edge (Crossbow)"
                    compatible-types: [CROSSBOW]
                    effect: PROJECTILE_DAMAGE
                    target-filter: PLAYER
                    tags: [COMBAT, OFFENSIVE, PLAYER, PROJECTILE, PASSIVE]
                    levels:
                      1: { material: CROSSBOW, proc-chance: 100, success-rate: 100, ability-value: 100, weight: 100 }
                  echo_test:
                    display-name: "Echo Test"
                    compatible-types: [CHESTPLATE]
                    effect: CORRUPTED_DETONATION
                    tags: [AOE, OFFENSIVE, MOB, PASSIVE, EQUIPMENT]
                    levels:
                      1: { material: MAGMA_CREAM, proc-chance: 100, success-rate: 100, ability-value: 50, weight: 100, effect-settings: { radius: 5.0 } }
            """;
}
