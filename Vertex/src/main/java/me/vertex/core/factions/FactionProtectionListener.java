package me.vertex.core.factions;

import me.vertex.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/** Server-authoritative build, container, and PvP protection for native Vertex claims. */
public final class FactionProtectionListener implements Listener {
    private final Plugin plugin;
    private final FactionService factions;
    private final Messages messages;

    public FactionProtectionListener(Plugin plugin, FactionService factions, Messages messages) {
        this.plugin = plugin; this.factions = factions; this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { if (!factions.canBreak(event.getPlayer(), event.getBlock().getLocation())) deny(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { if (!factions.canPlace(event.getPlayer(), event.getBlockPlaced().getLocation())) deny(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) { if (!factions.canPlace(event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) deny(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { if (!factions.canBreak(event.getPlayer(), event.getBlockClicked().getLocation())) deny(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null || !event.getAction().isRightClick()) return;
        Block block = event.getClickedBlock(); Player player = event.getPlayer();
        if (block.getState() instanceof Container) {
            if (!factions.canUseContainers(player, block.getLocation())) deny(player, event);
        } else if (block.getType().isInteractable() && !factions.canUseDoors(player, block.getLocation())) {
            deny(player, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim)) return;
        if (!factions.canPvp(attacker, victim) || protectedPvpZone(attacker.getLocation()) || protectedPvpZone(victim.getLocation())) {
            event.setCancelled(true); attacker.sendActionBar(messages.get(attacker, "native-factions.pvp-disabled"));
        }
    }

    private boolean protectedPvpZone(org.bukkit.Location location) {
        String tag = factions.factionTagAt(location);
        return tag != null && plugin.getConfig().getStringList("factions.system-claims.no-pvp-tags").stream().anyMatch(value -> value.equalsIgnoreCase(tag));
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    private void deny(Player player, org.bukkit.event.Cancellable event) {
        event.setCancelled(true);
        player.sendActionBar(messages.get(player, "native-factions.claim-action-denied"));
    }
}
