# Remaining Vertex Addons Work

GC Currency Integration is done: a self-hosted GC ledger with a balance GUI,
sign-based deposit/withdraw, staff-generated redeem codes, a player-facing
transaction log, full staff balance tooling (view/adjust/zero, all logged),
and GC as a third currency in Coinflips and the Auction House. See
docs/gc-currency.md.

## 3. Suspected-Dupe Investigation and Unique Item IDs

Build a staff-only investigation workflow; suspected items are retained by
their holder while investigated, never automatically deleted or confiscated.

- Create persistent dupe cases with an ID, time, holder UUID/name, suspected
  source, rollback/event context, and tracked-item ID where available.
- Alert authorized staff immediately, or queue the alert durably when no staff
  are online. On staff login, report the unresolved count.
- Add `/dupe inspect list` and detail/resolve/dismiss actions. All access and
  resolution actions require separate high-risk permissions and are logged.
- Keep all dupe metadata invisible to normal players, including item lore.
- Add an opt-in persistent item-instance ID framework for high-value custom
  items (initial targets: Sell/TNT Wands, Blueprints, custom armour). IDs must
  survive inventory, storage, trade, restart, and normal transfers.
- Detect the same tracked ID on two independent item instances and open a
  suspected-dupe case with the relevant location/holder evidence.
- If an item has a certian ntbtag id only staff can see it will show if duplicated or whatever is the best way to find duped items.
- Im unsure how well tag these items? Mayve every time an item is first introduced to the game do it on specific items like netherite armor tools, spawnewrs, chunk collecrtosa wands etc etc high value items.

## 4. F Top: Claimed, Individually-Aged Spawner Value

Replace power-based F Top with a separate Vertex leaderboard calculated from
spawners in valid faction claims.

- Track each physical spawner inside a stack separately: placed timestamp,
  current age/value, type, stack membership, and claim/faction association.
- A spawner reaches its configured full value over a configurable time. The
  stack value is the sum of its individual contributions, not one shared age.
- Unclaimed, Wilderness, Warzone, or wrong-faction locations contribute zero
  immediately. Revalidate against real current claims; never trust only a
  cached owner.
- Removing, selling, mining, or withdrawing a spawner removes the youngest
  individual first and resets that removed item's age to zero.
- Persist all records across restart and support existing stacks safely.
- Update dirty data through placement, removal, stack, and claim events; run a
  full validation every 10 minutes. Persist the next scheduled run so reboot
  does not reset the countdown. Run overdue checks shortly after startup.
- `/f top` must show the Vertex value leaderboard and time to next update.
  `/ftopforcecheck` must recalculate every faction immediately, be admin-only,
  and create an audit entry without moving the regular scheduled deadline.
- Include a deterministic rank tie-breaker and previous-rank change indicator.

### Spawner Stack GUI

Extend the existing right-click stack menu:

- Show type, count, combined current F Top value, full value, progress, and
  practical time remaining.
- Normal-click and shift-click actions withdraw/sell one or all respectively.
- Revalidate the live stack, reserve the transaction, persist the removal, and
  only then pay/give items. Inventory-full or stale-menu cases must do nothing.
- Refresh/close all stale views after a stack changes and mark its faction's F
  Top entry dirty.

## 5. PvP Top, Faction Stats, Logs, and Notifications

Create a leaderboard independent from F Top.

- `/pvptop` ranks factions by persisted PvP points. Configurable KOTH,
  Outpost, Artifact, and future objective awards feed it.
- Prevent allied/same-faction farming; handle disband, rename, merge, and
  reset intentionally. Every award/removal is logged with its source.
- Add staff inspection/modification commands with permissions and audit logs.
- Add `/f stats [faction]` for rank/value, PvP points, KOTH/Outpost results,
  K/D, spawner counts/value, TNT, upgrades, members, claims, and season age.
- Add durable, paginated `/f logs` with configurable faction-rank access for
  a faction's own non-sensitive events and separate staff access for any
  faction/staff-only events. Include transaction/event IDs and filters.
- Add `/f notifications` for authorized faction leaders. Initial categories:
  fully-aged spawner milestones, F Top rank changes, and TNT bank alerts.
  Preferences and one-time milestone state must persist and log changes.

## 6. Season Reset, Snapshot, and Restore

Implement an admin-only seasonal data lifecycle.

- `/vertex seasonreset` requires an explicit confirmation and a high-risk
  permission.
- Create and verify a uniquely identified full snapshot before purging any
  enabled backend. Abort the reset if backup fails.
- Snapshot/restore all seasonal Vertex state: faction upgrades/banks, F Top
  ages/ranks, PvP Top, cooldowns, pending Coinflip notices/history, wand and
  collector progression, dupe cases, faction logs/stats, and future event
  state.
- Add a separately confirmed high-level restore workflow by snapshot ID.
- Log initiator, timestamp, snapshot ID, outcome, and failure details.

## 7. Artifact Event and Events Hub

Add a scheduled Warzone Artifact event and a central `/events` GUI.

### Artifact event

- Select a validated, reachable Warzone spawn point; create exactly one
  authoritative, persistent Artifact instance per event.
- The Artifact cannot be dropped, placed, stored, traded, sold, auctioned,
  coinflipped, backpacked, or duplicated through normal inventory flows.
- Track cumulative personal and eligible-faction hold time server-side.
- On valid PvP kill transfer directly to the eligible killer; environmental
  death, disconnect, leaving Warzone, or invalid transfer respawns it safely.
- Block non-administrative escape teleports for the carrier. Log staff
  intervention and recover safely after restart without creating a second
  Artifact.
- Use deterministic leaderboard tiebreakers and independently configured Top
  3 faction/personal rewards. Faction PvP points and temporary rewards must
  flow through the normal persistent/audited services.
- Add live, same-world directional focus (`/artifact focus on|off`) with a
  persisted player preference and a configurable update interval.

### `/events`

- `/events` already displays live Mine KOTH owner/control/booster information
  and active/upcoming Hot Zone status from their authoritative managers.
- Add Artifact events to the hub when the Artifact module is implemented.
- Keep title, size, layout, item lore, refresh interval, and sounds in GUI
  configuration. Do not refresh every tick.


## 9. Remaining Coinflip Audit Detail

The result is already chosen server-side, durable, and revealed after the
shared animation timer. Extend the staff record only if required for incident
investigation:

- Treat the existing persistent Coinflip ID as the public transaction ID and
  expose a staff lookup by that ID.
- Record animation reveal deadline/completion, payout completion, and
  disconnect/reconnect delivery state alongside the existing wager/result
  audit data.
- Preserve a predetermined result through restart; do not reroll or refund a
  valid in-progress result.

## Acceptance Rules for New Modules

Every remaining module must provide:

- Dedicated configuration with validated defaults and clear console errors for
  invalid values; all normal player-facing messages/lore remain configurable.
- Narrow permission nodes, separate high-risk override permissions, and audit
  records for every override.
- Server-authoritative, idempotent transactions that revalidate live state at
  commit time and resist stale menus, double-clicks, packet spam, and restart
  boundaries.
- Persistent storage (SQLite/MySQL as configured), durable transaction/event
  IDs, and asynchronous I/O only where it cannot race synchronized item or
  balance changes.
- Bounded scheduled work and event-driven caches rather than world-wide scans
  every tick.
