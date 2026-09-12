# Velocity Shards and Cross-Server State

Vertex can run as one standalone Paper server or as shared gameplay backends
behind Velocity. Hub may remain a separate fallback without normal Vertex
gameplay state.

## Requirements and identity

Network mode requires `storage.type: mysql` and `network.enabled: true` on
every Vertex gameplay backend. Each backend needs a unique `network.shard-id`
that exactly matches its Velocity server name, plus a role, capacity, Spawn
shard, and Hub shard. SQLite remains supported only for standalone mode.

Use the existing `config.yml` settings; there is no second deployment toggle:

| Deployment | `network.enabled` | `storage.type` | `network.shard-id` |
| --- | --- | --- | --- |
| One server, local storage | `false` | `local` | `standalone` (default) |
| One server, dedicated database | `false` | `mysql` | Stable single-server ID |
| Multiple shared-database shards | `true` | `mysql` | Unique Velocity backend ID on each server, e.g. `spawn`, `factions`, `mines` |

All network shards must use the same MySQL database/credentials. Do not point an
independent standalone server at that live network database. Shard IDs use 1–64
letters/digits, hyphens or underscores and are normalized to lowercase; network
mode rejects the default `standalone` placeholder. Misconfigured network mode
with local storage stops Vertex startup instead of creating divergent local data.

`/vertex reload` refreshes network timing/capacity settings but preserves the
running mode, shard ID and storage dialect in memory. Requested identity changes
remain in the file for a **planned restart**, with a warning. Do not rename a shard
or switch an existing single-server database into a network without an explicit
claim/location/escrow migration and backups; these are not automatic conversions.

Shared teleport locations store shard ID, world, coordinates, yaw, and pitch.
Faction claims also
include their shard identity, so Base regions can exist on Star, Comet, and
Vertex without connecting across servers.

**Known deployment limits:** this is not yet a network-production sign-off.
Spawner/collector block keys and F Top aggregation still have the cross-shard
isolation issues tracked as ISS-15/16 in [issues.md](../issues.md). Transfer
inventory/delivery ordering and season reset also have open findings there.
Do not infer that every module is shard-safe from the teleport/claim namespace.

Trade escrow now has a shard owner, per-process fencing token and SQL lease.
Only the owning shard's next process recovers its abandoned sessions. A second
process with the same live identity is rejected; after an unclean stop, allow up
to 120 seconds for its lease to expire before retrying startup. Never bypass this
by changing the shard ID. See [trade recovery and rollout](trading.md#shared-shard-and-single-server-recovery).

The shared MySQL tables are the durable source of truth. Vertex publishes SQL
invalidation events for live faction, claim, Shield, and location cache
refreshes; Redis is not required by this implementation.

## Persistence scope and current limits

The intended rule is **one durable gameplay identity across all enabled shards**,
not one unrelated player/faction save per backend. A shared database alone does
not make cached snapshots or world operations safe; each writer must also have
transaction protection or an explicit owner.

| Data | Intended ownership | Current implementation / remaining work |
| --- | --- | --- |
| GC balances/codes, economy ledgers, auctions, coinflips and investigations | Shared network records | Existing SQL storage; GC contention/settlement checks are tested. Item delivery and recovery still have open issues; this is not a blanket economy sign-off. |
| Factions, memberships, permissions, upgrades, banks and shields | Shared faction identity | Existing shared tables and invalidations. Keep physical claims associated with their shard. Bank item delivery still needs the listed safety fixes. |
| Player preferences and progression/cooldowns | Follow the player | Preferences refresh on reconnect. Zone progression/session snapshots still need cross-shard write fencing (ISS-42). |
| Mob Kill Event points and winner boosts | One combined network event | Idempotent SQL kill receipts, transactional global finalization and periodic authoritative refresh. Implemented and storage-tested in this pass. |
| PvP Top points | Shared leaderboard | Existing idempotent awards plus refresh after remote awards/disbands/reload and a 10-second reconciliation fallback. |
| Spawners, collectors, portals, routes and other placed/world data | Durable, but owned by the shard containing the world | Do not replicate blocks/entities to every shard. Spawner/collector keys need migration (ISS-15); zone geometry/flight recovery also lack shard-qualified identities (ISS-43). F Top needs network aggregation (ISS-16). |
| Inventories and active interactions | One active player/transaction owner | Persisted handoffs and trade ownership exist, but inventory/delivery admission and crash windows remain. Never merge inventory snapshots from two servers. |
| GUI viewers, live entities, task handles and performance counters | Local runtime state | Reconstruct from durable records when appropriate; do not share Bukkit objects or blindly serialize every cache. |

With `network.enabled: false`, these systems use the server's own SQLite or
dedicated MySQL database. This is an independent deployment, not a disconnected
copy that will automatically merge into a live shard network later.

### Shared events and leaderboard rollout

- Back up first and upgrade **all gameplay shards together** in maintenance.
  Older jars still contain the unsafe absolute score/winner writers. A rolling
  mixture of old and new jars is not supported.
- Use matching Haven `kill-event` cycle, duration, scoring and rewards on every
  backend. Configuration files are not automatically synchronized across hosts.
- The existing event score table and cycle anchor are retained. New
  `zone_event_runs`, `zone_event_score_ops` and `zone_event_winners` tables store
  event deadlines/rewards, replay receipts and finalized winners. The existing
  migration and season-table lists include them. Legacy player boost fields are
  used only until a finalized event ledger exists; stale player saves cannot
  overwrite ledger-backed rewards.
- `haven.yml` → `kill-event.shared-refresh-seconds` defaults to **2** (1–60).
  Scores/boost displays are eventually consistent within this polling interval
  when SQL is healthy, not instant cross-server broadcasts.
- `kill-event.settlement-delay-seconds` defaults to **5** (1–60), persisted per
  event. Pre-deadline kills can finish writing during this short grace window.
  Later writes are rejected and logged with player and operation IDs; they do
  not alter an already finalized result. SQL-committed scores survive restart;
  an abrupt process death before SQL commit can still lose queued kills.
- Finalization uses the database clock and global durable scores, including when
  an otherwise empty shard performs it. It catches up overdue persisted events
  after restart. Winner announcements go to local online players after a durable
  result is observed; chat delivery itself is not an exactly-once persistent inbox.
- Existing zone admin event start/stop now updates the shared event record. Stop
  closes the same event, preserving its scores, instead of changing its ID.
- `/pvptop` caches replace the entire score projection, so deleted faction rows
  disappear too. Invalidation failures recover through periodic reconciliation.

Two-Paper-server transfer, disconnect and process-kill scenarios are still
required before production approval. The issue list remains the release gate.

Staging checks for this pass:

1. Run `/haven admin event start` on A. Kill in Haven, transfer the same player
   to Riftlands on B, and kill again. After refresh, both scoreboards should show
   the combined score (default 2.5 for one kill in each zone).
2. Run `/haven admin event stop` on B. Both shards should stop the same event;
   after the settlement delay, verify the same Top 3 and boosts. Restart A and
   verify the committed result is unchanged. Repeat with an empty third shard.
3. Award objective points on A and check `/pvptop` on B; disband that test faction
   and verify its cached row disappears. Test invalidation interruption and the
   ten-second reconciliation fallback, then repeat event/restart checks in an
   isolated `network.enabled: false` server.

## Shard states

- `ONLINE`: accepts transfers.
- `DRAINING`: planned restart is approaching; new arrivals are blocked and
  players are evacuated.
- `RESTARTING`: planned restart is in progress and may expose an ETA.
- `OFFLINE`: cleanly unavailable.
- `CRASH_RECOVERY`: an unexpected stop requires manual inspection. Heartbeats
  cannot silently clear this state.

`network.max-players` defaults to 100. Full shards reject transfers without a
queue. DRAINING/RESTARTING destinations can queue eligible Spawn/warp/faction
teleports until the configured timeout. A transition to CRASH_RECOVERY cancels
those queued transfers.

The process supervisor must respect CRASH_RECOVERY. A plugin running inside a
new JVM cannot prevent an external service from restarting that JVM; operators
must keep the backend private until `/fa network recovery ready --force` has
been approved.

## Handoff flow

For a cross-shard teleport Vertex:

1. Applies the current transfer locks before capturing the source snapshot.
2. Persists a unique PREPARED handoff and one-transfer lock.
3. Requests the Velocity connection only after SQL accepts the snapshot.
4. Revalidates destination shard/world and feature-specific rules on arrival.
5. Applies the snapshot, teleports, and ACKs before releasing the lock.

Inventory, armor, offhand, Ender Chest, health/food, experience, game mode,
flight, selected slot, absorption, fire/air/fall state, and potion effects are
included. The current locks block many item, inventory, command and damage
events, but do not yet cover every incoming-join window or delayed delivery;
ISS-14 and ISS-35 describe these gaps and the missing cursor/crafting capture.
An unresolved handoff blocks another
transfer and alerts staff after the configured threshold.

If destination validation fails, RTP can reroll a safe point before state is
loaded. Other failed or interrupted handoffs preserve the source-authoritative
snapshot for recovery at Spawn. Ordinary `/spawn` never substitutes Hub; Hub
fallback is reserved for join/recovery/restart routing.

Completed handoff payloads and transfer-history snapshots are retained for
approximately 30 minutes, then pruned. This is player handoff recovery—not a
complete point-in-time backup of every faction, bank, vault, or world change.
Use database and world snapshots for full shard rollback. See
[issues.md](../issues.md) for the remaining full-rollback limitation.

## Shared teleports

| Command | Permission | What it does |
|---|---|---|
| `/spawn` | Open to all | Uses the configured five-second countdown, movement/damage/combat cancellation, destination revision checks, and shard-health gating. |
| `/spawn set` | `vertex.spawn.set` | Stores the primary first-join location. |
| `/warp [name]` | Open to all | With no name, opens the public warp GUI. A supplied warp name tab-completes and starts the configured destination countdown. |
| `/s warp set <name> [description]` | `vertex.server.warp` | Creates or updates a shared warp. |
| `/s warp delete <name>` | `vertex.server.warp` | Deletes a shared warp. |
| `/rtp` / `/wild` | Open to all | Opens shard/world random-teleport destination GUI with safety checks before completion. |
| `/f home` / `/f warp` | Native faction command roles | Uses the same cross-shard handoff path as other shared teleports. |

## Planned restarts

Configure backend restart times at least 30 minutes apart. The reboot manager
marks a gameplay shard DRAINING before shutdown and evacuates players to the
configured Spawn, then Hub if Spawn is unavailable. Verified evacuation
departures suppress combat-logout punishment; players cannot invoke that path.

If neither destination is available, the player is cleanly disconnected while
the restart continues. The source snapshot is persisted whenever a valid Spawn
handoff can be created.

## Administration

Useful command paths include:

| Command | Permission | What it does |
|---|---|---|
| `/fa network shards` | `vertex.fa.network` | Lists shard status and health checks. |
| `/fa network transfers` | `vertex.fa.network` | Shows transfer queue/history across shards. |
| `/fa network state <state> <timeout>` | `vertex.fa.network` | Manually applies a shard state (for example `draining 10m`). |
| `/fa network recovery ready --force` | `vertex.network.recovery` | Marks a shard as no longer in `CRASH_RECOVERY` after approval conditions are satisfied. |
| `/fa network resolve <transfer-id> ack --force` | `vertex.fa.network` | Reconciles an interrupted transfer as acknowledged. |
| `/fa network resolve <transfer-id> abort --force` | `vertex.fa.network` | Reconciles an interrupted transfer as aborted. |
| `/fa season reset --force` | `vertex.fa.season` | Runs destructive, one-shot season reset flow. |

`vertex.fa.network` permits normal network inspection/management.
`vertex.network.recovery` is the separate high-risk permission needed to view
and clear recovery state. `vertex.network.transfer.alerts` receives stale
handoff alerts. All `/fa` mutations are logged.

Season reset is console-only in normal operation, requires every gameplay
shard to report zero players and no active transfer locks, runs its table purge
inside one SQL transaction, publishes a reset event, and shuts gameplay shards
down so they reload clean state. Operators must still enforce a maintenance
barrier at the proxy/supervisor level; see the remaining reset-race note in
[issues.md](../issues.md).
