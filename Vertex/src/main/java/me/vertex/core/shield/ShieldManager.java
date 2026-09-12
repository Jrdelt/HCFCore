package me.vertex.core.shield;

import me.vertex.core.claims.BaseClaimManager;
import me.vertex.core.factions.FactionsHook;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntToLongFunction;
import java.util.logging.Level;

/** Restart-safe manual and weekly Shield protection for Base Claims. */
public final class ShieldManager {
    private final Plugin plugin;
    private final ShieldStorage storage;
    private final BaseClaimManager baseClaims;
    private final File file;
    private final Map<Integer, ShieldStorage.ActivationRow> activations = new ConcurrentHashMap<>();
    private final Map<Integer, ShieldStorage.OverrideRow> overrides = new ConcurrentHashMap<>();
    private final Map<Integer, WeeklyState> weekly = new ConcurrentHashMap<>();
    private volatile long baseDurationSeconds;
    private volatile long cooldownSeconds;
    private volatile long maximumDurationSeconds;
    private volatile long newFactionDelaySeconds;
    private volatile boolean combatProtectionEnabled;
    private volatile int maximumDailyMinutes;
    private volatile long scheduleEditLockMillis;
    private volatile long scheduleActivationDelayMillis;
    private volatile ZoneId scheduleZone;
    private org.bukkit.scheduler.BukkitTask scheduleTask;
    private volatile IntToLongFunction durationBonusSeconds = ignored -> 0L;
    private volatile Runnable mutationPublisher = () -> { };

    /** BaseClaimManager remains in the signature for compatibility with the existing bootstrap. */
    public ShieldManager(Plugin plugin, ShieldStorage storage, BaseClaimManager baseClaims) {
        this.plugin = plugin;
        this.storage = storage;
        this.baseClaims = baseClaims;
        this.file = me.vertex.core.factions.FactionConfigManager.file(plugin);
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("factions.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        baseDurationSeconds = positive(config.getLong("shield.base-duration-seconds", 28_800L), 28_800L);
        cooldownSeconds = Math.max(0, config.getLong("shield.cooldown-seconds", 86_400L));
        maximumDurationSeconds = positive(config.getLong("shield.maximum-duration-seconds", 43_200L), 43_200L);
        newFactionDelaySeconds = Math.max(0, config.getLong("shield.new-faction-delay-seconds", 0L));
        combatProtectionEnabled = config.getBoolean("shield.combat-protection-enabled", false);
        maximumDailyMinutes = Math.max(0, Math.min(1_440, config.getInt("shield.schedule.maximum-hours-per-day", 8) * 60));
        scheduleEditLockMillis = Math.max(0L, config.getLong("shield.schedule.edit-lock-seconds", 86_400L) * 1_000L);
        scheduleActivationDelayMillis = Math.max(0L, config.getLong("shield.schedule.activation-delay-seconds", 86_400L) * 1_000L);
        try { scheduleZone = ZoneId.of(config.getString("shield.schedule.time-zone", ZoneId.systemDefault().getId())); }
        catch (Exception error) { scheduleZone = ZoneId.systemDefault(); plugin.getLogger().warning("Invalid Shield schedule time-zone; using " + scheduleZone + "."); }
    }

    private static long positive(long value, long fallback) { return value > 0 ? value : fallback; }

    public void loadState() {
        activations.clear();
        overrides.clear();
        weekly.clear();
        try {
            for (ShieldStorage.ActivationRow row : storage.loadAllActivations()) activations.put(row.factionId(), row);
            for (ShieldStorage.OverrideRow row : storage.loadAllOverrides()) overrides.put(row.factionId(), row);
            for (ShieldStorage.WeeklyRow row : storage.loadWeekly()) weekly.put(row.factionId(), WeeklyState.from(row));
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Faction Shield state.", error);
        }
    }

    public void start() {
        if (scheduleTask != null) scheduleTask.cancel();
        scheduleTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin,
                this::promotePendingSchedules, 20L, 20L);
    }

    public void shutdown() {
        if (scheduleTask != null) scheduleTask.cancel();
    }
    public boolean combatProtectionEnabled() { return combatProtectionEnabled; }
    public ZoneId scheduleZone() { return scheduleZone; }

    public void setDurationBonusProvider(IntToLongFunction provider) {
        durationBonusSeconds = provider == null ? ignored -> 0L : provider;
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    public void refreshAsync() {
        CompletableFuture.runAsync(this::loadState);
    }

    public long durationSeconds(int factionId) {
        long bonus = Math.max(0, durationBonusSeconds.applyAsLong(factionId));
        try { return Math.min(maximumDurationSeconds, Math.addExact(baseDurationSeconds, bonus)); }
        catch (ArithmeticException ignored) { return maximumDurationSeconds; }
    }

    public void onFactionCreated(int factionId) {
        long now = System.currentTimeMillis();
        ShieldStorage.ActivationRow row = new ShieldStorage.ActivationRow(factionId, 0, 0,
                safeDeadline(now, newFactionDelaySeconds), 0, null);
        if (persist(row)) activations.put(factionId, row);
        WeeklyState state=new WeeklyState(ShieldSchedule.empty(),null,0L,0L,null);
        if(persistWeekly(factionId,state))weekly.put(factionId,state);
        mutationPublisher.run();
    }

    public void onFactionDisbanded(int factionId) {
        try {
            storage.deleteFactionState(factionId);
            activations.remove(factionId);
            overrides.remove(factionId);
            weekly.remove(factionId);
            mutationPublisher.run();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear disbanded faction Shield state.", error);
        }
    }

    public boolean isEligible(int factionId) {
        ShieldStorage.ActivationRow row = activations.get(factionId);
        return row == null || System.currentTimeMillis() >= row.eligibleAt();
    }

    public long eligibleAtMillis(int factionId) {
        ShieldStorage.ActivationRow row = activations.get(factionId);
        return row == null ? 0 : row.eligibleAt();
    }

    public enum ActivateResult { OK, ALREADY_ACTIVE, COOLDOWN, NOT_ELIGIBLE, NO_PERMISSION, STORAGE_ERROR }

    public synchronized ActivateResult activate(int factionId, UUID actorUuid) {
        long now = System.currentTimeMillis();
        // Overrides and weekly windows are separate from the manual activation
        // row, so reject those from the live view before entering storage.
        if (isShieldActive(factionId, now)) return ActivateResult.ALREADY_ACTIVE;
        try {
            ShieldStorage.ActivationMutation mutation = storage.activate(factionId, now,
                    secondsToMillis(durationSeconds(factionId)), secondsToMillis(cooldownSeconds),
                    actorUuid == null ? null : actorUuid.toString());
            if (mutation.row() != null) activations.put(factionId, mutation.row());
            ActivateResult result = switch (mutation.status()) {
                case OK -> ActivateResult.OK;
                case ALREADY_ACTIVE -> ActivateResult.ALREADY_ACTIVE;
                case COOLDOWN -> ActivateResult.COOLDOWN;
                case NOT_ELIGIBLE -> ActivateResult.NOT_ELIGIBLE;
                case NOT_AUTHORIZED -> ActivateResult.NO_PERMISSION;
                case NOT_FOUND -> ActivateResult.STORAGE_ERROR;
            };
            if (result == ActivateResult.OK) {
                ShieldStorage.ActivationRow row = mutation.row();
                log(factionId, "ACTIVATED", actorUuid,
                        "activeUntil=" + row.activeUntil() + " cooldownUntil=" + row.cooldownUntil());
                mutationPublisher.run();
            }
            return result;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist Shield activation.", error);
            return ActivateResult.STORAGE_ERROR;
        }
    }

    private static long secondsToMillis(long seconds) {
        try { return Math.multiplyExact(Math.max(0L, seconds), 1_000L); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private static long safeDeadline(long startMillis, long seconds) {
        try { return Math.addExact(startMillis, Math.multiplyExact(seconds, 1_000L)); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    public boolean isShieldActive(int factionId) { return isShieldActive(factionId, System.currentTimeMillis()); }

    public boolean isShieldActive(int factionId, long now) {
        ShieldStorage.OverrideRow override = overrides.get(factionId);
        if (override != null) return "ACTIVE".equals(override.forcedState());
        ShieldStorage.ActivationRow row = activations.get(factionId);
        if(row != null && now < row.activeUntil())return true;
        WeeklyState state=weekly.get(factionId);
        return state!=null&&state.current().activeAt(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now),scheduleZone));
    }

    public boolean isClaimProtected(Location location) {
        int factionId = FactionsHook.getClaimFactionId(location);
        return factionId != FactionsHook.NO_FACTION && baseClaims.isBaseClaim(location) && isShieldActive(factionId);
    }

    /** Shield intentionally protects Base Claims only; Raid Claims remain vulnerable. */
    public boolean isBaseClaimProtected(Location location) { return isClaimProtected(location); }

    public long secondsUntilDeactivation(int factionId) {
        ShieldStorage.OverrideRow override = overrides.get(factionId);
        if (override != null && "ACTIVE".equals(override.forcedState())) return -1;
        ShieldStorage.ActivationRow row = activations.get(factionId);
        long manual=row == null ? 0 : Math.max(0, (row.activeUntil() - System.currentTimeMillis() + 999) / 1_000);
        WeeklyState state=weekly.get(factionId);long scheduled=state==null?0:state.current().secondsUntilWindowEnd(ZonedDateTime.now(scheduleZone));
        return Math.max(manual,scheduled);
    }

    public long secondsUntilAvailable(int factionId) {
        ShieldStorage.ActivationRow row = activations.get(factionId);
        if (row == null) return 0;
        long target = Math.max(row.eligibleAt(), row.cooldownUntil());
        return Math.max(0, (target - System.currentTimeMillis() + 999) / 1_000);
    }

    /** Time until the next weekly window, used by /f who and placeholders. */
    public long secondsUntilNextWindow(int factionId) {
        WeeklyState state = weekly.get(factionId);
        if (state == null) return secondsUntilAvailable(factionId);
        long scheduled = state.current().secondsUntilNextStart(ZonedDateTime.now(scheduleZone));
        long manual = secondsUntilAvailable(factionId);
        if (scheduled <= 0L) return manual;
        return manual <= 0L ? scheduled : Math.min(manual, scheduled);
    }

    public Optional<ShieldStorage.OverrideRow> override(int factionId) { return Optional.ofNullable(overrides.get(factionId)); }

    public ShieldSchedule schedule(int factionId) {
        WeeklyState state = weekly.get(factionId);
        return state == null ? ShieldSchedule.empty() : state.current();
    }

    public ShieldSchedule editableSchedule(int factionId) {
        WeeklyState state = weekly.get(factionId);
        if (state == null) return ShieldSchedule.empty();
        return state.pending() == null ? state.current() : state.pending();
    }

    public ShieldSchedule pendingSchedule(int factionId) {
        WeeklyState state = weekly.get(factionId);
        return state == null ? null : state.pending();
    }

    public long scheduleLockedForMillis(int factionId) {
        WeeklyState state = weekly.get(factionId);
        return state == null ? 0L : Math.max(0L, state.lockedUntil() - System.currentTimeMillis());
    }

    public long pendingActivatesInMillis(int factionId) {
        WeeklyState state = weekly.get(factionId);
        return state == null || state.pending() == null ? 0L
                : Math.max(0L, state.pendingActivatesAt() - System.currentTimeMillis());
    }

    public int maximumDailyMinutes() { return maximumDailyMinutes; }

    public enum ScheduleResult { OK, LOCKED, INVALID, NO_PERMISSION, STORAGE_ERROR }

    public synchronized ScheduleResult replaceSchedule(int factionId, ShieldSchedule changed,
            UUID actor, boolean adminBypass) {
        if (changed == null || changed.days().size() != 7) return ScheduleResult.INVALID;
        for (ShieldSchedule.Window window : changed.days()) {
            if (window.startMinute() < 0 || window.startMinute() >= 1_440
                    || window.durationMinutes() < 0 || window.durationMinutes() > maximumDailyMinutes) {
                return ScheduleResult.INVALID;
            }
        }
        for (int day = 0; day < 7; day++) {
            if (changed.protectedMinutes(day) > maximumDailyMinutes) return ScheduleResult.INVALID;
        }
        long now = System.currentTimeMillis();
        try {
            ShieldStorage.WeeklyMutation mutation = storage.replaceWeekly(factionId, changed.encode(),
                    changed.pvpProtected(), now, scheduleActivationDelayMillis, scheduleEditLockMillis,
                    actor == null ? null : actor.toString(), adminBypass);
            WeeklyState next = WeeklyState.from(mutation.row());
            weekly.put(factionId, next);
            if (mutation.status() == ShieldStorage.WeeklyMutationStatus.LOCKED) return ScheduleResult.LOCKED;
            if (mutation.status() == ShieldStorage.WeeklyMutationStatus.NOT_AUTHORIZED) {
                return ScheduleResult.NO_PERMISSION;
            }
            log(factionId, "SCHEDULE_REPLACED", actor,
                    "schedule=" + changed.encode() + ";pvp=" + changed.pvpProtected()
                            + ";activatesAt=" + (next.pending() == null ? now : next.pendingActivatesAt()));
            mutationPublisher.run();
            return ScheduleResult.OK;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist Shield schedule.", error);
            return ScheduleResult.STORAGE_ERROR;
        }
    }

    public synchronized ScheduleResult editSchedule(int factionId, int day, int startMinute,
            int durationMinutes, Boolean pvp, UUID actor, boolean adminBypass) {
        if (day < 0 || day > 6 || startMinute < 0 || startMinute >= 1_440
                || durationMinutes < 0 || durationMinutes > maximumDailyMinutes) {
            return ScheduleResult.INVALID;
        }
        ShieldSchedule base = editableSchedule(factionId);
        ShieldSchedule changed = base.withDay(day, new ShieldSchedule.Window(startMinute, durationMinutes));
        if (pvp != null) changed = changed.withPvpProtected(pvp);
        return replaceSchedule(factionId, changed, actor, adminBypass);
    }

    public boolean isPvpProtected(Location location) {
        if (!isClaimProtected(location)) return false;
        int factionId = FactionsHook.getClaimFactionId(location);
        WeeklyState state = weekly.get(factionId);
        boolean factionOption = state != null && state.current().pvpProtected();
        return combatProtectionEnabled || factionOption;
    }

    public synchronized boolean applyOverride(int factionId, boolean active, UUID actorUuid) {
        long now = System.currentTimeMillis();
        ShieldStorage.OverrideRow row = new ShieldStorage.OverrideRow(factionId, active ? "ACTIVE" : "INACTIVE",
                null, actorUuid == null ? null : actorUuid.toString(), now);
        try {
            storage.upsertOverride(row);
            overrides.put(factionId, row);
            log(factionId, active ? "OVERRIDE_FORCE_ACTIVE" : "OVERRIDE_FORCE_INACTIVE", actorUuid, null);
            mutationPublisher.run();
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist Shield override.", error);
            return false;
        }
    }

    public synchronized boolean removeOverride(int factionId, UUID actorUuid) {
        try {
            storage.deleteOverride(factionId);
            overrides.remove(factionId);
            log(factionId, "OVERRIDE_REMOVED", actorUuid, null);
            mutationPublisher.run();
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove Shield override.", error);
            return false;
        }
    }

    private boolean persist(ShieldStorage.ActivationRow row) {
        try { storage.upsertActivation(row); return true; }
        catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist Faction Shield activation.", error);
            return false;
        }
    }

    private boolean persistWeekly(int factionId, WeeklyState state) {
        try {
            storage.saveWeekly(new ShieldStorage.WeeklyRow(factionId, state.current().encode(),
                    state.current().pvpProtected(), state.pending() == null ? null : state.pending().encode(),
                    state.pending() == null ? null : state.pending().pvpProtected(),
                    state.pendingActivatesAt(), state.lockedUntil(),
                    state.updatedBy() == null ? null : state.updatedBy().toString()));
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist Shield schedule.", error);
            return false;
        }
    }

    private synchronized void promotePendingSchedules() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, WeeklyState> entry : weekly.entrySet()) {
            WeeklyState state = entry.getValue();
            if (state.pending() == null || now < state.pendingActivatesAt()) continue;
            WeeklyState promoted = new WeeklyState(state.pending(), null, 0L,
                    state.lockedUntil(), state.updatedBy());
            if (!weekly.getOrDefault(entry.getKey(), state).equals(state)) continue;
            try {
                if (!storage.promoteWeekly(entry.getKey(), state.pending().encode(),
                        state.pendingActivatesAt())) {
                    refreshAsync();
                    continue;
                }
                weekly.replace(entry.getKey(), state, promoted);
                mutationPublisher.run();
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to promote a pending Shield schedule.", error);
            }
        }
    }

    private record WeeklyState(ShieldSchedule current, ShieldSchedule pending, long pendingActivatesAt,
            long lockedUntil, UUID updatedBy) {
        static WeeklyState from(ShieldStorage.WeeklyRow row) {
            UUID updatedBy = null;
            try {
                if (row.updatedByUuid() != null) updatedBy = UUID.fromString(row.updatedByUuid());
            } catch (IllegalArgumentException ignored) {
                // Keep old malformed metadata from preventing all Shield state from loading.
            }
            return new WeeklyState(ShieldSchedule.decode(row.currentSchedule(), row.currentPvp()),
                    row.pendingSchedule() == null ? null
                            : ShieldSchedule.decode(row.pendingSchedule(), Boolean.TRUE.equals(row.pendingPvp())),
                    row.pendingActivatesAt(), row.editLockedUntil(), updatedBy);
        }
    }

    private void log(int factionId, String action, UUID actorUuid, String details) {
        try { storage.insertLog(factionId, action, actorUuid == null ? null : actorUuid.toString(), details,
                System.currentTimeMillis()); }
        catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Failed to write Shield audit log.", error); }
    }
}
