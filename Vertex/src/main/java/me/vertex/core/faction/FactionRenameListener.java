package me.vertex.core.faction;

import me.vertex.core.collector.ChunkCollectorManager;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.spawner.SpawnerManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;

/**
 * Spawners and Chunk Collectors record their owning faction as a plain tag
 * string (see the comment on {@code SpawnerData.ownerFactionTag} /
 * {@code ChunkCollectorData}), not a stable faction id.
 * Renaming a faction would otherwise silently orphan every managed block it
 * placed: overclaim/disband handling that matches by tag would no longer
 * recognize them as belonging to their own faction. This retags every
 * managed block the faction owns to its new tag in the same tick as the
 * rename, so ownership survives it.
 *
 * <p>The native lifecycle event carries both names, after its durable
 * faction record has been updated.
 */
public final class FactionRenameListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final ChunkCollectorManager collectorManager;

    public FactionRenameListener(SpawnerManager spawnerManager, ChunkCollectorManager collectorManager) {
        this.spawnerManager = spawnerManager;
        this.collectorManager = collectorManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFactionRename(FactionLifecycleEvent event) {
        if (event.action() != FactionLifecycleEvent.Action.RENAME) return;
        String oldTag = event.previousTag();
        String newTag = event.faction().tag();
        if (oldTag == null || newTag == null || oldTag.equalsIgnoreCase(newTag)) {
            return;
        }
        for (Map.Entry<Location, ?> entry : spawnerManager.getSpawnersOwnedBy(oldTag)) {
            spawnerManager.transferOwnership(entry.getKey(), newTag);
        }
        for (Map.Entry<Location, ?> entry : collectorManager.getCollectorsOwnedBy(oldTag)) {
            collectorManager.transferFactionOwnership(entry.getKey(), newTag);
        }
    }
}
