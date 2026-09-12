
### 3. Live storage migration is not a consistent cutover

**In plain terms**

A database copy can complete while the live server is still writing to old tables in places.

**Files**
- `VertexCommand.java`
- `VertexPlugin.java`
- `storage/StorageMigrator.java`

### Technical detail
Migration blocks logins and drains known writes, but normal commands/events and recurring schedulers (F Top, PvP Top, zone claim-expiry, market, collector, etc.) do not share a mutation barrier. They can write to source tables while others are copied. After copy, logins are reenabled even though the live server still uses old storage until restart; any write before restart can be missing from new backend.

The migration manifest now includes every durable SQL table, and migration draining no longer shuts down vault or delivery retry workers. These fixes alone do not solve the inconsistent cutover window.

### Required fix
- Add one global maintenance gate checked by every mutating command, listener, and scheduler.
- Acquire it before final drain.
- Copy a database-consistent snapshot.
- Verify row counts and checksums.
- Either hot-swap under gate or stop server immediately without reopening old backend.
- Release gate only on failed copy.

### Simple reason
Different tables can be from different moments and post-copy writes can be lost.

## High

### 4. Native claim ownership and Base/Raid metadata commit separately

**In plain terms**

Claim ownership and claim classification are not always written as one atomic action, so crashes can leave mixed state.

**Files**
- `factions/FactionService.java`
- `claims/ClaimEventListener.java`
- `claims/BaseClaimManager.java`
- `claims/RaidClaimManager.java`
- `claims/ClaimStorage.java`

### Technical detail
Native claim commit completes and publishes a Bukkit event first. A second async operation then adds/removes Base membership or Raid expiry. A crash or SQL failure between those steps can leave claim type/timer incorrect while user sees success. Startup reconciliation covers many local cases, but mismatch can persist until reconciliation happens.

### Required fix
- Commit ownership + Base/Raid classification in one storage transaction, or
- Add durable claim-operation journal in the ownership transaction.
- Keep claim unusable until worker completes operation.
- Update cache/success messages only after durable completion.

### Simple reason
Claim owner and claim type can disagree after a badly-timed failure.

---

### 5. Trade offer edits can still beat their escrow snapshot

**In plain terms**

Trade UI can show item movement before the database has safely recorded the final state.

**Files**
- `trade/TradeListener.java`
- `trade/TradeManager.java`
- `trade/TradeStorage.java`

### Technical detail
Completion and cancellation now settle asynchronously while session is locked, but Bukkit still moves offered item in/out of GUI before async escrow snapshot commits. A hard stop in that window can reload an older offer and lose/duplicate movement.

### Required fix
- Block normal GUI movement during settlement.
- Use durable per-edit operation ID.
- Persist source slot, destination slot, item identity, and prior escrow version before inventory change.
- Reconcile unfinished edits idempotently on startup before returning/exchanging items.

### Simple reason
The screen can change before DB knows which item actually moved.

---

### 6. Spawner stack mutations are not crash-atomic

**In plain terms**

Stack operations can partially apply: you may be paid without item removal or keep items without payout after a crash.

**Files**
- `spawner/SpawnerMenuListener.java`
- `spawner/SpawnerListener.java`
- `spawner/SpawnerManager.java`
- `spawner/SpawnerStorage.java`

### Technical detail
Stack withdraw/sell/break flows mutate age rows, block/PDC state, player inventory, F Top, and occasionally Vault in separate steps. Youngest-first behavior is correct but process stop can still desync these steps.

### Required fix
- Add persistent operation ID + state machine per stack mutation.
- Lock stack and transactionally reserve youngest rows.
- Commit stack/F Top change first.
- Complete idempotent item delivery or Vault payout.
- Block other stack actions while operation is pending.

### Simple reason
High-value spawners can duplicate or disappear during crash.

---

### 7. Several mixed economy operations still rely on compensation

**In plain terms**

Some economy flows still assume a rollback/refund can be done later, but the process may stop first.

**Files**
- `faction/FactionBankMenu.java`
- `faction/FactionBankManager.java`
- `faction/FactionUpgradeManager.java`
- `claims/BaseClaimManager.java`
- `shop/ShopManager.java`
- `wand/WandListener.java`
- `wand/WandManager.java`

### Technical detail
Faction bank, upgrades, Base slot purchases, shop trades, and wand flows touch SQL, Vault/XP, inventories, and containers. They compensate on normal failure, but JVM stop can occur before compensation. Auction, Coinflip, GC, and legacy trade now have durable intents; these systems do not.

### Required fix
- Persist unique operation states: `PREPARED/COMMITTING/COMPLETE/UNCERTAIN`.
- Make SQL/inventory/container actions idempotent.
- Route uncertain Vault/XP states to a permission-restricted staff reconciliation queue.
- Lock affected faction/container until completion.

### Simple reason
Refills/refunds only work if the server keeps running after the failure point.

---

### 8. A delivery-WAL rejection is not propagated to its caller

**In plain terms**

The queue can reject write-ahead logging, but callers may still remove or pay items.

**Files**
- `storage/DeliveryManager.java`
- `storage/DeliveryWal.java`
- Callers of `DeliveryManager.queueOverflow`

### Technical detail
Crash window is fixed with synchronous local WAL before async SQL and stable replay IDs. But the helper returns `void`, so callers ignore the admission result. If WAL cannot be written (disk full/read-only/corrupt), it logs and rejects batch after source item may already be removed or paid.

### Required fix
- Make WAL admission return immediate required result.
- Update all callers to remove payment only after admission succeeds.
- On admission failure, leave source untouched or do verified rollback.
- Treat failed WAL as degraded storage and fail new high-value overflow operations closed.

### Simple reason
Overflow survives normal crash replay, but a failed WAL write can still lose an item.

## Medium

### 9. Failed asynchronous state writes are not generally retried

**In plain terms**

Some async writes fail in logs but continue in memory, causing old values to reappear after restart.

**Files**
- `spawner/SpawnerManager.java`
- `collector/ChunkCollectorManager.java`
- `shop/ShopManager.java`
- `faction/FactionBankManager.java`
- `faction/FactionUpgradeManager.java`
- `faction/FTopManager.java`
- `faction/PvpTopManager.java`
- `mine/MineKothManager.java`
- `mine/HotZoneManager.java`
- `backpack/BackpackFilterManager.java`
- `staff/DeathManager.java`

### Technical detail
Most write paths are ordered, but final SQL exceptions are often logged and discarded while in-memory state moves on. On restart, older DB state can overwrite newer runtime state for balances, filters, spawners, market volume, leaderboards, or event state.

### Required fix
- Add bounded retry queue with backoff and monotonic versions.
- Expose degraded storage health to staff.
- Fail high-value mutations when persistence cannot keep up.
- Prevent older retries from overwriting newer state.

### Simple reason
A feature can appear correct now and still load as old data after restart.

---

### 10. Auction/Coinflip payout SQL runs on the server thread (status: fixed in this pass)

**In plain terms**

This is the same payout-threading issue that has already been fixed above.

**Files**
- `auction/AuctionManager.java`
- `auction/AuctionJoinListener.java`
- `coinflip/CoinflipManager.java`
- `coinflip/CoinflipJoinListener.java`

### Technical detail
`processPendingPayouts` previously loaded/reserved/released SQL rows from join/settlement callbacks on the primary thread, so slow MySQL could stall ticks.

### Required fix
Already addressed in `R10` with bounded executor handoff and asynchronous persistence.

---

### 11. Cross-process behavior has no automated integration harness

**In plain terms**

Current tests do not simulate multiple running servers, network failures, or process kills at scale.

**Files**
- `network/*`
- `teleport/*`
- `factions/*`
- `season/*`

### Technical detail
Mock and SQLite tests cover local logic and SQL transitions but not MySQL row locks, two Paper JVMs, Velocity forwarding, heartbeat loss, capacity races, process kills, or evacuation/handoff behavior.

### Required fix
- Add staging harness with Velocity + at least two Paper backends + MySQL + controlled network/process failure injection.
- Automate Part O scenarios from `addme.md`.
- Keep logs and DB snapshots as long-lived artifacts.

### Simple reason
Local unit tests do not reproduce real multi-server timing behavior.

## Staging tests for unresolved work

Use copied data only. Do not process-kill or reset production.

1. Kill a backend while adding/removing a trade offer and compare both player inventories, `trade_escrow`, and `trade_claims` after restart.
2. Kill a backend at each spawner sell/withdraw/stack step and verify stack age records, physical count, payout/item delivery, and F Top each happen exactly once.
3. Interrupt Vault during faction bank, upgrade, shop, and wand actions; verify no side is silently committed alone.
4. Make the Vertex data directory temporarily unwritable and attempt a full-inventory delivery; the source transaction must fail closed.
5. Run storage migration on staging while every mutating scheduler is active, then compare per-table checksums before and after the required restart.
6. With two Paper shards, race claim/unclaim against Base/Raid changes and verify every native claim has exactly one correct classification.
7. Exercise network handoff, destination failure, planned evacuation, season reset, and snapshot restoration with Velocity plus shared MySQL.


- This does not work | `/haven portal create <portal-id>` / `/riftlands portal create <portal-id>` | `vertex.portals.admin` | Creates a zone-specific portal directly from the current location. |


- This does not work | `/haven create <name>` / `/riftlands create <name>` | `vertex.zones.admin` | Define the active region and metadata for each zone type. |

- This does not work | `/haven list` / `/riftlands list` | `vertex.zones.admin` | Shows the currently defined zones for review/editing. |
## Latest requested fixes

The requested ticket-give removal, season-reset removal, stacked spawn countdown,
SandBot bank/persistence behavior, expanded block shop, ally chat, clickable
faction help pages, and unlimited downward lava source behavior were addressed
in the current pass. `/f tnt` already reports current/max capacity and faction
warp creation already uses the configured warp upgrade bonus.

### Haven/Riftlands command-map decision still needed

The obsolete commands are removed, but “full revamp” is broader than a code
fix. The remaining setup branches (`create`, `list`, `region`, `route`,
`portal`, `lootpool`, and `admin event/inspect`) need a final desired player vs
admin command map before they can safely be renamed, merged, or removed.

## Additional unresolved findings preserved during cleanup

### Critical — Shard rollback is not a complete point-in-time recovery

**Files:** `network/NetworkStorage.java`, `network/NetworkManager.java`,
`season/SeasonResetManager.java`, `staff/RollbackCommand.java`

The 30-minute player handoff history is not a coordinated snapshot of faction
membership and claims, banks, vaults, or world changes. `/rollback` restores a
player death inventory only; it cannot safely restore an entire shard after a
crash.

**Required fix:** Create coordinated database and world snapshots outside the
gameplay process, record their IDs in Vertex, and restore only in audited
network-wide maintenance mode.

**Simple reason:** Player transfers can recover, but the whole server cannot
yet return to one matching moment.

### Critical — Season reset lacks a network-wide write barrier and verified backup

**Files:** `season/SeasonResetManager.java`, `factions/FactionAdminCommand.java`,
`network/NetworkManager.java`

The reset checks for an empty network before starting asynchronous work, but a
player or scheduler can write immediately afterward. It also does not require
a verified complete season snapshot before purging data.

**Required fix:** Acquire a durable maintenance lease, reject joins and all
mutations, drain writers again, create and verify the snapshot, re-check that
the network is empty, then reset. Keep the barrier until all shards restart.

**Simple reason:** An empty-network check is only a moment in time; data can
still change while the reset runs.

### High — Shield activation can race Base Claim removal

**Files:** `claims/BaseClaimManager.java`, `claims/ClaimStorage.java`,
`shield/ShieldManager.java`, `shield/ShieldStorage.java`

Base removal checks the Shield before its separate SQL mutation. Another shard
can activate Shield after that check but before the removal commits.

**Required fix:** Lock and re-check the effective Shield state in the same
transaction as Base removal, or use one shared faction-operation lease across
all shards.

**Simple reason:** Two servers can both pass checks that should exclude each
other.

### High — Interrupted Auction and Coinflip creation intents lack staff reconciliation

**Files:** `auction/AuctionManager.java`, `auction/AuctionStorage.java`,
`coinflip/CoinflipManager.java`, `coinflip/CoinflipStorage.java`

An interrupted external-currency debit remains safely marked as `DEBITING`,
but staff currently need direct SQL access to decide whether the listing or
wager was actually charged.

**Required fix:** Add audited, permission-restricted `/ah intents` and
`/cf intents` list, inspect, and idempotent resolve commands.

**Simple reason:** The safe failure state exists, but staff cannot finish it
in-game.

### Medium — Legacy Trade, Auction, and Coinflip reward imports are not exactly once

**Files:** `trade/TradeStorage.java`, `trade/TradeManager.java`,
`auction/AuctionManager.java`, `coinflip/CoinflipManager.java`

Older pending money and XP rows use read/delete plus an external credit in
separate steps. A crash while importing an older production database can lose
or repeat a reward.

**Required fix:** Move legacy records into the current payout outbox with
deterministic operation IDs, then retire direct-delivery import paths.

**Simple reason:** Old pending rewards are the remaining path without the
newer reconciliation model.

### Medium — Blueprint integration depends on APIs marked for removal

**Files:** `blueprint/BlueprintManager.java`, `blueprint/BlueprintListener.java`,
`blueprint/BlueprintOutline.java`

The current build passes, but WorldEdit/JNBT APIs used by Blueprint are marked
deprecated for removal. A future FAWE or WorldEdit update can turn this into a
build or runtime failure.

**Required fix:** Move schematic coordinate and NBT handling to the supported
API for the pinned dependency version, then add a schematic import/build
compatibility test.

**Simple reason:** It works today, but future dependency updates are risky.
