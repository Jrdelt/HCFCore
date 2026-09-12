# Vertex Audit — Remaining Issues

Audit date: 2026-09-11

This report contains only unresolved findings after implementing the current
`addme.md` faction/network work. Fixed findings were removed.

## Critical

### 1. The 30-minute history is not a complete shard rollback

**Files:** `network/NetworkStorage.java`, `network/NetworkManager.java`,
`season/SeasonResetManager.java`, `staff/RollbackCommand.java`

Vertex retains source-authoritative player handoff snapshots for about 30
minutes. It does not retain coordinated point-in-time versions of faction
membership/claims, bank/TNT, faction vaults, or world changes. `/rollback` is
only a player's death-inventory history, so it cannot approve or restore a
whole shard after a crash as required by `addme.md` section 12.

**Required fix:** Coordinate timestamped MySQL and world snapshots outside the
gameplay process, record their IDs in Vertex, and add staff inspect +
approve/deny/restore controls guarded by `vertex.network.recovery`. Restoration
must happen in maintenance mode with every gameplay shard stopped. Never
auto-restore after a crash.

**Simple reason:** Player transfers can be recovered, but the complete server
cannot yet be rolled back to one matching moment.

### 2. Season reset has no network-wide write barrier or required backup

**Files:** `season/SeasonResetManager.java`,
`factions/FactionAdminCommand.java`, `network/NetworkManager.java`

The reset checks that all shards are empty, then starts an asynchronous SQL
transaction. A player can join or another shard can perform a scheduled write
after that check. The reset also begins without creating and verifying a full
season snapshot, so an operator cannot safely reverse an accidental reset.

**Required fix:** Acquire a durable network maintenance lease, make every
backend reject joins and all mutations, drain pending writers again, create and
verify a database/world backup, re-check zero players/transfers, then purge.
Keep the barrier until every shard restarts on the new season. Add a separately
confirmed, audited snapshot restore command.

**Simple reason:** An empty-network check is only a moment in time; another
server can write while the reset is running.

## High

### 3. Native claims and Base/Raid metadata commit in separate operations

**Files:** `factions/FactionService.java`, `claims/ClaimEventListener.java`,
`claims/BaseClaimManager.java`, `claims/RaidClaimManager.java`

A native claim/unclaim commits first and publishes a Bukkit event. The listener
then updates Base-region membership or the Raid expiry in a separate queued SQL
operation. A crash or database failure between those writes can leave a native
claim with no correct type/timer, or stale Base/Raid metadata after an unclaim.
The player may already have received success.

**Required fix:** Move claim ownership plus Base/Raid classification into one
storage transaction, or persist a durable claim-operation journal that startup
must finish before the claim becomes usable. Cache publication and faction
messages should happen only after the complete operation commits.

**Simple reason:** The land claim and its claim type can disagree after a badly
timed failure.

### 4. Shield can activate while a Base removal is committing

**Files:** `claims/BaseClaimManager.java`, `claims/ClaimStorage.java`,
`shield/ShieldManager.java`, `shield/ShieldStorage.java`

Base removal checks Shield through an in-memory predicate before starting its
separate SQL transaction. Another shard can activate the Shield after that
check but before removal commits. Durable role authorization is now rechecked,
but the durable Shield state is not locked/rechecked in the same transaction.

**Required fix:** Put Base removal and the current effective Shield check under
one database lock/transaction, or use a shared faction operation lease that
serializes Shield and Base mutations across shards.

**Simple reason:** Two servers can both pass their checks and perform actions
that should block each other.

### 5. Trade GUI changes are not persisted before Bukkit moves the item

**Files:** `trade/TradeListener.java`, `trade/TradeManager.java`,
`trade/TradeStorage.java`

The shared inventory changes immediately, then `persistEscrow()` snapshots it
asynchronously. A hard process stop in that gap can lose the newest offered
item. Completion/cancellation also waits up to five seconds for SQL from the
server thread, which can freeze ticks during database trouble.

**Required fix:** Cancel normal inventory movement and journal the exact slot
change before applying it to the GUI/player inventory. Settle asynchronously
while the session is locked, then publish the result on the main thread.

**Simple reason:** The screen can change before the database knows about the
item.

### 6. Spawner sale, withdrawal, and break are not crash-atomic

**Files:** `spawner/SpawnerMenuListener.java`, `spawner/SpawnerListener.java`,
`spawner/SpawnerManager.java`, `spawner/SpawnerStorage.java`

The stack's individual ages/count, physical block state, F Top dirty state,
inventory delivery, and Vault payout cross separate steps. A crash can pay
without removing a spawner or remove one without giving the item. Normal
youngest-first logic is correct, but it does not close this process-crash gap.

**Required fix:** Give each mutation a persistent operation ID and state. Lock
the stack, reserve/remove the youngest rows transactionally, then complete an
idempotent item delivery or a staff-reconcilable Vault payout before marking
the operation complete.

**Simple reason:** High-value spawners can duplicate or disappear if the server
dies during a transaction.

### 7. Several economy operations rely on in-memory compensation

**Files:** `faction/FactionBankMenu.java`, `faction/FactionUpgradeManager.java`,
`shop/ShopManager.java`, `wand/WandManager.java`, `wand/WandListener.java`

SQL, Vault, vanilla XP, inventories, and containers cannot share one database
transaction. Current code refunds/restores after a later failure, but a process
crash can happen before compensation. The new faction bank authorization check
prevents stale-role access; it does not make external Vault/XP operations
exactly once.

**Required fix:** Persist operation IDs with
`PREPARED/COMMITTING/COMPLETE/UNCERTAIN` states. Make SQL/inventory effects
idempotent. For Vault and XP, expose uncertain operations to audited staff
reconciliation instead of silently guessing.

**Simple reason:** A refund works for ordinary errors, but not if the JVM stops
between the charge and refund.

### 8. Delivery overflow is not durable before callers surrender items

**Files:** `storage/DeliveryManager.java` and callers of `queueOverflow`

Overflow first enters an in-memory retry collection and is inserted into SQL
asynchronously. Callers may already have removed, bought, generated, or
withdrawn the source item. A process stop before the insert loses the only
remaining copy.

**Required fix:** Require a successful durable inbox insert before source
removal commits, or synchronously append to a local write-ahead log that is
replayed on startup. Return success/failure to every caller.

**Simple reason:** Overflow is safe after SQL accepts it, but not during the
short enqueue window.

### 9. Interrupted Auction/Coinflip creation debits need staff commands

**Files:** `auction/AuctionManager.java`, `auction/AuctionStorage.java`,
`coinflip/CoinflipManager.java`, `coinflip/CoinflipStorage.java`

An interrupted external-currency debit correctly leaves a creation intent in
`DEBITING` instead of guessing. Startup logs it, but `/ah payouts` and
`/cf payouts` only reconcile result payouts. Staff still need direct SQL access
to decide whether a stuck listing/wager was actually debited.

**Required fix:** Add permission-restricted `/ah intents` and `/cf intents`
list/inspect/resolve commands. Resolution as debited or not-debited must be
idempotent and permanently audit logged.

**Simple reason:** The safe failure state exists, but there is no safe in-game
way to finish it.

### 10. Live storage migration does not freeze every mutating scheduler

**Files:** `VertexCommand.java`, `StorageMigrator.java`, `VertexPlugin.java`

Migration blocks joins and drains known writes once, but scheduled F Top, PvP
Top, Mine KOTH, Hot Zone, Raid expiry, market, collector, and other tasks can
enqueue newer source writes while tables are being copied.

**Required fix:** Reuse a global maintenance/write barrier checked by every
command, event, and mutating scheduler. Pause tasks, drain again, copy a
consistent snapshot, verify it, then resume only after success or failure.

**Simple reason:** Different copied tables can describe different moments.

## Medium

### 11. Failed asynchronous dirty-state writes are usually not retried

**Files:** `spawner/SpawnerManager.java`, `shop/ShopManager.java`,
`faction/FTopManager.java`, `faction/PvpTopManager.java`,
`mine/MineKothManager.java`, `mine/HotZoneManager.java`

Most writers serialize updates, but a final SQL exception is logged and then
discarded while live memory continues. A restart can reload old state.

**Required fix:** Keep the latest dirty version in a bounded retry queue with
backoff, expose degraded storage health to staff, and fail high-value changes
closed when persistence cannot catch up. An older retry must never overwrite a
newer state.

**Simple reason:** A feature may look correct until restart restores the old
database value.

### 12. Legacy Trade/Auction/Coinflip XP and money imports are not exactly once

**Files:** `trade/TradeStorage.java`, `trade/TradeManager.java`,
`auction/AuctionManager.java`, `coinflip/CoinflipManager.java`

Legacy rows use read/delete plus an external money/XP credit in different
steps. These are upgrade-only paths, but an older production database can
still contain them. A crash during import can lose or repeat credit.

**Required fix:** Migrate legacy rows into the current payout outbox with
deterministic operation IDs, then retire the old direct-delivery methods.

**Simple reason:** Old pending rewards are the last path without the newer
reconciliation model.

### 13. Blueprint integration uses APIs marked for removal

**Files:** `blueprint/BlueprintManager.java`,
`blueprint/BlueprintListener.java`, `blueprint/BlueprintOutline.java`

The build passes, but Java reports deprecated-for-removal WorldEdit/JNBT
coordinate and NBT stream APIs. A future FAWE/WorldEdit update can turn these
warnings into build or runtime failures.

**Required fix:** Move schematic coordinate and NBT access to the supported API
for the pinned FAWE/WorldEdit release, then add a real schematic import/build
compatibility test.

**Simple reason:** Nothing is broken today, but dependency upgrades are risky.

### 14. Multi-process behavior is not covered by the unit suite

**Files:** `network/*`, `teleport/*`, `factions/*`, `season/*`

MockBukkit verifies local logic and SQL transitions, but it cannot prove two
Paper JVMs, Velocity forwarding, MySQL locking, full-server capacity, heartbeat
loss, crash recovery, or restart evacuation. There are currently no automated
multi-process tests for the full Part O matrix in `addme.md`.

**Required fix:** Add a staging/integration harness with Velocity, at least two
Paper backends, MySQL, and controllable process/network failure injection. Run
the `addme.md` Part O cases before production release.

**Simple reason:** Local tests cannot reproduce real cross-server timing.

## Automated checks completed

- Clean unit suite: **657 tests, 0 failures, 0 errors, 1 skipped**.
- All **43** bundled YAML files parse successfully and have no duplicate keys.
- Documentation covers all **61** registered commands and all **95** live
  permission nodes; all local Markdown links resolve.
- Native faction role-sensitive bank, vault, claim, rename, disband, and create
  operations now re-check current shared SQL authorization before committing.
- Item-only Trade's unreachable money/XP anvil GUI code was removed; legacy
  escrow columns remain only for safe upgrade recovery.
- Fresh shaded JAR packaging completed successfully.

## In-game staging checklist

Use a copied database/world and two or more test accounts. For network tests,
run at least Spawn plus two base shards behind Velocity with shared MySQL.

1. Run `/f create Alpha`, invite a second account, and exercise all six roles
   through `/f permissions`. Demote the account from another shard while its
   bank or vault GUI is open; its next mutation must be rejected.
2. Run `/f claim 2`, `/f baseclaim`, disconnect a Base chunk, and complete both
   removal confirmations. Verify disconnected/removed chunks become Raid
   Claims with fresh timers and `/f map` hover shows the correct type/expiry.
3. Redeem each `/fpowerbooster` tier, die, wait through regen, and test enabled
   and disabled overclaiming around the exact current-power boundary.
4. Configure `/f shield`, test a cross-midnight window, edit lock/delay, the
   PvP toggle, zero Base Claims, and `/fa shield <faction> ... --force`.
5. Open `/f bank` and `/f vault` from two shards. Verify only one vault viewer
   network-wide and verify every money/XP/TNT action respects its role toggle.
6. Test `/f focus`, bans, ally/enemy/neutral requests, pending request expiry,
   leader departure, rename, and the two-confirmation disband flow.
7. Test `/spawn`, `/warp`, `/f home`, `/f warp`, and `/rtp` locally and across
   shards. Move, take damage, enter combat, fill/offline the destination, and
   change/delete the destination during countdown.
8. Run `/fa network shards`, create a queued transfer to a restarting shard,
   cancel a reboot during its DRAINING write, and verify the shard stays ONLINE
   with no evacuation. Then test planned evacuation Spawn -> Hub fallback.
9. Kill a backend process during PREPARED and LOADING handoffs. Verify no false
   combat death, no second transfer, source-authoritative recovery, staff alert,
   and manual `/fa network resolve` behavior.
10. On a disposable copy only, empty every shard and run
    `/fa season reset --force`. Verify permanent F Power max upgrades and global
    Spawn/warps survive while claims, faction progression, and cooldowns clear.

## Commands to rerun locally

```bash
cd "/Users/cesardeltoro-lemire/Desktop/Desktop - Cesar’s MacBook Pro/Kitmap Plugin/Vertex"
./mvnw clean test
./mvnw clean package
git diff --check
```
