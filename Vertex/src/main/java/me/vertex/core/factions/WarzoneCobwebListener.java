package me.vertex.core.factions;

import me.vertex.core.capture.CaptureEventManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/**
 * The one carved-out exception to the Warzone system claim's normal
 * unbuildable rule: a cobweb, specifically. {@link FactionProtectionListener}
 * already denies every {@link BlockPlaceEvent} inside a system claim at
 * {@code HIGH} priority (no player is ever a "member" of it) -- this
 * listener runs at {@code HIGHEST} with {@code ignoreCancelled = false} so
 * it can see that denial and selectively un-cancel it, the same
 * see-then-override idiom {@code ZoneListener#onZoneMobDamage} already uses
 * for the identical reason (never guess whether the block came in already
 * cancelled for some *other* legitimate reason -- inspect and override only
 * the one specific case this exists for).
 *
 * <p>The exception itself stays off while a KOTH's cuboid overlaps the
 * placement location -- Warzone and a KOTH area can coexist, and the KOTH
 * side must stay exactly as unbuildable as it is today regardless of this
 * class. A cobweb that does get placed is never persisted: it reverts to
 * air on its own after {@code factions.system-claims.warzone-cobweb-expiry-seconds}
 * (default 30), via a one-shot {@link Bukkit#getScheduler()} task, not a
 * durable timer -- a mid-window server restart simply leaves it in place.
 */
public final class WarzoneCobwebListener implements Listener {

    private final Plugin plugin;
    private final FactionService factions;
    private final CaptureEventManager captureEvents;

    public WarzoneCobwebListener(Plugin plugin, FactionService factions, CaptureEventManager captureEvents) {
        this.plugin = plugin;
        this.factions = factions;
        this.captureEvents = captureEvents;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.COBWEB || !event.isCancelled()) {
            return;
        }
        Location location = block.getLocation();
        if (!inWarzone(location) || captureEvents.insideActiveKoth(location)) {
            return;
        }
        event.setCancelled(false);
        long expiryTicks = Math.round(expirySeconds() * 20D);
        if (expiryTicks <= 0L) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() == Material.COBWEB) {
                block.setType(Material.AIR);
            }
        }, expiryTicks);
    }

    private boolean inWarzone(Location location) {
        String tag = factions.factionTagAt(location);
        return tag != null && tag.equalsIgnoreCase(factions.systemTag("warzone"));
    }

    private double expirySeconds() {
        return Math.max(0D, plugin.getConfig().getDouble("factions.system-claims.warzone-cobweb-expiry-seconds", 30D));
    }
}
