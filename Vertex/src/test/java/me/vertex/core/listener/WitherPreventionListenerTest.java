package me.vertex.core.listener;

import org.bukkit.Location;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Withers must never come into existence, via any spawn path -- see
 * {@link WitherPreventionListener}'s class doc for why a single
 * entity-type check on {@link CreatureSpawnEvent} covers every one of
 * them, including the vanilla soul-sand-and-skulls construction
 * ({@code SpawnReason.BUILD_WITHER}).
 */
class WitherPreventionListenerTest {

    private ServerMock server;
    private WorldMock world;
    private final WitherPreventionListener listener = new WitherPreventionListener();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("wither-test-world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void cancelsAWitherBuiltFromSoulSandAndSkulls() {
        Wither wither = world.spawn(new Location(world, 0, 64, 0), Wither.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(wither, CreatureSpawnEvent.SpawnReason.BUILD_WITHER);

        listener.onSpawn(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void cancelsAWitherSpawnedFromAnEggOrSpawner() {
        Wither wither = world.spawn(new Location(world, 0, 64, 0), Wither.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(wither, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

        listener.onSpawn(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void leavesOtherMobSpawnsAlone() {
        Zombie zombie = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onSpawn(event);

        assertFalse(event.isCancelled());
    }
}
