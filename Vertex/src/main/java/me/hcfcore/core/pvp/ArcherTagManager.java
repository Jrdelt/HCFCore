package me.hcfcore.core.pvp;

import me.hcfcore.core.factions.FactionsHook;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archer tag state: how many archer arrows a player is currently carrying,
 * and which factions those archers belonged to.
 *
 * <p>Deliberately faction-agnostic beyond storing ids -- the caller resolves
 * a player to a faction id through {@link FactionsHook} and passes it in, so
 * this class stays a plain in-memory tracker that can be tested without a
 * running Factions plugin.
 *
 * <p>Stacks are shared across every archer shooting the victim (that's what
 * makes focus fire hurt), but the faction ids are tracked per-faction so a
 * teammate's melee bonus only ever comes from their *own* archers' arrows.
 */
public final class ArcherTagManager {

    private final Map<UUID, Entry> tags = new ConcurrentHashMap<>();

    /**
     * Records an archer's arrow hit: adds a stack up to {@code maxStacks},
     * refreshes the expiry, and remembers the archer's faction as one that
     * gets the melee bonus.
     *
     * @return the victim's stack count after this hit
     */
    public int tag(UUID victimId, int factionId, int durationSeconds, int maxStacks) {
        if (durationSeconds <= 0 || maxStacks <= 0) {
            return 0;
        }
        long expiry = System.currentTimeMillis() + durationSeconds * 1000L;
        Entry entry = tags.compute(victimId, (id, existing) -> {
            if (existing == null || existing.isExpired()) {
                return new Entry(1, expiry, factionId);
            }
            existing.refresh(expiry, Math.min(maxStacks, existing.stacks + 1), factionId);
            return existing;
        });
        return entry.stacks;
    }

    /**
     * Total stacks on the victim from every archer, which is what scales
     * incoming arrow damage.
     */
    public int stacks(UUID victimId) {
        Entry entry = liveEntry(victimId);
        return entry == null ? 0 : entry.stacks;
    }

    /**
     * Stacks that {@code factionId} is entitled to for melee -- 0 unless one
     * of that faction's archers actually tagged this victim. A factionless
     * attacker never qualifies: {@link FactionsHook#NO_FACTION} is "no
     * faction", not a faction everyone shares.
     */
    public int stacksFor(UUID victimId, int factionId) {
        if (factionId == FactionsHook.NO_FACTION) {
            return 0;
        }
        Entry entry = liveEntry(victimId);
        return entry == null || !entry.factionIds.contains(factionId) ? 0 : entry.stacks;
    }

    public long remainingMillis(UUID victimId) {
        Entry entry = liveEntry(victimId);
        return entry == null ? 0L : entry.expiry - System.currentTimeMillis();
    }

    /** Death is a clean slate -- the tag doesn't follow a player past it. */
    public void clear(UUID victimId) {
        tags.remove(victimId);
    }

    /**
     * Housekeeping for a leaving player, dropping only what has already run
     * out. A live tag has to survive a disconnect, or logging out and back
     * in would be a free way to shed it mid-fight.
     */
    public void clearIfExpired(UUID victimId) {
        Entry entry = tags.get(victimId);
        if (entry != null && entry.isExpired()) {
            tags.remove(victimId, entry);
        }
    }

    /** Cleanup all expired entries in the map. Call periodically to prevent unbounded growth. */
    public void cleanupExpired() {
        tags.values().removeIf(Entry::isExpired);
    }

    private Entry liveEntry(UUID victimId) {
        Entry entry = tags.get(victimId);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            tags.remove(victimId, entry);
            return null;
        }
        return entry;
    }

    private static final class Entry {
        private volatile int stacks;
        private volatile long expiry;
        private final Set<Integer> factionIds = ConcurrentHashMap.newKeySet();

        private Entry(int stacks, long expiry, int factionId) {
            this.stacks = stacks;
            this.expiry = expiry;
            addFaction(factionId);
        }

        private void refresh(long newExpiry, int newStacks, int factionId) {
            stacks = newStacks;
            expiry = Math.max(expiry, newExpiry);
            addFaction(factionId);
        }

        private void addFaction(int factionId) {
            if (factionId != FactionsHook.NO_FACTION) {
                factionIds.add(factionId);
            }
        }

        private boolean isExpired() {
            return expiry <= System.currentTimeMillis();
        }
    }
}
