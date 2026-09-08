package me.vertex.core.cannon;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.worldguard.WorldGuardHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiting, sequence tracking, and velocity clamping for
 * redstone/dispenser-triggered TNT (i.e. built cannons). TNT a player
 * lights by hand, or that chain-reacts off TNT a player lit by hand, is
 * left completely vanilla -- see {@link CannonListener#onTntSpawn} for
 * exactly how that split is made.
 */
public final class CannonManager {

    private final Plugin plugin;
    private final NamespacedKey cannonTagKey;
    private final NamespacedKey startTickKey;

    private volatile boolean enabled;
    private volatile int maxIgnitionsPerTick;
    private volatile int maxSimultaneousPerLocation;
    private volatile double maxYVelocity;
    private volatile double bandLower;
    private volatile double bandUpper;
    private volatile double bandVelocityMultiplier;
    private volatile double scanRadius;
    private volatile int launchWindowTicks;
    private volatile boolean protectLaunchStructure;
    private volatile boolean suppressFireSpread;
    private volatile Set<String> disabledRegions;
    private volatile Set<String> disabledClaimNames;

    private final ConcurrentHashMap<World, AtomicInteger> ignitionsPerWorldThisTick = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> ignitionsPerLocationThisTick = new ConcurrentHashMap<>();
    private BukkitTask resetTask;

    public CannonManager(Plugin plugin, boolean enabled, int maxIgnitionsPerTick, int maxSimultaneousPerLocation,
                          double maxYVelocity, double bandLower, double bandUpper, double bandVelocityMultiplier,
                          double scanRadius, int launchWindowTicks, boolean protectLaunchStructure,
                          boolean suppressFireSpread, Set<String> disabledRegions, Set<String> disabledClaimNames) {
        this.plugin = plugin;
        this.cannonTagKey = new NamespacedKey(plugin, "cannon-tagged");
        this.startTickKey = new NamespacedKey(plugin, "cannon-start-tick");
        reconfigure(enabled, maxIgnitionsPerTick, maxSimultaneousPerLocation, maxYVelocity, bandLower, bandUpper,
                bandVelocityMultiplier, scanRadius, launchWindowTicks, protectLaunchStructure, suppressFireSpread,
                disabledRegions, disabledClaimNames);
    }

    public void reconfigure(boolean enabled, int maxIgnitionsPerTick, int maxSimultaneousPerLocation,
                             double maxYVelocity, double bandLower, double bandUpper, double bandVelocityMultiplier,
                             double scanRadius, int launchWindowTicks, boolean protectLaunchStructure,
                             boolean suppressFireSpread, Set<String> disabledRegions, Set<String> disabledClaimNames) {
        this.enabled = enabled;
        this.maxIgnitionsPerTick = Math.max(1, maxIgnitionsPerTick);
        this.maxSimultaneousPerLocation = Math.max(1, maxSimultaneousPerLocation);
        this.maxYVelocity = maxYVelocity;
        this.bandLower = bandLower;
        this.bandUpper = bandUpper;
        this.bandVelocityMultiplier = bandVelocityMultiplier;
        // Ceiling matters as much as the floor -- this runs a
        // getNearbyEntities scan (twice: immediate + 1-tick-delayed) on
        // every cannon explosion, so a misconfigured huge radius turns
        // every shot into a large-volume entity scan.
        this.scanRadius = Math.max(1.0, Math.min(32.0, scanRadius));
        this.launchWindowTicks = Math.max(0, launchWindowTicks);
        this.protectLaunchStructure = protectLaunchStructure;
        this.suppressFireSpread = suppressFireSpread;
        this.disabledRegions = Set.copyOf(disabledRegions);
        this.disabledClaimNames = Set.copyOf(disabledClaimNames);
    }

    public void start() {
        if (resetTask != null) {
            resetTask.cancel();
        }
        // Both per-tick counters are simply wiped every tick rather than
        // decayed -- ExplosionPrimeEvent-style "cancel and let the clock's
        // next pulse retry" only works if the count is briefly full, not
        // permanently capped.
        resetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ignitionsPerWorldThisTick.clear();
            ignitionsPerLocationThisTick.clear();
        }, 1L, 1L);
    }

    public void stop() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
        ignitionsPerWorldThisTick.clear();
        ignitionsPerLocationThisTick.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Reserves one ignition slot for {@code blockLoc} this tick, against
     * both the per-world cap and the per-exact-location "simultaneous
     * stacking" cap. Returns false (reserving nothing) if either is
     * already full -- the caller cancels the spawn, and a real redstone
     * clock will simply retry on its next pulse.
     */
    public boolean tryConsumeIgnition(World world, Location blockLoc) {
        AtomicInteger worldCount = ignitionsPerWorldThisTick.computeIfAbsent(world, w -> new AtomicInteger());
        if (worldCount.get() >= maxIgnitionsPerTick) {
            return false;
        }
        String locationKey = world.getUID() + ";" + blockLoc.getBlockX() + ";" + blockLoc.getBlockY() + ";"
                + blockLoc.getBlockZ();
        AtomicInteger locationCount =
                ignitionsPerLocationThisTick.computeIfAbsent(locationKey, k -> new AtomicInteger());
        if (locationCount.get() >= maxSimultaneousPerLocation) {
            return false;
        }
        worldCount.incrementAndGet();
        locationCount.incrementAndGet();
        return true;
    }

    public void tagCannon(TNTPrimed tnt, long startTick) {
        tnt.getPersistentDataContainer().set(cannonTagKey, PersistentDataType.BOOLEAN, true);
        tnt.getPersistentDataContainer().set(startTickKey, PersistentDataType.LONG, startTick);
    }

    public boolean isCannonTagged(Entity entity) {
        return entity.getPersistentDataContainer().has(cannonTagKey, PersistentDataType.BOOLEAN);
    }

    /** Only valid on an entity {@link #isCannonTagged} already returned true for. */
    public long getStartTick(Entity entity) {
        Long startTick = entity.getPersistentDataContainer().get(startTickKey, PersistentDataType.LONG);
        return startTick == null ? Bukkit.getCurrentTick() : startTick;
    }

    public boolean isWithinLaunchWindow(long startTick) {
        return Bukkit.getCurrentTick() - startTick <= launchWindowTicks;
    }

    /** Same blocklist model as abilities.disabled-regions / abilities.disabled-claim-names. */
    public boolean isBlockedLocation(Location location) {
        return WorldGuardHook.isInDisabledRegion(location, disabledRegions)
                || FactionsHook.isDisabledClaim(location, disabledClaimNames);
    }

    public boolean protectLaunchStructure() {
        return protectLaunchStructure;
    }

    public boolean suppressFireSpread() {
        return suppressFireSpread;
    }

    /**
     * Clamps the upward velocity of every TNT/falling-block/player entity
     * near {@code center} -- run once immediately and once more one tick
     * later, since the public API gives no event for velocity vanilla
     * imparts on non-living entities (TNTPrimed, FallingBlock) from an
     * explosion, and doesn't document whether that velocity lands before
     * or after EntityExplodeEvent fires. Immediately catches the common
     * case; the delayed pass is a cheap safety net for the other order.
     */
    public void clampNearbyVelocities(Location center) {
        clampAllNearby(center);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (center.getWorld() == null || !center.getWorld().isChunkLoaded(
                    center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
                return;
            }
            clampAllNearby(center);
        });
    }

    private void clampAllNearby(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (Entity nearby : world.getNearbyEntities(center, scanRadius, scanRadius, scanRadius)) {
            if (nearby instanceof TNTPrimed || nearby instanceof FallingBlock || nearby instanceof Player) {
                clampVelocity(nearby);
            }
        }
    }

    private void clampVelocity(Entity entity) {
        Vector velocity = entity.getVelocity();
        if (velocity.getY() <= 0) {
            // Only the upward launch component is ours to bound -- an entity
            // already falling is just obeying gravity, not being propelled.
            return;
        }

        double currentY = entity.getLocation().getY();
        double bandMultiplier = (currentY >= bandLower && currentY <= bandUpper) ? bandVelocityMultiplier : 1.0;
        double clampedY = Math.min(velocity.getY() * bandMultiplier, maxYVelocity);

        // Hard safety net, independent of every config value above: never
        // leave a velocity in place that would carry the entity through the
        // world's actual build-height ceiling within the next second.
        World world = entity.getWorld();
        double headroom = world.getMaxHeight() - currentY;
        double oneSecondRise = clampedY * 20.0;
        if (oneSecondRise > headroom) {
            clampedY = Math.max(0.0, headroom / 20.0);
        }

        velocity.setY(clampedY);
        entity.setVelocity(velocity);
    }
}
