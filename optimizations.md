# Optimization opportunities

These are performance improvements, not required feature changes.

- **Update F Top by changed chunks.** Keep spawner totals by faction/chunk and recalculate only claims or stacks that changed. The scheduled check can verify cached totals instead of doing all the work again.

- **Index zones by world and chunk.** A zone lookup should first narrow to a small chunk bucket instead of checking every configured region.

- **Use one bounded database worker pool.** A limited queue prevents a burst of portal, zone, dupe, and economy saves from creating too much background work.

- **Make dupe scans incremental and measurable.** Scan a fixed small amount of work per interval, record duration, and slow down when server tick time rises.

- **Cache safe teleport spots.** Validate several safe entry points when a mine or zone is configured, then refresh only after its terrain changes.

- **Add performance counters.** Track queue depth, failed writes, active flights, pending claims, and scan duration so staff spot bottlenecks early.

- **Avoid full GUI redraws when nothing changed.** Reuse static filler items and redraw only balances, timers, and listing slots that actually changed.

- **Measure shared event traffic before a large rollout.** The new score ledger prioritizes safe ordering with a shared database lock. Test realistic kill rates on several shards, watch queued writes and settlement delays, then consider safe batching or narrower locks. Passing contention tests does not establish a player-capacity limit.
