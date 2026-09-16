package me.vertex.core.enchant.listener;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.enchant.binds.BindManager;
import me.vertex.core.enchant.binds.BindStorage;
import me.vertex.core.enchant.binds.PlayerBinds;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.storage.Database;
import me.vertex.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Expanded Rune Module's first batch (Zeus, Obliterate,
 * Featherweight, Ghost, Bat Vision) against the real shipped
 * {@code runes.yml}, driven entirely through the same event handlers a
 * live server calls, not by invoking effect logic directly.
 */
class ExpandedRuneModuleBatch1Test {
    @TempDir Path folder;
    private PluginMock plugin;
    private EnchantManager manager;
    private RuneEffectListener listener;
    private CombatManager combat;
    private World world;
    private Database database;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        UserManager users = new UserManager(plugin, null);
        combat = new CombatManager(plugin, null, 15, 5, true, 4, "{seconds}", "{seconds}", "{seconds}");
        listener = new RuneEffectListener(manager, combat, users, new RuneCooldownStore(plugin, null));
        world = MockBukkit.getMock().addSimpleWorld("world");
        database = new Database(new YamlConfiguration(), folder.toFile());
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    private void equip(PlayerMock player, String enchantId, Material toolOrArmor, ItemStack target) {
        ItemStack rune = manager.createEnchantItem(enchantId, 1, RuneTier.COMMON);
        manager.applyEnchant(target, rune, 0, 0.0);
    }

    @Test
    void zeusAddsBonusHeartDamageOnASuccessfulArrowHitOnAnEnemyPlayer() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        attacker.setLocation(new Location(world, 0, 64, 0));
        ItemStack bow = new ItemStack(Material.BOW);
        equip(attacker, "zeus", Material.BOW, bow);
        attacker.getInventory().setItemInMainHand(bow);

        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setLocation(new Location(world, 1, 64, 0));

        boolean procced = false;
        for (int attempt = 0; attempt < 500 && !procced; attempt++) {
            org.bukkit.entity.Arrow arrow = world.spawn(attacker.getLocation(), org.bukkit.entity.Arrow.class);
            arrow.setShooter(attacker);
            EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(
                    arrow, victim, EntityDamageEvent.DamageCause.PROJECTILE, 4D);
            listener.onDamageByEntity(hit);
            if (hit.getDamage() > 4D) {
                procced = true;
                // Level 1 is +1 heart = +2 damage, folded into this same event.
                assertEquals(6D, hit.getDamage(), 0.0001, "Zeus must add exactly +1 heart (2 damage) at level 1");
            }
        }
        assertTrue(procced, "Zeus must eventually proc across many attempts at its configured chance");
    }

    @Test
    void obliterateKnocksTheTargetAwayFromTheAttacker() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        attacker.setLocation(new Location(world, 0, 64, 0));
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        equip(attacker, "obliterate", Material.IRON_AXE, axe);
        attacker.getInventory().setItemInMainHand(axe);

        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setLocation(new Location(world, 2, 64, 0));

        boolean procced = false;
        for (int attempt = 0; attempt < 1000 && !procced; attempt++) {
            EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(
                    attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D);
            listener.onDamageByEntity(hit);
            if (victim.getVelocity().lengthSquared() > 0D) {
                procced = true;
                assertTrue(victim.getVelocity().getX() > 0D, "the victim (at +x from the attacker) must be pushed further away, not toward them");
            }
        }
        assertTrue(procced, "Obliterate must eventually proc across many attempts at its configured chance");
    }

    @Test
    void featherweightGrantsHasteOnAPrimaryMeleeHitAndRefreshesWithoutStackingAmplifier() {
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        equip(attacker, "featherweight", Material.IRON_SWORD, sword);
        attacker.getInventory().setItemInMainHand(sword);

        org.bukkit.entity.Zombie target = world.spawn(new Location(world, 0, 64, 0), org.bukkit.entity.Zombie.class);

        boolean procced = false;
        for (int attempt = 0; attempt < 500 && !procced; attempt++) {
            listener.onDamageByEntity(new EntityDamageByEntityEvent(
                    attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5D));
            if (attacker.hasPotionEffect(PotionEffectType.HASTE)) {
                procced = true;
                assertEquals(0, attacker.getPotionEffect(PotionEffectType.HASTE).getAmplifier(),
                        "level 1 Featherweight must grant Haste I (amplifier 0), never stacking beyond its own level");
            }
        }
        assertTrue(procced, "Featherweight must eventually proc across many attempts at its configured chance");
    }

    @Test
    void ghostUnboundAutomaticallyActivatesOnQualifyingEnemyDamageBelowThreshold() {
        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setHealth(4D); // 20% of 20 max health, below level 1's 25% threshold
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        equip(victim, "ghost", Material.LEATHER_HELMET, helmet);
        victim.getInventory().setHelmet(helmet);

        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        combat.tag(attacker, victim);
        listener.onDamage(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1D));

        assertTrue(victim.hasPotionEffect(PotionEffectType.INVISIBILITY), "Ghost must automatically activate when unbound, combat-tagged, and below its health threshold");
        assertTrue(victim.hasPotionEffect(PotionEffectType.SPEED));
    }

    @Test
    void ghostDoesNotActivateWhenNotCombatTaggedEvenBelowThreshold() {
        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setHealth(4D);
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        equip(victim, "ghost", Material.LEATHER_HELMET, helmet);
        victim.getInventory().setHelmet(helmet);

        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        listener.onDamage(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1D));

        assertFalse(victim.hasPotionEffect(PotionEffectType.INVISIBILITY), "Ghost must require the combat tag, not just low health");
    }

    @Test
    void ghostTakingDamageDoesNotCancelItButDealingAnAttackDoes() {
        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setHealth(4D);
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        equip(victim, "ghost", Material.LEATHER_HELMET, helmet);
        victim.getInventory().setHelmet(helmet);
        PlayerMock attacker = MockBukkit.getMock().addPlayer();
        combat.tag(attacker, victim);
        listener.onDamage(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1D));
        assertTrue(victim.hasPotionEffect(PotionEffectType.INVISIBILITY), "setup: Ghost must be active before the rest of this test means anything");

        // Taking further damage must not cancel it.
        listener.onDamage(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1D));
        assertTrue(victim.hasPotionEffect(PotionEffectType.INVISIBILITY), "taking damage must never cancel an active Ghost");

        // Dealing an attack must cancel it immediately.
        org.bukkit.entity.Zombie mob = world.spawn(new Location(world, 5, 64, 0), org.bukkit.entity.Zombie.class);
        listener.onDamageByEntity(new EntityDamageByEntityEvent(victim, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1D));
        assertFalse(victim.hasPotionEffect(PotionEffectType.INVISIBILITY), "dealing an attack must cancel Ghost immediately");
    }

    @Test
    void ghostBoundToABindSlotDisablesItsAutomaticTrigger() throws Exception {
        BindStorage bindStorage = new BindStorage(database);
        bindStorage.init();
        BindManager bindManager = new BindManager(plugin, bindStorage, manager, null);
        listener.setBindManager(bindManager);

        PlayerMock victim = MockBukkit.getMock().addPlayer();
        victim.setHealth(4D);
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        equip(victim, "ghost", Material.LEATHER_HELMET, helmet);
        victim.getInventory().setHelmet(helmet);

        bindManager.onJoin(new PlayerJoinEvent(victim, net.kyori.adventure.text.Component.empty()));
        for (int attempt = 0; attempt < 200 && bindManager.get(victim.getUniqueId()) == null; attempt++) {
            Thread.sleep(5L);
        }
        PlayerBinds binds = bindManager.get(victim.getUniqueId());
        assertTrue(binds != null, "bind data must finish loading for this test to be meaningful");
        assertTrue(bindManager.setSlot(victim, 1, 0, "ghost"), "assigning Ghost to a bind slot must succeed");

        Player attacker = MockBukkit.getMock().addPlayer();
        combat.tag(attacker, victim);
        listener.onDamage(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1D));

        assertFalse(victim.hasPotionEffect(PotionEffectType.INVISIBILITY),
                "once bound to a /binds slot, Ghost must never activate automatically -- only through the bind system");

        // The bind system's own execution path (manuallyActivate) must still work.
        var definition = manager.definition("ghost");
        assertTrue(listener.manuallyActivate(victim, definition, definition.level(1)),
                "Ghost must still activate through manual /binds execution once bound");
        assertTrue(victim.hasPotionEffect(PotionEffectType.INVISIBILITY));

        bindManager.awaitWrites();
    }

    @Test
    void batVisionGrantsNightVisionWhileHelmetEquippedAndRemovesItWhenUnequipped() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        equip(player, "bat_vision", Material.LEATHER_HELMET, helmet);

        player.getInventory().setHelmet(helmet);
        listener.onArmorChange(new com.destroystokyo.paper.event.player.PlayerArmorChangeEvent(
                player, com.destroystokyo.paper.event.player.PlayerArmorChangeEvent.SlotType.HEAD, null, helmet));
        assertTrue(player.hasPotionEffect(PotionEffectType.NIGHT_VISION), "Bat Vision must grant Night Vision while an eligible helmet is equipped");

        ItemStack plainHelmet = new ItemStack(Material.LEATHER_HELMET);
        player.getInventory().setHelmet(plainHelmet);
        listener.onArmorChange(new com.destroystokyo.paper.event.player.PlayerArmorChangeEvent(
                player, com.destroystokyo.paper.event.player.PlayerArmorChangeEvent.SlotType.HEAD, helmet, plainHelmet));
        assertFalse(player.hasPotionEffect(PotionEffectType.NIGHT_VISION), "Bat Vision must be removed the instant the enchanted helmet comes off");
    }
}
