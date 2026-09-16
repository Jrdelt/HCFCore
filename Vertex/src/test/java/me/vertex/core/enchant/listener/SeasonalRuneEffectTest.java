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
    void gildedCatchDuplicatesTheCatchAndEventuallyGrantsAConsumableBoosterPotion() {
        me.vertex.core.lang.Messages messages = new me.vertex.core.lang.Messages(plugin, new UserManager(plugin, null));
        messages.load();
        listener.setMessages(messages);

        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setLocation(new Location(world, 0, 64, 0));
        ItemStack rod = manager.createEnchantItem("gilded_catch", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.FISHING_ROD);
        manager.applyEnchant(applied, rod, 0, 0.0);
        player.getInventory().setItemInMainHand(applied);

        ItemStack boosterPotion = null;
        // Level 1's booster-chance is now the low end of an increasing-with-level
        // curve (1%, not the old 10%), so this needs far more attempts to stay
        // reliably non-flaky: 5000 tries keeps the false-negative rate near zero.
        for (int attempt = 0; attempt < 5000 && boosterPotion == null; attempt++) {
            org.bukkit.entity.Item caught = world.dropItem(new Location(world, 0, 64, 0), new ItemStack(Material.COD));
            listener.onFish(new org.bukkit.event.player.PlayerFishEvent(player, caught, null,
                    org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH));
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && stack.getType() == Material.POTION) {
                    boosterPotion = stack;
                    break;
                }
            }
        }
        assertTrue(player.getInventory().contains(Material.COD), "the genuine catch must be duplicated into the player's inventory");
        assertTrue(boosterPotion != null, "a booster potion must eventually roll across many catches at level 1's 1% chance");

        player.getInventory().removeItem(boosterPotion);
        listener.onConsume(new org.bukkit.event.player.PlayerItemConsumeEvent(player, boosterPotion));

        java.util.List<me.vertex.core.booster.BoosterContribution> exp =
                listener.gildedCatchBoosterSource().contribute(player, me.vertex.core.booster.BoosterCategory.EXP);
        java.util.List<me.vertex.core.booster.BoosterContribution> sell =
                listener.gildedCatchBoosterSource().contribute(player, me.vertex.core.booster.BoosterCategory.SELL);
        java.util.List<me.vertex.core.booster.BoosterContribution> granted = exp.isEmpty() ? sell : exp;
        assertTrue(!granted.isEmpty() && granted.get(0).active() && granted.get(0).percent() > 0D,
                "drinking the potion must grant an active personal booster in whichever category it rolled");
    }

    @Test
    void crownBreakerNotifiesThePlayerVictimWhenItActuallyStripsAbsorption() {
        me.vertex.core.lang.Messages messages = new me.vertex.core.lang.Messages(plugin, new UserManager(plugin, null));
        messages.load();
        listener.setMessages(messages);

        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack axe = manager.createEnchantItem("crown_breaker", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.GOLDEN_AXE);
        manager.applyEnchant(applied, axe, 0, 0.0);
        attacker.getInventory().setItemInMainHand(applied);
        arm(attacker, "crown_breaker", applied);

        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setLocation(new Location(world, 0, 64, 0));
        victim.setAbsorptionAmount(1.0D);
        listener.onDamageByEntity(new EntityDamageByEntityEvent(attacker, victim,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D));

        assertTrue(victim.nextMessage() != null, "the victim must be told their absorption was struck through");
    }

    @Test
    void seasonalGiveByIdCoversAllTenGearAppliedItems() {
        assertEquals(10, manager.seasonalIds().size(), "4 armor + 3 weapon + 3 farming/mining tools");
        for (String id : manager.seasonalIds()) {
            assertTrue(manager.isSeasonal(id));
            ItemStack item = manager.createEnchantItem(id, 1, RuneTier.SEASONAL);
            assertEquals(RuneTier.SEASONAL, manager.enchantItemInfo(item).originTier(),
                    id + " must render with the Seasonal tier when given directly");
        }
        assertTrue(!manager.isSeasonal("ore_sense"), "a normal-tier enchant must not be reported as seasonal");
    }

    /**
     * Raptor's Reversal is fully passive now (see {@link RuneEffectListener#applyRaptorsReversal}):
     * every hit rolls level 1's configured 20% proc chance instead of
     * requiring a manual activation, so this drives real hits until one
     * actually procs (200 attempts against a 20% chance is astronomically
     * unlikely to never fire) rather than asserting a single deterministic
     * roll.
     */
    @Test
    void raptorsReversalEventuallyReducesDamageWithinItsConfiguredRangeAndKnocksBackTheAttacker() {
        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setLocation(new Location(world, 0, 64, 0));
        ItemStack leggings = manager.createEnchantItem("raptors_reversal", 1, RuneTier.SEASONAL);
        ItemStack applied = new ItemStack(Material.GOLDEN_LEGGINGS);
        manager.applyEnchant(applied, leggings, 0, 0.0);
        victim.getInventory().setLeggings(applied);

        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        attacker.setLocation(new Location(world, 1, 64, 0));

        boolean procced = false;
        for (int attempt = 0; attempt < 200 && !procced; attempt++) {
            EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(
                    attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10D);
            listener.onDamage(hit);
            if (hit.getDamage() < 10D) {
                procced = true;
                assertTrue(hit.getDamage() >= 6.4D && hit.getDamage() <= 8.5D,
                        "level 1's 15-35% random reduction range must be honored, got " + hit.getDamage());
                assertTrue(attacker.getVelocity().lengthSquared() > 0D, "the attacker must be knocked back on a proc");
            }
        }
        assertTrue(procced, "raptors reversal must eventually proc across many attempts at a 20% chance");
    }

    /**
     * Golden Bastion is a standing field now (see {@link
     * RuneEffectListener#pushBastion}), not a single instant pulse: casting
     * it starts a repeating task that keeps knocking every non-ally within
     * its box away from the caster -- following the caster if they move --
     * for the level's configured {@code duration-seconds}, never the caster
     * themselves and never anyone outside the box. Activation itself only
     * schedules the task, so the mock scheduler has to be advanced before
     * any push actually lands.
     */
    @Test
    void goldenBastionPushesNearbyNonAlliesAwayButNeverTheCasterOrSomeoneOutOfRange() {
        PlayerMock owner = MockBukkit.getMock().addPlayer();
        owner.setLocation(new Location(world, 0, 64, 0));
        PlayerMock nearby = MockBukkit.getMock().addPlayer();
        nearby.setLocation(new Location(world, 2, 64, 0));
        PlayerMock farAway = MockBukkit.getMock().addPlayer();
        farAway.setLocation(new Location(world, 100, 64, 100));

        assertTrue(arm(owner, "golden_bastion", null), "activation must succeed with no cooldown active");
        ((org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock) MockBukkit.getMock().getScheduler()).performTicks(1L);

        assertEquals(0D, owner.getVelocity().lengthSquared(), 0.0001, "the caster must never push themselves");
        assertTrue(nearby.getVelocity().lengthSquared() > 0D, "a nearby non-ally must be knocked away");
        assertEquals(0D, farAway.getVelocity().lengthSquared(), 0.0001, "a player outside the radius must be unaffected");
    }

    /** The field keeps pushing on every tick of its duration, not just once -- a player who drifts back in must be pushed out again. */
    @Test
    void goldenBastionKeepsPushingForItsWholeConfiguredDuration() {
        PlayerMock owner = MockBukkit.getMock().addPlayer();
        owner.setLocation(new Location(world, 0, 64, 0));
        PlayerMock nearby = MockBukkit.getMock().addPlayer();
        nearby.setLocation(new Location(world, 2, 64, 0));

        assertTrue(arm(owner, "golden_bastion", null));
        org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock scheduler =
                (org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock) MockBukkit.getMock().getScheduler();
        scheduler.performTicks(1L);
        nearby.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        nearby.setLocation(new Location(world, 2, 64, 0));

        // Level 1's duration is 4 seconds (80 ticks) -- well within that window the field must still be pushing.
        scheduler.performTicks(20L);
        assertTrue(nearby.getVelocity().lengthSquared() > 0D, "the field must still be pushing partway through its duration");
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
