# Velocity Shards and Cross-Server State

Vertex can run as one standalone Paper server or as shared gameplay backends
behind Velocity. Hub may remain a separate fallback without normal Vertex
gameplay state.

## Requirements and identity

Network mode requires `storage.type: mysql` and `network.enabled: true` on
every Vertex gameplay backend. Each backend needs a unique `network.shard-id`
that exactly matches its Velocity server name, plus a role, capacity, Spawn
shard, and Hub shard. SQLite remains supported only for standalone mode.

Every shared location stores shard ID, world, coordinates, yaw, and pitch.
World names alone are never treated as globally unique. Faction claims also
include their shard identity, so Base regions can exist on Star, Comet, and
Vertex without connecting across servers.

The shared MySQL tables are the durable source of truth. Vertex publishes SQL
invalidation events for live faction, claim, Shield, and location cache
refreshes; Redis is not required by this implementation.

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

1. Freezes mutable player state before capturing the source snapshot.
2. Persists a unique PREPARED handoff and one-transfer lock.
3. Requests the Velocity connection only after SQL accepts the snapshot.
4. Revalidates destination shard/world and feature-specific rules on arrival.
5. Applies the snapshot, teleports, and ACKs before releasing the lock.

Inventory, armor, offhand, Ender Chest, health/food, experience, game mode,
flight, selected slot, absorption, fire/air/fall state, and potion effects are
included. While PREPARED/LOADING, Vertex blocks item, inventory, command,
damage, and similar state mutations. An unresolved handoff blocks another
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
