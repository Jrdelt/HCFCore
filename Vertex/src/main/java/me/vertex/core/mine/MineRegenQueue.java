package me.vertex.core.mine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Mined locations waiting to regenerate, oldest-due first.
 *
 * <p>A 700x700 region holds 490,000 positions per layer, so regeneration has
 * to be driven by what was actually mined rather than by scanning the region,
 * and by one shared worker rather than a scheduled task per block. This is
 * that queue: a timestamp heap the worker drains in bounded batches.
 *
 * <p>Kept free of Bukkit so the ordering and batching guarantees can be
 * tested directly.
 *
 * <p>Not thread-safe by design -- it is only ever touched from the main
 * thread, where blocks are broken and placed.
 */
public final class MineRegenQueue {

    /** A queued location. World is a name so a reload cannot leave a stale World reference behind. */
    public record Key(String world, int x, int y, int z) {
    }

    private record Pending(Key key, long dueAtMillis) {
    }

    private final PriorityQueue<Pending> queue =
            new PriorityQueue<>((left, right) -> Long.compare(left.dueAtMillis(), right.dueAtMillis()));
    private final Set<Key> queued = new HashSet<>();

    /** Ignores a location already waiting, so one block cannot occupy the queue twice. */
    public void schedule(Key key, long dueAtMillis) {
        if (queued.add(key)) {
            queue.add(new Pending(key, dueAtMillis));
        }
    }

    public boolean isQueued(Key key) {
        return queued.contains(key);
    }

    public int size() {
        return queue.size();
    }

    /**
     * Up to {@code max} locations now due, oldest first.
     *
     * <p>Anything still due but over the limit stays queued for the next pass
     * rather than being dropped: a busy queue must never lose a location, or
     * that block would stay mined out forever.
     */
    public List<Key> drainDue(long nowMillis, int max) {
        List<Key> due = new ArrayList<>();
        while (due.size() < max) {
            Pending head = queue.peek();
            if (head == null || head.dueAtMillis() > nowMillis) {
                break;
            }
            queue.poll();
            queued.remove(head.key());
            due.add(head.key());
        }
        return due;
    }

    /** Drops everything for a world, for an unload or a region being deleted. */
    public void clearWorld(String world) {
        queue.removeIf(pending -> {
            if (pending.key().world().equals(world)) {
                queued.remove(pending.key());
                return true;
            }
            return false;
        });
    }

    public void clear() {
        queue.clear();
        queued.clear();
    }
}
