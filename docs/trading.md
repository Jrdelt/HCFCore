# Player trading

`/trade <player>` opens a direct two-player trade after the target accepts
the request. Players must be in the same world and within the configured
3D distance. `/tradetoggle` changes the persistent Trade Requests toggle in
`/settings`; `/trade cancel` queues both offers for durable return.

## Safety model

- A player may have only one pending request or active trade at once.
- The six-row GUI has separate, 16-slot offer grids. A player cannot click,
  drag, shift-click, double-click collect, drop, or edit the other side's items.
  Number-key/offhand insertion checks the actual incoming item against the blacklist.
- Items move into the shared trade escrow as soon as they are placed. An item
  offer becomes read-only when that player locks it. Money and XP are not
  tradeable in the current item-only trade system.
- When both sides lock, only the first player to lock can press the final
  accept button.
- If either inventory cannot receive the opposite items, the entire trade is
  cancelled before anything transfers. Disconnects, closing either GUI,
  moving out of range, blacklisted world/gamemode changes, inactivity, and
  plugin shutdown request cancellation. SQL failures retain the transaction for
  investigation/retry; this is not a guarantee of crash-safe item ownership.
- Current escrow snapshots are stored in SQL. Completion/cancellation converts
  both escrow sides into the correct durable recipient claims and writes the
  history row in one transaction before inventory delivery. Startup attempts
  recovery of unfinished escrow; shared-shard recovery still needs the
  ownership/lease fix documented in [issues.md](../issues.md).
- Claim rows use reservation tokens. They are deleted only after their tagged
  inventory copies are present. The remaining gap between the SQL acknowledgement
  and a durable Minecraft player save is tracked in [issues.md](../issues.md).

Right-click a shulker box or barrel in either trade grid to inspect its
contents. The viewer is read-only and returns to the trade on close.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/trade <player>` | `vertex.trade.use` | Request a trade. |
| `/trade accept <player>` | `vertex.trade.use` | Accept a pending request. |
| `/trade cancel` | `vertex.trade.use` | Cancel and refund the current trade. |
| `/tradetoggle` | Open to all | Toggle the same persistent Trade Requests setting shown in `/settings`. |
| `/trade payouts [key] [paid\|retry]` | `vertex.trade.payouts` | Inspect or reconcile an uncertain legacy money/EXP payout. |
| `/vertex reload` | `vertex.admin` | Reload Vertex configuration, including `traders.yml`. |
| `/tradelogs [all\|player]` | `vertex.trade.staff.history` | Open the server-wide or player-filtered audit history. |

Staff can use `vertex.trade.staff.bypassdistance` and
`vertex.trade.staff.bypassblacklist` for testing.

## Configuration

`plugins/Vertex/traders.yml` controls the distance, request/idle/cooldown
timers, GUI materials, and world, gamemode, or material blacklist. Bad
numerical or Material values are logged and replaced with safe defaults; run
`/vertex reload` after editing it.

Trade activity is written to `trade_history` for audit purposes. The staff
history menus query it with SQL pagination rather than loading all records at
once. Unfinished contents live in `trade_escrow`; `trade_sessions` records their
shard/process owner and terminal state. Completion/cancellation moves items
atomically into `trade_claims` before removing escrow rows. Terminal session
records remain as replay guards and must not be casually deleted.

## Shared-shard and single-server recovery

The existing `network.enabled` option controls deployment; no new trade mode or
preference storage is needed. Each storage writer uses the stable
`network.shard-id` plus a fresh process token. `trade_owners` holds a 120-second
database-time lease, renewed asynchronously by the existing trade sweep every
10 seconds. SQL locks fence old/expired processes on each save, settlement and
recovery. GUI editing/acceptance is blocked when local ownership is unhealthy.
Shutdown drains queued trade writes before releasing ownership; an uncertain
shutdown leaves it to expire. A duplicate live identity stops Vertex startup.

Restarting Spawn cannot refund a live trade on Factions. After its owning shard
restarts, a trade's two sides are recovered together from the latest durable SQL
contents. Recovery and normal completion retain a terminal record, so repeated
calls and delayed snapshots cannot issue a second reward or reopen the session.
Another shard never takes over recovery solely because an owner is offline.

**Upgrade all shards together in maintenance.** Old plugin versions do not obey
the new ownership protocol. Stop every backend, back up the database and worlds,
deploy the same JAR everywhere, and preserve existing shard IDs. After an unclean
stop, a restart can be rejected for up to 120 seconds; retry once the lease has
expired rather than renaming the server or deleting ownership records.

Legacy `trade_escrow` rows have no reliable shard identity. On a dedicated
single-server database, startup adopts and recovers them. In network mode they
are left untouched and their unresolved session count is logged. Do not blindly
assign them to whichever shard boots first. An offline, backed-up migration must
map each session to its actual shard using available logs/snapshots, then create
its `trade_sessions` association with state `OPEN` and a historical process token
(not a running process's token). If ownership cannot be established, preserve it
for investigation. No automatic migration or new staff command guesses this map.

The SQLite/MySQL storage migrator includes both ownership tables. It does not
rename their shard IDs or turn a standalone season into a multi-shard season.
Copied leases retain their expiry; allow that expiry before starting the migrated
destination. Keep proxy admission closed until Vertex has enabled successfully.

Remaining limits: this fixes cross-shard recovery and settlement replay, **not**
the live GUI-to-SQL crash window or inventory-save/claim-acknowledgement gap. See
ISS-13 and ISS-08 in [issues.md](../issues.md) and complete staging tests before release.

Old money/EXP credits from builds that allowed currency trading are migrated
atomically into the current payout outbox with deterministic IDs. A payout
whose external result cannot be proven remains reserved for staff review via
`/trade payouts`; it is never guessed or automatically paid twice.
