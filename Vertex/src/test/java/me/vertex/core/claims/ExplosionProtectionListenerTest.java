package me.vertex.core.claims;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ExplosionProtectionListener} without touching FactionsUUID's
 * live {@code Board} singleton -- the Base Claim query is a plain
 * {@code Predicate<Location>} here (see the class doc for why), standing in
 * for what {@code BaseClaimManager.isBaseClaim} reports in production.
 */
class ExplosionProtectionListenerTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("explosion-test-world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block blockAt(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    @Test
    void entityExplodeStripsOnlyBlocksInsideABaseClaim() {
        // Base Claim blocks sit at x < 10; everything else (Raid Claim / wilderness) is left alone.
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> loc.getBlockX() < 10);

        Block baseClaimBlock = blockAt(0, 64, 0);
        Block raidClaimBlock = blockAt(20, 64, 0);
        List<Block> blocks = new ArrayList<>(List.of(baseClaimBlock, raidClaimBlock));

        TNTPrimed tnt = world.spawn(new Location(world, 5, 64, 0), TNTPrimed.class);
        EntityExplodeEvent event = new EntityExplodeEvent(tnt, tnt.getLocation(), blocks, 4F,
                org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertFalse(event.blockList().contains(baseClaimBlock), "Base Claim block damage must be suppressed");
        assertTrue(event.blockList().contains(raidClaimBlock), "Raid Claim / wilderness block damage must be untouched");
    }

    @Test
    void entityExplodeLeavesEveryBlockAloneOutsideABaseClaim() {
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> false);

        Block first = blockAt(1, 64, 1);
        Block second = blockAt(2, 64, 2);
        List<Block> blocks = new ArrayList<>(List.of(first, second));

        TNTPrimed tnt = world.spawn(new Location(world, 1, 64, 1), TNTPrimed.class);
        EntityExplodeEvent event = new EntityExplodeEvent(tnt, tnt.getLocation(), blocks, 4F,
                org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertEquals(2, event.blockList().size(), "wilderness/Raid Claim TNT block damage must not be touched");
    }

    @Test
    void blockExplodeStripsBlocksInsideABaseClaimOnly() {
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> loc.getBlockX() == 7);

        Block source = blockAt(7, 64, 0);
        Block baseClaimBlock = blockAt(7, 64, 0);
        Block other = blockAt(8, 64, 0);
        List<Block> blocks = new ArrayList<>(List.of(baseClaimBlock, other));

        BlockExplodeEvent event = new BlockExplodeEvent(source, source.getState(), blocks, 4F,
                org.bukkit.ExplosionResult.DESTROY);

        listener.onBlockExplode(event);

        assertFalse(event.blockList().contains(baseClaimBlock));
        assertTrue(event.blockList().contains(other));
    }

    @Test
    void explosionDamageIsCancelledForPlayersEverywhere() {
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> false);
        PlayerMock player = server.addPlayer();

        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 6D);
        listener.onExplosionDamage(event);

        assertTrue(event.isCancelled(), "TNT must never damage players, even outside a Base Claim");
    }

    @Test
    void explosionDamageIsCancelledForMobsEverywhere() {
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> true);
        Zombie zombie = world.spawn(new Location(world, 0, 64, 0), Zombie.class);

        EntityDamageEvent event = new EntityDamageEvent(zombie,
                EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, 6D);
        listener.onExplosionDamage(event);

        assertTrue(event.isCancelled(), "TNT must never damage mobs, even inside a Base Claim");
    }

    @Test
    void nonExplosionDamageIsLeftAlone() {
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> true);
        PlayerMock player = server.addPlayer();

        EntityDamageEvent event = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 6D);
        listener.onExplosionDamage(event);

        assertFalse(event.isCancelled(), "only explosion-caused damage should be touched by this listener");
    }

    @Test
    void nonLivingEntityDamageIsLeftAlone() {
        ExplosionProtectionListener listener = new ExplosionProtectionListener(loc -> true);
        // An ender crystal is a valid explosion-damage target but not a LivingEntity;
        // out of scope for the "players and mobs" rule.
        org.bukkit.entity.EnderCrystal crystal =
                world.spawn(new Location(world, 0, 64, 0), org.bukkit.entity.EnderCrystal.class);

        EntityDamageEvent event = new EntityDamageEvent(crystal,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 6D);
        listener.onExplosionDamage(event);

        assertFalse(event.isCancelled());
    }
}
