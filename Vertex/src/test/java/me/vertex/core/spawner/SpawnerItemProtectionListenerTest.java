package me.vertex.core.spawner;

import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerItemProtectionListenerTest {

    @Test
    void protectsDroppedSpawnersFromLavaAndFire() {
        ItemStack spawner = new ItemStack(Material.SPAWNER);

        assertTrue(SpawnerItemProtectionListener.protects(spawner, EntityDamageEvent.DamageCause.LAVA));
        assertTrue(SpawnerItemProtectionListener.protects(spawner, EntityDamageEvent.DamageCause.FIRE));
        assertTrue(SpawnerItemProtectionListener.protects(spawner, EntityDamageEvent.DamageCause.FIRE_TICK));
    }

    @Test
    void doesNotProtectOrdinaryItemsOrUnrelatedDamage() {
        assertFalse(SpawnerItemProtectionListener.protects(new ItemStack(Material.DIAMOND), EntityDamageEvent.DamageCause.LAVA));
        assertFalse(SpawnerItemProtectionListener.protects(new ItemStack(Material.SPAWNER), EntityDamageEvent.DamageCause.VOID));
    }
}
