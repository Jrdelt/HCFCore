# Faction Leaderboards

Two independent, Vertex-native leaderboards are available: **F Top**
(claimed spawner value) under `/f top`, and **PvP Top** (objective points)
under `/pvptop`. Both are independent of Vertex's native faction power.

Neither leaderboard reads the other. F Top never looks at PvP points, and
PvP Top never looks at spawner value.

## F Top (claimed spawner value)

F Top ranks factions by the value of their **tracked spawners** — the
buyable, stackable spawners covered in
[Spawners & Collectors](spawners-and-collectors.md) — sitting in land
they actually claim. This is a separate calculation from native faction
power.

### What counts

A spawner only contributes value while **all** of the following hold:

- It's a Vertex-tracked spawner (placed and stacked through the
  Spawners shop, not a vanilla spawner block).
- The chunk it sits in is currently claimed by a faction.
- That claim's current owner matches the faction tag recorded on the
  spawner when it was placed (`ownerFactionTag`). An overclaim
  transfers this tag to the new owner automatically — see
  [Claim integration](spawners-and-collectors.md#claim-integration) —
  so spawners keep contributing after the land changes hands; they just
  contribute to whoever holds the claim *now*, not whoever placed them.

Unclaimed, wilderness, or mismatched-claim spawners contribute **zero**
immediately — this is re-validated against the live claim every time F
Top is calculated, never trusted from a cache.

### Value & aging

Every physical spawner in a stack is tracked **individually** — each one
has its own placement timestamp. Stacking a fresh spawner onto an
existing stack does not inherit the older ones' progress; it starts at
age zero.

A single spawner's value ramps linearly from 0% to 100% of its full
value over `aging-seconds` (`ftop.yml`, default 1 day):

```
spawner value = full value × min(1, age / aging-seconds)
```

The stack's contribution is the **sum** of every individual spawner's
current value, and a faction's F Top score is the sum of that across
every qualifying spawner it owns. A stack's "full value" comes from
`ftop.yml`'s `values` map, keyed by entity type; a type missing from
that map falls back to its configured Spawners-shop purchase price
(`spawners.yml`) instead. Defining every enabled type explicitly is
recommended so a later shop price change never silently reshapes the
leaderboard.

Removing spawners from a stack (withdraw or sell, from the spawner's
management GUI) always takes the **youngest** individuals first,
preserving the oldest — and therefore most valuable — spawners in what's
left.

### Ranking and movement

Scores are sorted highest-value first; ties break deterministically by
faction ID (ascending) so the order never wobbles between recalculations
for equal values. Each entry remembers its rank from the *previous*
calculation, and `/f top` shows the change:

| Symbol | Meaning |
|---|---|
| `▲N` | Moved up N ranks since the last calculation |
| `▼N` | Moved down N ranks |
| `—` | Unchanged (also shown the first time a faction appears) |

### Commands

| Command | Permission | Notes |
|---|---|---|
| `/f top` | Open to all | Shows time until the next scheduled calculation, then each ranked faction: rank, name, value, and movement indicator. |
| `/ftopforcecheck` | `vertex.ftop.forcecheck` (default: op) | Immediately recalculates every faction's F Top value on demand. It does **not** move the regular scheduled deadline — the next automatic recalculation still lands on its original schedule. The command sender's name and the action are written to the server console log; there is no separate persisted audit table for this action. |

### Schedule & persistence

A full recalculation (scanning every tracked spawner) runs every
`update-interval-seconds` (`ftop.yml`, default 10 minutes). The
*deadline* for that next run — not just the interval — is persisted, so
a restart resumes the remaining countdown instead of resetting it; if
the server was down past the deadline, an overdue check runs shortly
after startup instead of waiting a full interval. Between full
recalculations, a lightweight timer only checks whether the deadline has
passed; it does not scan spawners or claims on every tick.

Scores and the next-run deadline are stored in dedicated `ftop_scores`
and `ftop_schedule` tables (MySQL or SQLite, whichever backend Vertex is
using) — entirely separate from Vertex faction power.

**Shared-shard limitation:** F Top still calculates from each backend's local
spawner index and overwrites the shared result. This is unresolved (ISS-16),
not the same implementation as the repaired PvP Top refresh below. See
[issues.md](../issues.md) before enabling shared production shards.

### Spawner Stack GUI

Right-clicking a tracked spawner with an empty hand opens its management
GUI, which shows that stack's live F Top contribution alongside the
usual stack size and unit price: current value and percent of full
value, the full value itself, and the time remaining until the
*oldest-remaining* spawner in the stack finishes aging (not the stack's
average).

### Configuration (`ftop.yml`)

| Key | Default | Meaning |
|---|---|---|
| `aging-seconds` | `86400` | Time for one spawner to reach 100% of its full value. |
| `update-interval-seconds` | `600` | Full recalculation cadence; the deadline persists across restarts. |
| `display-limit` | `10` | Number of factions shown by `/f top` (1–50). |
| `values` | per-type map | Full value per spawner of that entity type. Missing types fall back to the Spawners-shop price. |

## PvP Top (objective points)

PvP Top ranks factions by persisted points earned from PvP objective
captures. It is entirely independent of F Top — no spawner data feeds
it, and F Top never reads PvP points.

### What counts

Points are currently awarded for two capture types, defined in
`pvptop.yml`'s `awards` section:

| Source | Config key | Default points |
|---|---|---|
| KOTH capture | `awards.koth-capture` | 10 |
| Outpost capture | `awards.outpost-capture` | 5 |

Setting either value to `0` disables that award source entirely. Points
accumulate — they are never reset by this system on their own — and
every award is written to a persistent log alongside the faction it
went to, the amount, the triggering event's ID, and the capturing
player's UUID.

### Commands

| Command | Permission | Notes |
|---|---|---|
| `/pvptop` | Open to all | Shows each faction ranked by total points: rank, faction name, and point total. Ties break deterministically by faction ID (ascending). There is no movement indicator (unlike `/f top`) and no forced-recalculation or admin management command for PvP Top. |

### Persistence

Point totals live in a `pvptop_points` table, keyed one row per faction,
with a companion `pvptop_log` table recording every individual award
(faction, points, source identifier, actor UUID, timestamp) for later
auditing.

Awards use persistent operation IDs to prevent replay. When shard mode is
enabled, committed awards publish invalidations so other backends reload their
score projection. Faction mutations/disbands also request a refresh;
`/vertex reload` refreshes the live cache. A ten-second reconciliation pass
recovers missed invalidations. Refresh replaces the complete projection, so
removed factions do not remain in the cache. All periodic reads run off the
server thread. Standalone mode uses the same tables in its own database.

### Configuration (`pvptop.yml`)

| Key | Default | Meaning |
|---|---|---|
| `display-limit` | `10` | Number of factions shown by `/pvptop` (1–50). |
| `awards.koth-capture` | `10` | Points awarded to the capturing faction per KOTH capture. |
| `awards.outpost-capture` | `5` | Points awarded to the capturing faction per Outpost capture. |

See [KOTH & Outposts](koth-and-outposts.md) for how captures themselves
are triggered and resolved.
