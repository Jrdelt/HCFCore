package me.vertex.core.spawner;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps dropped spawner items recoverable when a player breaks or withdraws
 * one near lava. The protection applies to every spawner item, including a
 * vanilla-configured one, because it is still a valuable spawner while it is
 * an item entity and has no safe way to distinguish its provenance later.
 */
public final class SpawnerItemProtectionListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerItemHeatDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item) || !protects(item.getItemStack(), event.getCause())) {
            return;
        }
        event.setCancelled(true);
    }

    /** Package-visible for a server-free regression test. */
    static boolean protects(ItemStack item, EntityDamageEvent.DamageCause cause) {
        if (item == null || item.getType() != Material.SPAWNER) {
            return false;
        }
        return switch (cause) {
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR -> true;
            default -> false;
        };
    }
}
