# Performance

Vertex ships one small, purely diagnostic profiling framework —
`PerformanceManager` — rather than a separate optimization system for every
feature. It exists to answer "what's running, how often, and how long does
it take" when troubleshooting, and nothing else: it never changes gameplay
behavior, no matter what it's set to, and by default it does essentially
nothing.

## Monitoring levels

Configured in `performance.yml`'s `monitoring-level`, one of:

| Level | What it does | Overhead |
|---|---|---|
| `OFF` (default) | Monitoring is completely inactive. | None beyond a single cheap check per call site — no timer runs, nothing is recorded. |
| `BASIC` | Tracks which periodic/scheduled operations exist and how often they're configured to run. | Negligible — no per-execution timing. |
| `DETAILED` | Additionally times individual operations: how many samples have been taken, and the average/last/worst execution time seen. | Small, real per-call overhead — meant for active troubleshooting, not to be left on permanently. |

**`OFF` is a genuine no-op, not just "quiet."** Every instrumented call site
starts with a single volatile-field read; at `OFF` (and, for timing
specifically, also at `BASIC`) it runs straight through to the real work
with no clock read, no map lookup, and no allocation. There's no separate
"disabled" code path that could drift out of sync with the real one — the
check itself *is* the no-op.

Takes effect on `/vertex reload`.

```yaml
monitoring-level: "OFF"
max-tracked-labels: 200
```

`max-tracked-labels` (clamped to 10–2000) bounds how many distinct
`DETAILED`-level timing labels Vertex will keep statistics for at once — a
safety net against a runaway or dynamically-generated label set, not
something a normal server ever needs to touch. Every label Vertex times
today comes from a small, fixed set of call sites (see below).

## `/vertex performance`

Reports the current level, requires `vertex.admin` (the same permission
every other `/vertex` subcommand uses):

- At `OFF`, it just says so and points at `performance.yml`.
- At `BASIC` or `DETAILED`, it lists every registered scheduled/periodic
  task and its configured interval, how long monitoring has been active,
  and — at `DETAILED` only — each timed operation's sample count and
  average/last/worst execution time in milliseconds.

## What's actually instrumented

Per the framework's own "don't add unnecessary optimization complexity"
principle, Vertex does not retrofit timing into every existing manager —
that would be a large, unreviewed change for no real benefit on a server
that leaves monitoring off (the overwhelmingly common case). Only two
call sites report through it today, both natural fits for "an operation
that runs repeatedly and might one day need a closer look":

- **Chunk Busters' batched block removal** (`chunkbuster.batch-removal`) —
  the per-tick loop that drains a Chunk Buster's queued block removals. Its
  configured interval (`chunkbuster.yml`'s `processing.period-ticks`) shows
  up at `BASIC`; its actual per-tick execution time shows up at `DETAILED`.
- **The dupe-investigation reconciliation pass** (`dupe.reconciliation-pass`)
  — the periodic scan described in
  [Dupe investigation](dupe-investigation.md). Its configured cadence
  (`dupes.yml`'s `scan-interval-ticks`) shows up at `BASIC`; its actual
  execution time per pass shows up at `DETAILED`.

Any future feature can opt into the same two-line pattern — register its
interval once from its own `load()`, wrap its periodic work in
`PerformanceManager#time` — without needing any change to
`PerformanceManager` itself. A manager that is never wired to one (every
unit test included) falls back to `PerformanceManager.disabled()`, a
shared, permanently-`OFF` instance, so calling either API is always safe
even when nothing has wired a real one in.
