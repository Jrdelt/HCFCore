package me.vertex.core.spawner;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpawnerDataTest {

    @Test
    void removesYoungestSpawnerFirst() {
        SpawnerData data = new SpawnerData(EntityType.CREEPER,
                List.of(100L, 400L, 200L, 300L), "Vertex");

        assertEquals(2, data.removeYoungest(2));
        assertEquals(List.of(100L, 200L), data.placedAtMillis());
        assertEquals(2, data.stackSize());
    }

    @Test
    void freshDepositsReceiveTheirOwnTimestamps() {
        SpawnerData data = new SpawnerData(EntityType.CREEPER, List.of(100L), "Vertex");

        data.addFresh(2);

        assertEquals(3, data.stackSize());
        assertEquals(100L, data.placedAtMillis().getFirst());
        assertEquals(2, data.placedAtMillis().stream().filter(age -> age > 100L).count());
    }
}
