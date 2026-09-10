package me.vertex.core.shield;

import me.vertex.core.claims.BaseClaimManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns per-faction Shield schedules, the new-faction eligibility timer, and
 * staff overrides.
 *
 * <p><b>Schedule model.</b> A Shield schedule is one daily recurring window
 * (start-of-day minute + duration), anchored to real-world Eastern time
 * (see {@link ShieldSchedule}). Because it is wall-clock-anchored rather
 * than an elapsing countdown, "keeps advancing during downtime" falls out
 * for free -- there is nothing to catch up on restart for the recurring
 * part; only the activation-delay and new-faction-eligibility deadlines
 * (both absolute epoch millis) need the usual persisted-deadline pattern
 * ({@code FTopManager}/{@code RaidClaimManager}'s idiom).
 *
 * <p><b>"Let the current window finish before switching schedules."</b>
 * {@link #effectiveSchedule(int, long)} is computed live, stateless, on
 * every call: before the activation delay elapses, the live schedule
 * applies unconditionally; once the delay has elapsed, the live schedule
 * *still* applies for as long as its own window is still active (so an
 * in-progress protection period is never cut short), and only switches to
 * the pending schedule once that window ends. Because the delay deadline is
 * in the past forever after it elapses, this naturally becomes permanent
 * the first time the live window is inactive -- no timer needs to "notice"
 * the moment. The bounded sweep in {@link #sweep()} only exists to persist
 * that swap (clearing the pending columns) and write the one-time
 * "schedule activated" log entry -- {@link #isShieldActive} does not depend
 * on the sweep having run yet.
 *
 * <p><b>Override freeze/resume.</b> Applying an override snapshots how much
 * of the *current* window (if any) was left, in {@code frozen_remaining_millis}.
 * Removing the override, if that snapshot is non-null, stamps a
 * {@code frozen_resume_until} deadline of {@code now + remaining} so the
 * faction is treated as still-protected for exactly that much longer before
 * the normal wall-clock schedule takes back over -- a one-off extension of
 * the current occurrence rather than a rewound countdown. If the snapshot
 * was null (no window was active when the override was applied), removing
 * the override simply lets the ordinary wall-clock schedule resume, which
 * needs no special handling since it was never paused in any stateful sense.
 *
 * <p><b>Shield events</b> (schedule submissions, pending-schedule
 * activation, staff force-enable/disable) are written to
 * {@code faction_shield_log} for a future {@code /f logs} reader -- no such
 * reader exists yet in this codebase, so this phase only writes the rows
 * (see class doc on {@code ShieldStorage}). Per spec, overrides are never
 * announced to the affected faction in chat.
 */
public final class ShieldManager {

    private final Plugin plugin;
    private final ShieldStorage storage;
    private final BaseClaimManager baseClaims;
    private final File file;

    private volatile long activationDelayMillis;
    private volatile long newFactionDelayMillis;
    private volatile long sweepIntervalTicks;
    private volatile boolean combatProtectionEnabled;

    private final Map<Integer, ShieldStorage.ShieldRow> rows = new ConcurrentHashMap<>();
    private final Map<Integer, ShieldStorage.OverrideRow> overrides = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ShieldManager(Plugin plugin, ShieldStorage storage, BaseClaimManager baseClaims) {
        this.plugin = plugin;
        this.storage = storage;
        this.baseClaims = baseClaims;
        this.file = new File(plugin.getDataFolder(), "shield.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("shield.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        activationDelayMillis = Math.max(0L, config.getLong("shield.activation-delay-seconds", 24L * 3_600L)) * 1_000L;
        newFactionDelayMillis = Math.max(0L, config.getLong("shield.new-faction-delay-seconds", 6L * 3_600L)) * 1_000L;
        sweepIntervalTicks = Math.max(20L, config.getLong("shield.sweep-interval-seconds", 30L) * 20L);
        combatProtectionEnabled = config.getBoolean("shield.combat-protection-enabled", true);
    }

    public void loadState() {
        rows.clear();
        overrides.clear();
        try {
            for (ShieldStorage.ShieldRow row : storage.loadAll()) {
                rows.put(row.factionId(), row);
            }
            for (ShieldStorage.OverrideRow row : storage.loadAllOverrides()) {
                overrides.put(row.factionId(), row);
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Faction Shield state; starting empty.", error);
        }
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, sweepIntervalTicks, sweepIntervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    public boolean combatProtectionEnabled() {
        return combatProtectionEnabled;
    }

    // ---- New-faction eligibility ----

    /** Call from a {@code FactionCreateEvent} handler: starts (or restarts) this faction's Shield wait. */
    public void onFactionCreated(int factionId) {
        long eligibleAt = System.currentTimeMillis() + newFactionDelayMillis;
        ShieldStorage.ShieldRow row = new ShieldStorage.ShieldRow(factionId, null, null, null, null, null, null, eligibleAt);
        rows.put(factionId, row);
        persist(row);
    }

    /** Call from {@code FactionDisbandEvent}/{@code FactionAutoDisbandEvent}: a recreated faction gets a fresh wait. */
    public void onFactionDisbanded(int factionId) {
        rows.remove(factionId);
        overrides.remove(factionId);
        try {
            storage.deleteRow(factionId);
            storage.deleteOverride(factionId);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear a disbanded faction's Shield state.", error);
        }
    }

    public boolean isEligible(int factionId) {
        ShieldStorage.ShieldRow row = rows.get(factionId);
        return row != null && System.currentTimeMillis() >= row.newFactionEligibleAt();
    }

    public long eligibleAtMillis(int factionId) {
        ShieldStorage.ShieldRow row = rows.get(factionId);
        return row == null ? 0L : row.newFactionEligibleAt();
    }

    // ---- Schedule submission ----

    public enum SubmitResult { OK, NOT_ELIGIBLE, NO_ROW }

    public SubmitResult submitSchedule(int factionId, ShieldSchedule schedule, UUID actorUuid) {
        ShieldStorage.ShieldRow existing = rows.get(factionId);
        if (existing == null) {
            return SubmitResult.NO_ROW; // faction was never seen created -- shouldn't normally happen
        }
        if (!isEligible(factionId)) {
            return SubmitResult.NOT_ELIGIBLE;
        }
        long activatesAt = System.currentTimeMillis() + activationDelayMillis;
        ShieldStorage.ShieldRow updated = new ShieldStorage.ShieldRow(factionId,
                existing.scheduleStartMinute(), existing.scheduleDurationMinutes(),
                schedule.startMinuteOfDay(), schedule.durationMinutes(), activatesAt,
                existing.frozenResumeUntil(), existing.newFactionEligibleAt());
        rows.put(factionId, updated);
        persist(updated);
        log(factionId, "SCHEDULE_SUBMITTED", actorUuid,
                "schedule=" + schedule + " activatesAt=" + activatesAt);
        return SubmitResult.OK;
    }

    // ---- Live schedule resolution ----

    /** The schedule actually in effect right now -- see the class doc for the "finish current window" rule. */
    public Optional<ShieldSchedule> effectiveSchedule(int factionId, long now) {
        ShieldStorage.ShieldRow row = rows.get(factionId);
        if (row == null) {
            return Optional.empty();
        }
        Optional<ShieldSchedule> live = row.scheduleStartMinute() == null ? Optional.empty()
                : Optional.of(new ShieldSchedule(row.scheduleStartMinute(), row.scheduleDurationMinutes()));
        if (row.pendingActivatesAt() == null) {
            return live;
        }
        if (now < row.pendingActivatesAt()) {
            return live;
        }
        // Delay elapsed: keep the live schedule only while its own window is still running.
        if (live.isPresent() && live.get().isActiveAt(now)) {
            return live;
        }
        return Optional.of(new ShieldSchedule(row.pendingStartMinute(), row.pendingDurationMinutes()));
    }

    public boolean isShieldActive(int factionId) {
        return isShieldActive(factionId, System.currentTimeMillis());
    }

    public boolean isShieldActive(int factionId, long now) {
        ShieldStorage.OverrideRow override = overrides.get(factionId);
        if (override != null) {
            return "ACTIVE".equals(override.forcedState());
        }
        ShieldStorage.ShieldRow row = rows.get(factionId);
        if (row != null && row.frozenResumeUntil() != null && now < row.frozenResumeUntil()) {
            return true;
        }
        return effectiveSchedule(factionId, now).map(schedule -> schedule.isActiveAt(now)).orElse(false);
    }

    /** True only for a location inside an active-Shield faction's Base Claim -- Raid Claims are never protected. */
    public boolean isBaseClaimProtected(Location location) {
        BaseClaimManager.Region region = baseClaims.regionAt(location);
        return region != null && isShieldActive(region.factionId());
    }

    /** Countdown, in seconds, to the next activation if currently inactive; 0 if already active or no schedule. */
    public long secondsUntilNextActivation(int factionId) {
        if (isShieldActive(factionId)) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        return effectiveSchedule(factionId, now)
                .map(schedule -> Math.max(0L, (schedule.nextActivationAfter(now) - now + 999L) / 1_000L))
                .orElse(-1L);
    }

    /** Countdown, in seconds, until the current active window ends; 0 if not currently active. */
    public long secondsUntilDeactivation(int factionId) {
        long now = System.currentTimeMillis();
        if (!isShieldActive(factionId, now)) {
            return 0L;
        }
        ShieldStorage.OverrideRow override = overrides.get(factionId);
        if (override != null) {
            return -1L; // forced active indefinitely until the override is lifted
        }
        ShieldStorage.ShieldRow row = rows.get(factionId);
        if (row != null && row.frozenResumeUntil() != null && now < row.frozenResumeUntil()) {
            return Math.max(0L, (row.frozenResumeUntil() - now + 999L) / 1_000L);
        }
        return effectiveSchedule(factionId, now)
                .map(schedule -> Math.max(0L, (schedule.currentWindowEndMillis(now) - now + 999L) / 1_000L))
                .orElse(0L);
    }

    public Optional<ShieldSchedule> liveSchedule(int factionId) {
        ShieldStorage.ShieldRow row = rows.get(factionId);
        if (row == null || row.scheduleStartMinute() == null) {
            return Optional.empty();
        }
        return Optional.of(new ShieldSchedule(row.scheduleStartMinute(), row.scheduleDurationMinutes()));
    }

    public Optional<ShieldSchedule> pendingSchedule(int factionId) {
        ShieldStorage.ShieldRow row = rows.get(factionId);
        if (row == null || row.pendingActivatesAt() == null) {
            return Optional.empty();
        }
        return Optional.of(new ShieldSchedule(row.pendingStartMinute(), row.pendingDurationMinutes()));
    }

    public long pendingActivatesAt(int factionId) {
        ShieldStorage.ShieldRow row = rows.get(factionId);
        return row != null && row.pendingActivatesAt() != null ? row.pendingActivatesAt() : -1L;
    }

    // ---- Admin override ----

    public Optional<ShieldStorage.OverrideRow> override(int factionId) {
        return Optional.ofNullable(overrides.get(factionId));
    }

    /** Forces the Shield ACTIVE or INACTIVE, freezing the normal schedule. Silent to the affected faction. */
    public void applyOverride(int factionId, boolean forceActive, UUID actorUuid) {
        ShieldStorage.OverrideRow existing = overrides.get(factionId);
        long now = System.currentTimeMillis();
        Long frozenRemaining = existing != null ? existing.frozenRemainingMillis() : computeRemainingIfActive(factionId, now);
        ShieldStorage.OverrideRow row = new ShieldStorage.OverrideRow(factionId, forceActive ? "ACTIVE" : "INACTIVE",
                frozenRemaining, actorUuid == null ? null : actorUuid.toString(), now);
        overrides.put(factionId, row);
        try {
            storage.upsertOverride(row);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Shield admin override.", error);
        }
        log(factionId, forceActive ? "OVERRIDE_FORCE_ACTIVE" : "OVERRIDE_FORCE_INACTIVE", actorUuid, null);
    }

    private Long computeRemainingIfActive(int factionId, long now) {
        if (!isShieldActive(factionId, now)) {
            return null;
        }
        ShieldStorage.ShieldRow row = rows.get(factionId);
        if (row != null && row.frozenResumeUntil() != null && now < row.frozenResumeUntil()) {
            return row.frozenResumeUntil() - now;
        }
        return effectiveSchedule(factionId, now).map(schedule -> {
            long end = schedule.currentWindowEndMillis(now);
            return end < 0 ? null : end - now;
        }).orElse(null);
    }

    /** Removes the override, resuming the normal schedule with whatever time remained when it was frozen. */
    public void removeOverride(int factionId, UUID actorUuid) {
        ShieldStorage.OverrideRow existing = overrides.remove(factionId);
        try {
            storage.deleteOverride(factionId);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove a persisted Shield admin override.", error);
        }
        if (existing != null && existing.frozenRemainingMillis() != null) {
            long resumeUntil = System.currentTimeMillis() + existing.frozenRemainingMillis();
            ShieldStorage.ShieldRow row = rows.get(factionId);
            if (row != null) {
                ShieldStorage.ShieldRow updated = new ShieldStorage.ShieldRow(factionId,
                        row.scheduleStartMinute(), row.scheduleDurationMinutes(), row.pendingStartMinute(),
                        row.pendingDurationMinutes(), row.pendingActivatesAt(), resumeUntil, row.newFactionEligibleAt());
                rows.put(factionId, updated);
                persist(updated);
            }
        }
        log(factionId, "OVERRIDE_REMOVED", actorUuid, null);
    }

    // ---- Bounded periodic sweep ----

    /** Only iterates factions with a pending schedule swap outstanding -- never a full scan. */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (ShieldStorage.ShieldRow row : rows.values()) {
            if (row.pendingActivatesAt() == null || now < row.pendingActivatesAt()) {
                continue;
            }
            Optional<ShieldSchedule> live = row.scheduleStartMinute() == null ? Optional.empty()
                    : Optional.of(new ShieldSchedule(row.scheduleStartMinute(), row.scheduleDurationMinutes()));
            if (live.isPresent() && live.get().isActiveAt(now)) {
                continue; // still finishing the old window -- defer the swap
            }
            ShieldStorage.ShieldRow committed = new ShieldStorage.ShieldRow(row.factionId(),
                    row.pendingStartMinute(), row.pendingDurationMinutes(), null, null, null,
                    row.frozenResumeUntil(), row.newFactionEligibleAt());
            rows.put(row.factionId(), committed);
            persist(committed);
            log(row.factionId(), "SCHEDULE_ACTIVATED", null,
                    "schedule=" + new ShieldSchedule(row.pendingStartMinute(), row.pendingDurationMinutes()));
        }
    }

    private void persist(ShieldStorage.ShieldRow row) {
        try {
            storage.upsertRow(row);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist Faction Shield state.", error);
        }
    }

    private void log(int factionId, String action, UUID actorUuid, String details) {
        try {
            storage.insertLog(factionId, action, actorUuid == null ? null : actorUuid.toString(), details,
                    System.currentTimeMillis());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write a Faction Shield log entry.", error);
        }
    }
}
