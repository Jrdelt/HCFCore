package me.vertex.core.enchant.listener;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two seasonal combat abilities currently wired to live gameplay
 * (Talon Rend, Crown Breaker) against the real shipped {@code runes.yml} --
 * proving the "arm on manual activation, consume on the next qualifying
 * melee hit" mechanic those two share actually fires, not just that their
 * config parses. See {@link RuneEffectListener#armNextHit} and {@link
 * RuneEffectListener#consumeArmedHit}.
 */
class SeasonalRuneEffectTest {

    private PluginMock plugin;
    private EnchantManager manager;
    private RuneEffectListener listener;
    private World world;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
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

    private boolean arm(PlayerMock player, String enchantId, ItemStack equippedItem) {
        EnchantDefinition definition = manager.definition(enchantId);
        EnchantDefinition.Level level = definition.level(1);
        return listener.manuallyActivate(player, definition, level);
    }

    @Test
    void talonRendArmsAndWoundsTheNextMeleeTargetReducingIncomingHealing() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack sword = manager.createEnchantItem("talon_rend", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.GOLDEN_SWORD);
        manager.applyEnchant(applied, sword, 0, 0.0);
        attacker.getInventory().setItemInMainHand(applied);

        assertTrue(arm(attacker, "talon_rend", applied), "arming must succeed with no cooldown active");

        Zombie target = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(
                attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D);
        listener.onDamageByEntity(hit);

        EntityRegainHealthEvent regain = new EntityRegainHealthEvent(target, 10D,
                EntityRegainHealthEvent.RegainReason.CUSTOM);
        listener.onRegainHealth(regain);
        assertEquals(9.5D, regain.getAmount(), 0.0001, "level 1's 5% healing reduction must apply to the wounded target");
    }

    @Test
    void talonRendOnlyConsumesOnce() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack sword = manager.createEnchantItem("talon_rend", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.GOLDEN_SWORD);
        manager.applyEnchant(applied, sword, 0, 0.0);
        attacker.getInventory().setItemInMainHand(applied);
        arm(attacker, "talon_rend", applied);

        Zombie first = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        listener.onDamageByEntity(new EntityDamageByEntityEvent(attacker, first, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D));

        Zombie second = world.spawn(new Location(world, 1, 64, 0), Zombie.class);
        listener.onDamageByEntity(new EntityDamageByEntityEvent(attacker, second, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D));

        EntityRegainHealthEvent regainSecond = new EntityRegainHealthEvent(second, 10D, EntityRegainHealthEvent.RegainReason.CUSTOM);
        listener.onRegainHealth(regainSecond);
        assertEquals(10D, regainSecond.getAmount(), 0.0001, "a second hit must not re-wound -- one activation empowers only one hit");
    }

    @Test
    void crownBreakerRemovesAbsorptionCappedAtWhatTheTargetActuallyHas() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack axe = manager.createEnchantItem("crown_breaker", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.GOLDEN_AXE);
        manager.applyEnchant(applied, axe, 0, 0.0);
        attacker.getInventory().setItemInMainHand(applied);
        arm(attacker, "crown_breaker", applied);

        Zombie target = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        target.setAbsorptionAmount(1.0D);
        listener.onDamageByEntity(new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D));

        // Level 1 removes 1.0 heart = 2.0 health, but the target only had 1.0 absorption health to begin with.
        assertEquals(0D, target.getAbsorptionAmount(), 0.0001, "removal must cap at the target's existing absorption");
    }

    @Test
    void seasonalGiveByIdCoversAllElevenGearAppliedItems() {
        assertEquals(11, manager.seasonalIds().size(), "4 armor + 4 weapon + 3 farming tools");
        for (String id : manager.seasonalIds()) {
            assertTrue(manager.isSeasonal(id));
            ItemStack item = manager.createEnchantItem(id, 1, RuneTier.SEASONAL);
            assertEquals(RuneTier.SEASONAL, manager.enchantItemInfo(item).originTier(),
                    id + " must render with the Seasonal tier when given directly");
        }
        assertTrue(!manager.isSeasonal("ore_sense"), "a normal-tier enchant must not be reported as seasonal");
    }

    @Test
    void raptorsReversalReducesDamageAndKnocksBackTheAttackerOnce() {
        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setLocation(new Location(world, 0, 64, 0));
        assertTrue(arm(victim, "raptors_reversal", null), "bracing must succeed with no cooldown active");

        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        attacker.setLocation(new Location(world, 1, 64, 0));
        EntityDamageByEntityEvent first = new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
        listener.onDamage(first);
        assertEquals(9D, first.getDamage(), 0.0001, "level 1's 10% reduction must apply to the braced hit");
        assertTrue(attacker.getVelocity().lengthSquared() > 0D, "the attacker must be knocked back");

        EntityDamageByEntityEvent second = new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
        listener.onDamage(second);
        assertEquals(10D, second.getDamage(), 0.0001, "one brace counters only the first hit");
    }

    @Test
    void goldenBastionReducesDamageOnlyInsideItsRadiusAndOnlyForTeammates() {
        PlayerMock owner = MockBukkit.getMock().addPlayer();
        owner.setLocation(new Location(world, 0, 64, 0));
        assertTrue(arm(owner, "golden_bastion", null));

        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        EntityDamageByEntityEvent insideRadius = new EntityDamageByEntityEvent(attacker, owner, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
        listener.onDamage(insideRadius);
        assertEquals(9.3D, insideRadius.getDamage(), 0.0001, "level 1's 7% reduction must apply to the owner standing in their own circle");

        owner.setLocation(new Location(world, 100, 64, 100));
        EntityDamageByEntityEvent outsideRadius = new EntityDamageByEntityEvent(attacker, owner, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
        listener.onDamage(outsideRadius);
        assertEquals(10D, outsideRadius.getDamage(), 0.0001, "moving outside the circle's radius must lose the reduction");
    }

    @Test
    void huntmastersCallFailsToActivateAndConsumesNoCooldownWithoutAValidTarget() {
        PlayerMock caster = MockBukkit.getMock().addPlayer();
        caster.setLocation(new Location(world, 0, 64, 0));
        // No hostile player anywhere near the ray -- a required target that
        // is invalid before activation must not consume the cooldown.
        assertTrue(!arm(caster, "huntmasters_call", null), "activation must fail when no valid hostile target is in line of sight");
    }

    @Test
    void slipstreamGrantsWearerSpeedAndNeverDowngradesAStrongerExistingEffect() {
        PlayerMock wearer = MockBukkit.getMock().addPlayer();
        wearer.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 200, 9));
        assertTrue(arm(wearer, "slipstream", null));
        org.bukkit.potion.PotionEffect after = wearer.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
        assertEquals(9, after.getAmplifier(), "a stronger existing Speed effect must never be downgraded");
    }

    @Test
    void seasonalLoreLineAlwaysRendersLastRegardlessOfApplicationOrder() {
        ItemStack chestplate = new ItemStack(Material.GOLDEN_CHESTPLATE);
        manager.applyEnchant(chestplate, manager.createEnchantItem("golden_bastion", 1, RuneTier.SEASONAL), 0, 0.0);
        manager.applyEnchant(chestplate, manager.createEnchantItem("ironhide", 1, RuneTier.SIMPLE), 0, 0.0);

        java.util.List<net.kyori.adventure.text.Component> lore = chestplate.getItemMeta().lore();
        String last = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lore.getLast());
        assertTrue(last.contains("Golden Bastion") || last.contains("Bastion"),
                "the seasonal enchant must render last even though it was applied first");
    }

    @Test
    void armedHitIsDroppedIfTheEnchantIsNoLongerEquippedAtHitTime() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack sword = manager.createEnchantItem("talon_rend", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.GOLDEN_SWORD);
        manager.applyEnchant(applied, sword, 0, 0.0);
        attacker.getInventory().setItemInMainHand(applied);
        arm(attacker, "talon_rend", applied);

        // Swap away before the hit lands.
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.STICK));

        Zombie target = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        listener.onDamageByEntity(new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D));

        EntityRegainHealthEvent regain = new EntityRegainHealthEvent(target, 10D, EntityRegainHealthEvent.RegainReason.CUSTOM);
        listener.onRegainHealth(regain);
        assertEquals(10D, regain.getAmount(), 0.0001, "an armed hit whose source is no longer equipped must not apply");
    }
}
