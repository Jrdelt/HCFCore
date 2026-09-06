package me.vertex.core.faction;

import dev.kitteh.factions.event.FactionRenameEvent;
import me.vertex.core.collector.ChunkCollectorManager;
import me.vertex.core.spawner.SpawnerManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;

/**
 * Spawners and Chunk Collectors record their owning faction as a plain tag
 * string (see the comment on {@code SpawnerData.ownerFactionTag} /
 * {@code ChunkCollectorData}), not FactionsUUID's stable faction id.
 * Renaming a faction would otherwise silently orphan every managed block it
 * placed: overclaim/disband handling that matches by tag would no longer
 * recognize them as belonging to their own faction. This retags every
 * managed block the faction owns to its new tag in the same tick as the
 * rename, so ownership survives it.
 *
 * <p>{@link FactionRenameEvent} is cancellable and fires before
 * FactionsUUID applies the change, so {@code event.getFaction().tag()}
 * still reads the pre-rename tag here regardless of listener priority --
 * only whether the rename actually goes through depends on priority
 * ordering among plugins, which {@code ignoreCancelled} handles.
 */
public final class FactionRenameListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final ChunkCollectorManager collectorManager;

    public FactionRenameListener(SpawnerManager spawnerManager, ChunkCollectorManager collectorManager) {
        this.spawnerManager = spawnerManager;
        this.collectorManager = collectorManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFactionRename(FactionRenameEvent event) {
        String oldTag = event.getFaction().tag();
        String newTag = event.getFactionTag();
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
