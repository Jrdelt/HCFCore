package me.vertex.core.zone;

import me.vertex.core.pvp.CombatManager;
import me.vertex.core.pvp.LootProtectionListener;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Event boundary for all zone rules; no unrelated global terrain is affected. */
public final class ZoneListener implements Listener {
    private final ZoneManager zones;
    private final CombatManager combat;
    private final LootProtectionListener lootProtection;
    private final Messages messages;

    public ZoneListener(ZoneManager zones, CombatManager combat, LootProtectionListener lootProtection, Messages messages) {
        this.zones = zones;
        this.combat = combat;
        this.lootProtection = lootProtection;
        this.messages = messages;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        zones.trackLoadedZoneMobs(event.getEntities());
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        zones.untrackUnloadedZoneMobs(event.getEntities());
    }

    private boolean protectedZone(Player player) {
        return !player.hasPermission("vertex.zones.admin.build") && zones.isInAnyZone(player.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedZone(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedZone(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { if (protectedZone(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { if (protectedZone(event.getPlayer())) event.setCancelled(true); }

    /** Haven is player-safe even against projectiles fired from a boundary or while in route flight. */
    // LOWEST is deliberate: CombatListener tags at NORMAL. Haven damage must
    // be cancelled before that existing listener sees it, rather than trying
    // to clear a potentially legitimate unrelated combat tag afterward.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerSource(event.getDamager());
        Player victim = event.getEntity() instanceof Player player ? player : null;
        if (attacker != null && victim != null) {
            if (zones.isIn(attacker, ZoneType.HAVEN) || zones.isIn(victim, ZoneType.HAVEN)) {
                event.setCancelled(true);
                return;
            }
            if (zones.isIn(victim, ZoneType.RIFTLANDS)) zones.releaseFlightFromHit(victim);
            if (zones.isIn(attacker, ZoneType.RIFTLANDS)) zones.releaseFlightFromHit(attacker);
        }
    }

    /** Zone mobs must remain attackable even when a claim/protection listener cancels the hit. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onZoneMobDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player)
                || !zones.isZoneMob(event.getEntity())) {
            return;
        }

        event.setCancelled(false);
        if (event.getEntity() instanceof org.bukkit.entity.Mob mob) {
            mob.setTarget((Player) event.getDamager());
            mob.setAware(true);
        }
        // Zone combat follows the server's 1.8-style fixed-hit behavior;
        // do not let modern attack-cooldown scaling reduce rapid swings to
        // effectively harmless damage.
        if (event.getDamage() < 1.0D) {
            event.setDamage(1.0D);
        }
    }

    /** Zone mobs have no healing or nearby-healer mechanic. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onZoneMobRegainHealth(EntityRegainHealthEvent event) {
        if (zones.isZoneMob(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        zones.cancelEntry(player, "damage");
        zones.cancelExit(player, "damage");
    }

    // Must run before BackpackAutoStoreListener (HIGHEST): zone mobs have no
    // vanilla loot, only the independently rolled zone pool below.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        zones.handleZoneDeath(event.getEntity(), event.getEntity().getKiller(), event.getDrops());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        ZoneRegion region = zones.regionAt(dead.getLocation());
        if (region == null) return;
        zones.setDeathCooldown(dead, region.type());
        if (region.type() != ZoneType.RIFTLANDS) return;
        List<ItemStack> sessionDrops = zones.takeRiftSessionLoot(dead);
        if (sessionDrops.isEmpty()) return;
        event.getDrops().addAll(sessionDrops);
        Player killer = dead.getKiller();
        if (killer == null) {
            UUID opponent = combat.getOpponentId(dead.getUniqueId());
            killer = opponent == null ? null : Bukkit.getPlayer(opponent);
        }
        // Non-PvP deaths intentionally remain public. The existing listener
        // owns PDC payload and expiry checks, so no competing protection key.
        if (killer != null) lootProtection.protect(sessionDrops, killer);
    }

    /**
     * Uses the same priority/cancellation contract as {@code MineListener}'s
     * selection wand.  Several unrelated item listeners legitimately cancel
     * an air interaction before HIGHEST; a region selection must still see
     * that input so a sneaking left/right air-click can finish it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onSelectorInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (!zones.isSelector(held)) return;
        // Preserve the flight-release precedence from the normal interaction
        // handler: a selector click during guided flight means "drop now",
        // never "edit a region while airborne".
        if (zones.isFlying(player.getUniqueId())) {
            zones.releaseFlight(player.getUniqueId(), true);
            event.setCancelled(true);
            return;
        }
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                event.setCancelled(true);
                String result = zones.isRouteSelecting(player.getUniqueId())
                        ? zones.addRoutePoint(player, event.getClickedBlock().getLocation().add(.5, 1, .5))
                        : zones.selectCorner(player, event.getClickedBlock().getLocation(), true);
                selectionFeedback(player, result, false);
            }
            case RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                String result = zones.isRouteSelecting(player.getUniqueId())
                        ? zones.removeRoutePoint(player)
                        : zones.selectCorner(player, event.getClickedBlock().getLocation(), false);
                selectionFeedback(player, result, false);
            }
            case LEFT_CLICK_AIR, RIGHT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    event.setCancelled(true);
                    String result = zones.finishSelection(player);
                    selectionFeedback(player, result, true);
                }
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (held == null) held = player.getInventory().getItemInMainHand();
        if (zones.isFlying(player.getUniqueId())) {
            zones.releaseFlight(player.getUniqueId(), true);
            event.setCancelled(true);
            return;
        }
        if (zones.isTicket(held) && (event.getAction().isRightClick())) {
            event.setCancelled(true);
            String result = zones.useTicket(player, held);
            if (!"ok".equals(result)) {
                String key = switch (result) { case "not-rift" -> "zones.ticket-not-rift"; case "not-combat" -> "zones.ticket-not-combat"; default -> "zones.ticket-no-safe"; };
                player.sendMessage(zones.message(player, key));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        zones.cancelEntry(event.getPlayer(), "movement");
        zones.cancelExit(event.getPlayer(), "movement");
    }
    private boolean sameBlock(Location a, Location b) { return a.getWorld().equals(b.getWorld()) && a.getBlockX()==b.getBlockX() && a.getBlockY()==b.getBlockY() && a.getBlockZ()==b.getBlockZ(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim().toLowerCase(java.util.Locale.ROOT);
        if (!(raw.equals("/spawn") || raw.startsWith("/spawn "))) return;
        Player player = event.getPlayer();
        // Let the actual SpawnCommand consume the one-shot marker. The zone
        // listener must only avoid starting a second exit countdown here.
        if (zones.hasSpawnDispatchBypass(player.getUniqueId())) return;
        if (!zones.isInAnyZone(player.getLocation())) return;
        event.setCancelled(true);
        String result = zones.beginExit(player);
        String key = switch (result) { case "combat" -> "zones.spawn-combat"; case "ok" -> "zones.spawn-started"; default -> "zones.spawn-not-in-zone"; };
        player.sendMessage(messages.get(player, key));
    }

    private void selectionFeedback(Player player, String result, boolean saved) {
        String key = "ok".equals(result) ? (saved ? "zones.selection-saved" : "zones.selection-updated")
                : (saved ? "zones.selection-save-failed" : "zones.selection-error");
        player.sendMessage(messages.get(player, key, "reason", result));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (zones.isSessionItem(event.getPlayer(), event.getItemDrop().getItemStack())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSessionMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        boolean session = zones.isSessionItem(player, cursor) || zones.isSessionItem(player, current);
        if (!session) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked != null && clicked != player.getInventory()) event.setCancelled(true);
        if (event.isShiftClick() || event.getHotbarButton() >= 0) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSessionDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && zones.isSessionItem(player, event.getOldCursor())) {
            for (int rawSlot : event.getRawSlots()) if (rawSlot < event.getView().getTopInventory().getSize()) { event.setCancelled(true); return; }
        }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        zones.cancelEntry(event.getPlayer(), "disconnect"); zones.cancelExit(event.getPlayer(), "disconnect"); zones.recordFlightDisconnect(event.getPlayer());
    }
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        zones.preloadPlayer(event.getUniqueId(), event.getName());
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent event) { zones.completePlayerJoin(event.getPlayer()); }
    private static Player playerSource(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
