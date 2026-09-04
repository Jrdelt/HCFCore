package me.hcfcore.core.factions;

import me.hcfcore.core.staff.StaffManager;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Set;

/**
 * Blocks all block breaking in claims named in
 * {@code factions.no-build-claim-names} (default: warzone, safezone).
 * Cancels {@link BlockDamageEvent} -- the event fired the moment a player
 * starts hitting a block, before any break progress exists -- rather than
 * only {@link BlockBreakEvent}, so there's no partially-mined block state
 * for a desync/packet exploit to glitch through. BlockBreakEvent is also
 * cancelled as defense in depth for any path that skips damage (e.g. an
 * instant-break tool).
 *
 * <p>Matches by claim name ({@link FactionsHook#isDisabledClaim}), not the
 * system WarZone/SafeZone API flags -- a claim can be named "warzone" or
 * "safezone" without those formal flags ever being set (e.g. a plain
 * {@code /f create warzone} rather than {@code /f warzone}), which is why
 * this previously silently failed to protect some servers' actual zones.
 *
 * <p>Also blocks pistons from pushing/pulling blocks into or out of the
 * zone -- otherwise a piston sitting just outside the boundary is a way to
 * displace protected terrain without ever breaking a block directly.
 */
public final class ZoneBlockProtectionListener implements Listener {

    private final Plugin plugin;
    private final StaffManager staffManager;

    public ZoneBlockProtectionListener(Plugin plugin, StaffManager staffManager) {
        this.plugin = plugin;
        this.staffManager = staffManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * No player is involved in a piston firing (it's redstone-triggered),
     * so there's nothing to check {@code isStaffBuild} against -- pistons
     * are blocked outright rather than bypassed for staff.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (touchesZone(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (touchesZone(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /**
     * True if the piston itself, or any block it's about to move -- at
     * either its current position or where it's about to end up -- is in
     * a protected claim.
     */
    private boolean touchesZone(Block piston, List<Block> moved, BlockFace direction) {
        Set<String> names = protectedClaimNames();
        if (FactionsHook.isDisabledClaim(piston.getLocation(), names)) {
            return true;
        }
        for (Block block : moved) {
            if (FactionsHook.isDisabledClaim(block.getLocation(), names)
                    || FactionsHook.isDisabledClaim(block.getRelative(direction).getLocation(), names)) {
                return true;
            }
        }
        return false;
    }

    private boolean blocked(Player player) {
        return !staffManager.isStaffBuild(player.getUniqueId())
                && FactionsHook.isDisabledClaim(player.getLocation(), protectedClaimNames());
    }

    private Set<String> protectedClaimNames() {
        return Set.copyOf(plugin.getConfig().getStringList("factions.no-build-claim-names"));
    }
}
