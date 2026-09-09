package me.vertex.core.spawner;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A tracked, stacked spawner's mutable state -- everything but its location. */
public final class SpawnerData {

    private final EntityType mobType;
    /**
     * One placement timestamp per physical spawner in this stack.  Keeping
     * these separately is the foundation for F Top aging: a newly-added
     * spawner must not inherit the age of the stack it joins.
     */
    private final List<Long> placedAtMillis;
    /**
     * The faction tag that placed this spawner, recorded once at placement
     * time and never touched afterward -- used to tell "this claim is being
     * overclaimed out from under its actual owner" apart from "the owning
     * faction just re-ran /f claim on land that was already theirs", which
     * the current claim owner alone can't distinguish after the fact. Null
     * for spawners placed before this tracking existed.
     */
    private final String ownerFactionTag;

    public SpawnerData(EntityType mobType, int stackSize, String ownerFactionTag) {
        this(mobType, freshAges(stackSize), ownerFactionTag);
    }

    public SpawnerData(EntityType mobType, List<Long> placedAtMillis, String ownerFactionTag) {
        this.mobType = mobType;
        this.placedAtMillis = normalizeAges(placedAtMillis);
        this.ownerFactionTag = ownerFactionTag;
    }

    public EntityType mobType() {
        return mobType;
    }

    public int stackSize() {
        return placedAtMillis.size();
    }

    /** Adds newly placed spawners, each starting their own F Top aging clock. */
    public void addFresh(int amount) {
        long now = System.currentTimeMillis();
        for (int index = 0; index < Math.max(0, amount); index++) {
            placedAtMillis.add(now);
        }
    }

    /**
     * Removes the youngest individual spawners first, preserving the oldest
     * spawners (and therefore the most-earned F Top value) in the stack.
     */
    public int removeYoungest(int amount) {
        int removed = Math.min(Math.max(0, amount), placedAtMillis.size());
        if (removed == 0) {
            return 0;
        }
        placedAtMillis.sort(Comparator.reverseOrder());
        placedAtMillis.subList(0, removed).clear();
        placedAtMillis.sort(Comparator.naturalOrder());
        return removed;
    }

    /** Immutable snapshot used for persistence and leaderboard calculation. */
    public List<Long> placedAtMillis() {
        return List.copyOf(placedAtMillis);
    }

    public String ownerFactionTag() {
        return ownerFactionTag;
    }

    private static List<Long> freshAges(int stackSize) {
        int count = Math.max(1, stackSize);
        long now = System.currentTimeMillis();
        List<Long> ages = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ages.add(now);
        }
        return ages;
    }

    private static List<Long> normalizeAges(List<Long> ages) {
        if (ages == null || ages.isEmpty()) {
            return freshAges(1);
        }
        long now = System.currentTimeMillis();
        List<Long> normalized = new ArrayList<>(ages.size());
        for (Long age : ages) {
            // A corrupt/non-positive/future timestamp must never make a
            // spawner appear fully aged.  Reset just that entry safely.
            normalized.add(age == null || age <= 0 || age > now ? now : age);
        }
        return normalized;
    }
}
