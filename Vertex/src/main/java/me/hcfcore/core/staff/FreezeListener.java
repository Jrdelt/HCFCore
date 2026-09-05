package me.hcfcore.core.staff;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.time.Duration;

/**
 * Locks a frozen player in place -- no movement, block break/place,
 * interaction, damage dealt or taken, item drops, inventory clicks,
 * commands, projectile launches (pearls, arrows, snowballs, eggs, fishing
 * hooks), eating/drinking, bucket use, item pickup, or hand-swapping --
 * and auto-bans anyone who disconnects while frozen, since that's the
 * classic way to dodge a staff investigation.
 *
 * <p>Projectiles get their own handler on top of {@code PlayerInteractEvent}
 * cancellation: cancelling the interact event alone doesn't reliably stop
 * every launch path (this codebase's own pearl-cooldown/pearl-stun code
 * already double-covers pearls the same way), so anything that can leave
 * the frozen player's hand is caught at the projectile itself too.
 */
public final class FreezeListener implements Listener {

    private static final Duration LEAVE_BAN_DURATION = Duration.ofHours(3);

    private final StaffManager staffManager;
    private final Messages messages;

    public FreezeListener(StaffManager staffManager, Messages messages) {
        this.staffManager = staffManager;
        this.messages = messages;
    }

    /**
     * Runs at LOWEST so it reads frozen state before {@link VanishListener}
     * (default priority) calls {@code staffManager.forget()} on the same
     * event and clears it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!staffManager.isFrozen(player.getUniqueId())) {
            return;
        }
        // Player.ban(...) takes a plain String shown verbatim on the ban
        // screen, not a Component -- MessageFormatter.plain() strips the
        // MiniMessage/legacy markup so it doesn't show as literal tag text.
        String banReason = MessageFormatter.plain(messages.getRaw(player, "staff.freeze-ban-reason"));
        player.ban(banReason, LEAVE_BAN_DURATION, "HCFCore", false);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.hasPermission("hcfcore.staff.freeze")) {
                viewer.sendMessage(messages.get(viewer, "staff.freeze-quit-alert", "player", player.getName()));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen(event.getPlayer())) {
            return;
        }
        // Only block actual position changes -- a frozen player can still
        // look around, just not walk/fly/fall anywhere.
        if (event.getFrom().distanceSquared(event.getTo()) > 0.0001) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "staff.freeze-blocked-command"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    /** Covers all damage sources (fall, lava, mobs, etc.), not just PvP. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    /** A frozen player can't deal damage either, on top of not taking any. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Catches every projectile a frozen player could launch -- ender
     * pearls, arrows from a bow, snowballs, eggs, fishing hooks -- not
     * just the ones a right-click starts. {@code PlayerInteractEvent}
     * cancellation alone doesn't reliably block every one of these
     * (see the class doc), so this is deliberately a second, independent
     * check on the projectile itself.
     */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean frozen(Player player) {
        return staffManager.isFrozen(player.getUniqueId());
    }
}
