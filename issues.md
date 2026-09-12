# Vertex — unresolved issues

Updated 2026-09-12. **23 open or partially fixed findings.**
This is not a production sign-off or a completed every-file audit.

Only unresolved issues belong here. Completed fixes and their evidence are kept
in [audit progress](docs/audit-progress.md). Latest verification:
**774 tests, 773 passed, 1 existing MockBukkit skip, no failures/errors**.
Cross-shard Paper transfer/crash testing remains required.

Priority: **P1** = duplication, loss, protection or shared-data risk;
**P2** = broken behavior/configuration. Source links identify the file;
the named method is the evidence anchor because line numbers change during repairs.

## Open findings

### ISS-07 [P1] Delivery markers do not fully lock items awaiting acknowledgement

Evidence: [ClaimDeliveryGuard](Vertex/src/main/java/me/vertex/core/storage/ClaimDeliveryGuard.java), [delivery acknowledgement failure](Vertex/src/main/java/me/vertex/core/storage/DeliveryManager.java).

- **Problem:** The guard covers inventory clicks/drags and dropping, but not placing, consuming or other direct use of a marked item already in the selected slot. During delayed/failed acknowledgement, an item can leave the inventory through use; retry then treats it as missing and supplies it again. Any marked item also grants blanket damage immunity until the marker is cleared, potentially for the duration of a database outage.
- **Fix direction:** Treat the handoff as an explicit restricted transaction. Block every relevant mutation/use path until ownership is resolved, with a bounded failure/reconciliation policy rather than indefinite gameplay immunity.
- **Retest:** Delay acknowledgement and attempt to place a delivered block, consume a delivered item, use a custom item, swap offhand, and take damage. No retry may recreate something already used.

### ISS-08 [P1] SQL claims can be deleted before the received inventory is durable

Evidence: [ClaimDelivery.add](Vertex/src/main/java/me/vertex/core/storage/ClaimDelivery.java), [DeliveryManager acknowledgement](Vertex/src/main/java/me/vertex/core/storage/DeliveryManager.java), [TradeManager claim acknowledgement](Vertex/src/main/java/me/vertex/core/trade/TradeManager.java).

- **Problem, crash window:** Adding an ItemStack/PDC marker changes the live inventory, not a durable player save. The code then permanently completes/deletes SQL claims without a durable receipt/player-state checkpoint. A crash after SQL completion but before player-data persistence can restore the old inventory with no remaining SQL claim to recover.
- **Fix direction:** Design the handoff around durable player state/receipts and explicit recovery states. An in-memory PDC mutation alone is not an atomic inventory/database transaction. Apply the solution consistently to delivery, trade, auction and coinflip collection.
- **Retest on Paper:** Terminate the process at each handoff stage, especially immediately after SQL acknowledgement and before a player save; verify both no loss and no replay duplicates.

### ISS-09 [P1] Spawner withdrawal/selling does not atomically debit the source stack

Evidence: [SpawnerMenuListener.withdraw/sell](Vertex/src/main/java/me/vertex/core/spawner/SpawnerMenuListener.java), [decreaseStack](Vertex/src/main/java/me/vertex/core/spawner/SpawnerManager.java), [queued persistence](Vertex/src/main/java/me/vertex/core/spawner/SpawnerManager.java).

- **Problem, crash/failure window:** Withdrawal durably queues the payout before decreasing the placed stack. Selling credits Vault before decreasing it. The decrease is applied to live block PDC and queued SQL, with no durable transaction linking source and reward. A crash or failed source save can retain the old stack while the withdrawal/payout survives.
- **Fix direction:** Journal a unique operation and source version; atomically record the stack debit with a durable payout entitlement where possible. Reconcile block/PDC state from the transaction on startup instead of independently trusting both.
- **Retest:** Interrupt withdrawal and selling before/after source persistence and chunk save. Total physical spawners plus remaining stack must be conserved, and a sale must pay once.

### ISS-11 [P1] One TNT Wand can start multiple uses before its last use is consumed

Evidence: [WandListener.onInteract](Vertex/src/main/java/me/vertex/core/wand/WandListener.java), [async runTnt](Vertex/src/main/java/me/vertex/core/wand/WandListener.java), [spendUse](Vertex/src/main/java/me/vertex/core/wand/WandListener.java).

- **Problem:** Only the clicked container location is locked. Wand use is consumed after SQL completes, using the originally captured ItemStack. While SQL is pending, the same one-use wand can activate other containers, or be moved/transferred. Completion does not revalidate its actual inventory slot/identity or remaining uses.
- **Fix direction:** Reserve the specific wand/use and lock it across the async transaction, with durable completion/refund handling. Revalidate the owner/session and item identity before committing. Canonicalize double-chest locks too.
- **Retest:** With delayed SQL and one remaining use, hit two different chests, then move/drop/trade the wand or disconnect. At most one conversion may succeed.

### ISS-12 [P1] TNT bank refunds/delivery can silently lose TNT

Evidence: [FactionBankMenu.depositTnt/withdrawTnt/giveTnt](Vertex/src/main/java/me/vertex/core/faction/FactionBankMenu.java).

- **Problem:** A failed deposit calls giveTnt but ignores false when the inventory filled meanwhile. A withdrawal that no longer fits queues a bank refund without checking its result; another deposit can have filled the capacity. Callbacks also use the captured Player without handling a disconnect/replaced session, so inventory delivery is not safely tied to the durable owner.
- **Fix direction:** Use durable, UUID-owned refunds/deliveries. Reserve bank capacity during compensation or record a guaranteed compensating credit; never discard a failed refund result.
- **Retest:** Fill inventory during a failed deposit; disconnect during withdrawal; fill the bank before a withdrawal refund. All TNT must remain either in the bank or in an explicitly recoverable delivery.

### ISS-13 [P1] Trade offer editing has an unsaved escrow window

Evidence: [TradeListener.onClick/onDrag](Vertex/src/main/java/me/vertex/core/trade/TradeListener.java), [TradeManager.persistEscrow](Vertex/src/main/java/me/vertex/core/trade/TradeManager.java).

- **Problem:** Bukkit changes the live inventory first. A next-tick task captures it and then queues a SQL snapshot; SQL errors are only logged. A crash between these stages, or an unsuccessful snapshot followed by a crash, leaves recovery with an older offer. Items moved into the offer can be lost; items returned to the player can be refunded again from old escrow.
- **Fix direction:** Journal/serialize offer mutations with their inventory ownership changes and keep uncertain edits unavailable until durable. Preserve operation IDs and failures for reconciliation; simply adding more async retries does not close the crash gap.
- **Retest:** Crash after adding/removing an item but before the next-tick snapshot, and fail the escrow write. Recovery must match the last completed ownership transfer.

### ISS-14 [P1] Cross-shard snapshots omit items on the cursor and in temporary inventories

Evidence: [NetworkManager.transfer](Vertex/src/main/java/me/vertex/core/network/NetworkManager.java), [restart evacuation snapshot](Vertex/src/main/java/me/vertex/core/network/NetworkManager.java), [PlayerStateSnapshot.capture](Vertex/src/main/java/me/vertex/core/network/PlayerStateSnapshot.java).

- **Problem:** Snapshot capture includes storage/armor/offhand/ender chest but neither cursor nor crafting/other temporary GUI items. Transfer and evacuation do not settle/close those views before capture. Closing them on departure can return items only to the old shard, after the authoritative snapshot has already been made.
- **Fix direction:** Before capture, safely settle or close inventory sessions and return cursor/crafting items to owned storage/durable overflow. Coordinate outstanding escrow/delivery callbacks so later writes cannot mutate an already-captured source inventory.
- **Retest:** Hold valuables on the cursor/in the 2x2 crafting grid when a cross-shard countdown finishes or a planned evacuation begins. Everything must arrive exactly once.

### ISS-15 [P1] Spawner and collector database keys collide across shards with the same world name

Evidence: [SpawnerStorage schema](Vertex/src/main/java/me/vertex/core/spawner/SpawnerStorage.java), [ChunkCollectorStorage schema](Vertex/src/main/java/me/vertex/core/collector/ChunkCollectorStorage.java), [spawner loading](Vertex/src/main/java/me/vertex/core/spawner/SpawnerManager.java).

- **Problem, shared MySQL:** Both tables identify a block using only world name and coordinates. Two supported shards with a world named world and a block at the same coordinates overwrite/delete the same record. Loading resolves every shared row against the current server's same-named world, so reconciliation can also mistake another shard's records for local blocks.
- **Fix direction:** Add stable shard identity to keys, queries and reconciliation, and provide an explicit legacy-data migration. Audit other block-location tables under the same rule.
- **Retest:** On two shards with matching world names/coordinates, place different spawners/collectors. Restart and remove one; the other must be unchanged.

### ISS-16 [P1] Each shard overwrites the shared F Top leaderboard with its local spawners

Evidence: [F Top startup](Vertex/src/main/java/me/vertex/core/VertexPlugin.java), [FTopManager.calculate](Vertex/src/main/java/me/vertex/core/faction/FTopManager.java), [FTopStorage.save](Vertex/src/main/java/me/vertex/core/faction/FTopStorage.java).

- **Problem, shared MySQL:** Every backend starts its own timer. Calculation uses that process's spawner index, which excludes worlds not loaded there. Saving deletes all ftop_scores and writes the local result plus the shared timer. A spawn/mine shard with no spawners can erase the factions shard's rankings; other processes also retain different cached results.
- **Fix direction:** Elect one durable calculation owner or aggregate shard-qualified durable contributions centrally. Publish one authoritative leaderboard/timer and invalidate reader caches.
- **Retest:** Run two shards with distinct worlds, one empty. Automatic checks and forcecheck from either must produce the same complete leaderboard without resetting each other's results.

### ISS-17 [P1] Season reset has no safe network-wide barrier or automatic restore snapshot

Evidence: [SeasonResetManager.reset](Vertex/src/main/java/me/vertex/core/season/SeasonResetManager.java), [reset transaction/table list](Vertex/src/main/java/me/vertex/core/season/SeasonResetManager.java), [admin reset handler](Vertex/src/main/java/me/vertex/core/factions/FactionAdminCommand.java).

- **Problem:** The command checks a cached empty-network snapshot once, starts an async purge, and only tells shards to stop after commit. There is no shared maintenance/join barrier, drain of all writers or backup-before-delete. New joins or queued/background saves can repopulate deleted data. SQL deletion alone also leaves recoverable world/PDC/local state outside this transaction.
- **Fix direction:** Require all shards to enter a persisted maintenance state and acknowledge drained writers; complete and verify a restorable snapshot before deletion. Define reset coverage for world/PDC/local state and block new writes until clean restart. Use a separate confirmation token, not only a repeatable --force flag.
- **Retest:** Race joins, queued bank/spawner saves and an active background operation against reset. Fail backup deliberately. No purge may start without a valid backup, and old state must not reappear afterward.

### ISS-26 [P2] Flight cleanup can remove an unrelated Slow Falling effect

Status: **partially fixed**. New portal/zone flight effects last five seconds and are renewed while tracked; logout/shutdown clears tracked effects. They can no longer remain effectively infinite after losing runtime tracking.

- **Remaining problem:** cleanup calls `removePotionEffect(SLOW_FALLING)` without identifying which effect is currently active or restoring an earlier legitimate potion. A stronger/longer effect applied independently during flight can be removed on landing. Old infinite effects from previous builds also have no persisted ownership marker for safe migration.
- **Evidence:** `PortalManager.clearSlowFall/tick` and `ZoneManager.clearSlowFall/tickSlowFalling`.
- **Plain English:** flight cleanup should end the flight bonus, not take away a normal potion.
- **Fix direction:** record effect ownership and any preexisting potion/expiry; remove or restore only the effect owned by the flight system. Decide a safe legacy-effect cleanup policy.
- **Retest:** apply a normal or stronger Slow Falling potion before/during a flight, then land, disconnect and reload. Its remaining legitimate duration must be preserved.

### ISS-32 [P2] GC chat guards do not learn newly changed formats on another running shard

Status: **partially fixed**. Local reload remembers old formats; startup also derives historical formats from persisted codes. Regression tests verify both paths.

- **Remaining problem:** each manager's format inventory is populated locally at load/startup. If shard A changes format and issues a code after shard B has already loaded, B does not automatically learn that format. Redemption still accepts the code, but B's chat guard can miss it.
- **Evidence:** `GcManager.load/loadState/recognizedCodeLengths`, `GcChatProtectionListener.containsUnescapedCode`. No network invalidation or refresh subscription updates the historical-format map.
- **Plain English:** changing code formats on one server can leave another server's accidental-code-sharing warning out of date.
- **Fix direction:** share/version code formats and refresh existing guards through the network invalidation system. Keep JDBC out of the chat-event thread.
- **Retest:** start both nodes, change only A's code format, issue a code and send it unescaped on B. It must be blocked while remaining redeemable. Until fixed, keep generation settings aligned and reload all shards together.

### ISS-35 [P1] Incoming transfer loading is not isolated from inventory edits and deliveries

Evidence: [NetworkManager.onJoin](Vertex/src/main/java/me/vertex/core/network/NetworkManager.java), [late incoming lock and snapshot application](Vertex/src/main/java/me/vertex/core/network/NetworkManager.java), [frozen-state predicate](Vertex/src/main/java/me/vertex/core/network/NetworkManager.java), [automatic delivery](Vertex/src/main/java/me/vertex/core/storage/DeliveryManager.java), [snapshot overwrite](Vertex/src/main/java/me/vertex/core/network/PlayerStateSnapshot.java).

- **Problem:** Join starts an asynchronous handoff lookup, but `frozen()` does not include that pending admission. `incomingTransfers` is set only after the later SQL state transition. During that interval a player can interact with the destination's existing inventory. Separately, DeliveryManager runs after 20 ticks and during retries, adds items and acknowledges their SQL records without checking network readiness. Applying the incoming/recovery snapshot then replaces the inventory wholesale. Source-side automatic deliveries after snapshot capture have the same missing coordination.
- **Evidence scope:** Source-confirmed ordering gap. A local snapshot probe confirms that an item added after capture disappears on apply; a complete two-Paper-server timing reproduction remains required.
- **Plain English:** Slow transfers can let players use stale items, or erase a newly delivered reward after the system has marked it received.
- **Fix direction:** Introduce/reuse one inventory-session admission barrier from the start of join through committed transfer/recovery completion. Defer item/XP deliveries and other inventory-mutating callbacks while a snapshot is frozen or not yet applied. Check the current login/session identity before delayed callbacks run.
- **Retest:** Delay incoming lookup and destination validation beyond one second while delivering a pending reward; spam drop/trade/use commands during loading. Also delay proxy departure while a source-side delivery finishes. No stale items may escape and no acknowledged reward may disappear.

### ISS-36 [P1] A rejected staff inventory edit can leave a duplicated item with the staff member

Evidence: [InvseeMenuListener.onClick](Vertex/src/main/java/me/vertex/core/staff/InvseeMenuListener.java), [deferred sync](Vertex/src/main/java/me/vertex/core/staff/InvseeMenuListener.java), [InvseeMenu.writeBack conflict handling](Vertex/src/main/java/me/vertex/core/staff/InvseeMenu.java).

- **Problem:** Native clicks move items between the detached `/invsee` GUI and the staff member before next-tick source validation. If validation then detects a changed target slot, it refreshes only the GUI; it never reverses the item already put on the staff cursor/inventory. If the target disconnects, sync simply returns. Bottom-inventory clicks also skip prevalidation entirely, even when a double-click collects matching items from the top inventory.
- **Plain English:** Rejecting a stale edit does not take back the copied item already handed to staff. In the opposite direction, staff can lose an item placed into an edit that is subsequently discarded.
- **Fix direction:** Cancel native cross-inventory transfers and validate/apply the target and staff inventory/cursor changes together on the main thread. Cover shift-click, collect-to-cursor, hotbar/offhand swaps and disconnects; do not rely solely on a later target-only refresh.
- **Retest:** Open `/invsee <test-player>`, have the target move/drop a diamond, then double-click a matching diamond in the staff member's bottom inventory. Also simulate a target mutation/disconnect between an allowed top click and the scheduled sync. Count both players' items, cursors and ground drops. The existing seven `InvseeMenuTest` cases exercise target-side snapshots, not this two-player transfer boundary; reproduce native click behavior on Paper.

### ISS-37 [P1] Shift-clicking into the Rune Shop can destroy the player's items

Evidence: [RuneShopMenuListener.onClick](Vertex/src/main/java/me/vertex/core/enchant/RuneShopMenuListener.java), [shop inventory construction](Vertex/src/main/java/me/vertex/core/enchant/RuneShopMenu.java), [confirmation inventory construction](Vertex/src/main/java/me/vertex/core/enchant/RuneShopMenu.java).

- **Problem:** Shop/confirmation protection checks the *clicked inventory's* holder. Clicking the player's bottom inventory returns without cancellation, allowing native shift-click to move ordinary items into empty top slots. These menus have no close-time recovery of deposited items. Closing or replacing the GUI discards them. The catalog and drag handlers already protect the whole view, but these click branches do not.
- **Plain English:** Accidentally shift-clicking a valuable item while shopping can lose it.
- **Fix direction:** Identify the menu from the top inventory, cancel cross-boundary actions across the whole view, then handle legitimate top-slot buttons. Apply this to both shop and confirmation holders without changing purchase behavior.
- **Retest:** In `/runes`, shift-click ordinary diamonds from the bottom inventory, then close or open the catalog. Repeat in bulk confirmation. Items must stay with the player. Also test double-click collection, hotbar swaps and drags on Paper.

### ISS-38 [P2] Shift-right-click opens the rune catalog instead of maximum-purchase confirmation

Evidence: [RuneShopMenuListener click ordering](Vertex/src/main/java/me/vertex/core/enchant/RuneShopMenuListener.java), [advertised shop controls](Vertex/src/main/resources/lang/en_us.yml).

- **Problem:** `isRightClick()` is checked before `isShiftClick()`. Shift-right-click satisfies both, so every non-Lucky-Gem product opens its catalog and never reaches the maximum-purchase branch. The lore explicitly advertises shift-right-click for maximum purchases.
- **Plain English:** The displayed “buy maximum” shortcut does not work for normal or Arena Runes.
- **Fix direction:** Handle shift-click first, or restrict catalog opening to ordinary right-click. Keep bulk confirmation and ordinary right-click catalog behavior intact.
- **Retest:** With enough balance and space for more than 64, shift-right-click every rune tier, Arena Rune and Lucky Gem. Confirm the maximum quantity, exact charge and delivery. Shift-left must still request up to 64; ordinary right-click must still open the catalog.

### ISS-39 [P2] An unaffordable GC coinflip reports that the listing disappeared

Evidence: [CoinflipManager.play failure result](Vertex/src/main/java/me/vertex/core/coinflip/CoinflipManager.java), [balance rejection handling](Vertex/src/main/java/me/vertex/core/coinflip/CoinflipManager.java), [result-to-message mapping](Vertex/src/main/java/me/vertex/core/coinflip/CoinflipMenuListener.java).

- **Problem:** The authoritative GC debit correctly rejects insufficient funds. However, `persistResolution` converts that typed rejection to `false`, and `play` restores the active listing while returning `GONE`. The player sees “That coinflip is no longer available” even though it remains available. This is a messaging defect, not evidence that the repaired debit permits overdrafts.
- **Plain English:** A player with too little GC gets the wrong explanation.
- **Fix direction:** Propagate a typed persistence/settlement outcome and map balance rejection to `CANNOT_AFFORD`. Preserve atomic SQL balance validation; do not restore the old cache-only affordability decision.
- **Retest:** Attempt to join a GC coinflip with an insufficient authoritative balance, including after spending GC on another shard. Show the existing cannot-afford message, retain the listing and leave both wagers unchanged. Distinguish a real removed listing and an actual SQL failure.

### ISS-40 [P2] Haven/Riftlands can display the wrong next kill milestone

Evidence: [ZoneManager.nextMilestone](Vertex/src/main/java/me/vertex/core/zone/ZoneManager.java), [milestone map conversion](Vertex/src/main/java/me/vertex/core/zone/ZoneManager.java), [progress GUI consumer](Vertex/src/main/java/me/vertex/core/zone/ZoneMenu.java), [placeholder consumers](Vertex/src/main/java/me/vertex/core/placeholderapi/VertexPlaceholderExpansion.java).

- **Problem:** `nextMilestone` takes the first larger key from a `Map.copyOf` map. That map guarantees neither numeric nor YAML insertion order. It can choose a later threshold instead of the nearest one, producing incorrect next-goal and kills-remaining GUI/placeholder values. Configuration order and JVM restarts must not determine progression display.
- **Plain English:** Players may be told they need far more kills than the actual next milestone requires.
- **Fix direction:** Select the minimum configured threshold greater than current kills, or use a numerically ordered immutable map. This finding concerns the displayed target; it does not establish incorrect reward grants.
- **Retest:** Define milestones out of order, such as 1000, 100, 500. At 0/100/500/1000 kills, expect next targets 100/500/1000/none and matching remaining counts, including after restart.

### ISS-41 [P2] Non-finite zone configuration values pass validation and can repeatedly break spawning

Evidence: [ZoneConfig validation/read](Vertex/src/main/java/me/vertex/core/zone/ZoneManager.java), [MobDefinition validation](Vertex/src/main/java/me/vertex/core/zone/ZoneManager.java), [weighted spawn selection](Vertex/src/main/java/me/vertex/core/zone/ZoneManager.java).

- **Problem:** Numeric validation uses `Math.max`/`Math.min` without finite checks. For example, `mobs.<id>.spawn-weight: .nan` survives `Math.max(0, weight)`. The enabled-weight sum becomes NaN, bypasses `total <= 0`, and reaches `ThreadLocalRandom.nextDouble(total)`, which rejects the invalid bound. Infinity, including an overflowing sum of individually finite weights, also fails. Similar non-finite values can survive route-speed, spawn-bias, health and loot-chance parsing.
- **Partial fix:** Shared-event scoring/reward configuration now rejects non-finite values with a warning and fallback; the spawning/health/loot paths described here still need repair.
- **Plain English:** One malformed number can stop zone mobs from spawning and repeatedly spam the console instead of falling back safely.
- **Fix direction:** Reject non-finite numbers before clamping, use safe defaults with a warning naming the exact config key, and validate the aggregate spawn weight before random selection. Keep invalid definitions from disrupting the rest of the zone tick.
- **Retest:** In disposable Haven/Riftlands configs, test `.nan`, `.inf`, negative weights, all-zero/disabled pools and huge finite weights whose sum overflows. Reload and trigger spawning. Invalid values must produce a clear configuration warning, no recurring task exception and safe behavior for valid remaining mobs. Add finite-value regression coverage alongside the current mob-budget test.

### ISS-42 [P1] Zone progression and session snapshots still lack cross-shard fencing

- **Evidence:** `ZoneManager.preloadPlayer`, `persistPlayer`, `setDeathCooldown`, `secureRiftSession`; `ZoneStorage.upsertPlayer`; `NetworkManager.transfer` captures a handoff without draining/fencing this module's writer.
- **Problem:** Kills, cooldowns and Riftlands session IDs are saved as whole player rows. Pre-login waits only for the destination JVM's write chain. A delayed old-shard snapshot can overwrite newer progression/cooldowns/session state after a transfer, or the destination can load before the source write completes. Event scores/boosts are now separately protected; general zone progression is not.
- **Fix direction:** Add a durable per-player owner/version tied to transfer admission; drain source writes before releasing ownership and reject stale owner versions. Use idempotent kill deltas and compare-and-set session transitions rather than broad absolute row replacement. Do not solve this with a second preference/player database.
- **Retest:** Delay shard A's zone save, transfer to B, earn kills or start/secure a Riftlands session, then release A's write. Verify totals never decrease, cooldowns cannot shorten and an old session cannot return. Repeat around disconnect/restart and SQL failure.
- **Simply:** Changing servers can still overwrite non-event farming progress with an older save.

### ISS-43 [P1] Zone geometry and flight recovery are not shard-qualified

- **Evidence:** `ZoneStorage.init/loadRegions/loadRoutes/saveFlightReturn`; `ZoneManager.loadState/completePlayerJoin`. `zone_regions` and routes use global IDs plus a plain world name; `zone_flight_returns` records region/world/coordinates without a shard.
- **Problem:** Every backend loads the same region/route records. Backends with a world named `world` can treat a remote zone as local; pending landings can resolve against the wrong world's same-named region. Merely persisting these rows globally does not identify the owning server.
- **Fix direction:** Add explicit world/shard ownership to geometry and recovery records and route cross-shard entry through NetworkManager. Migrate existing IDs with an explicit owner mapping; do not guess ownership from world names or clear old records.
- **Retest:** Give two backends identically named worlds/zone IDs at the same coordinates. Claim a zone on A; verify B cannot spawn its mobs, edit its geometry or apply A's pending flight landing locally.
- **Simply:** World-related saves need to remember which server they belong to.

### ISS-44 [P2] Mob stacking misses nearby stacks across grid cells

- **Evidence:** `MobStackListener.consolidateStacks` uses cells of `radius / 2` but checks only neighboring offsets -1 through 1.
- **Problem:** Two mobs can be within merge radius but two cell indices apart. With radius 6 and cell size 3, x=2.9 and x=6.1 are only 3.2 blocks apart but are never compared.
- **Fix direction:** Search offsets through `ceil(radius/cellSize)` and retain distance/source/type checks. Do not enlarge cells without validating within-cell distances, since current same-cell merging assumes all occupants are close enough.
- **Retest:** Test boundary coordinates on all three axes, negative coordinates, exact radius, different spawner sources and full stacks.
- **Simply:** Some mobs close enough to stack are overlooked, leaving extra live entities.

### ISS-45 [P2] Zone event administration reports success before SQL commits

- **Evidence:** [ZoneCommand.admin](Vertex/src/main/java/me/vertex/core/zone/ZoneCommand.java) sends `zones.admin-event-started/stopped` immediately after the void [ZoneManager.forceStartEvent/forceStopEvent](Vertex/src/main/java/me/vertex/core/zone/ZoneManager.java) queues an asynchronous write. Persistence failures retry later; they never change that staff confirmation.
- **Problem:** During a database outage or lock timeout, staff are told the event started/stopped although the durable schedule is unchanged. A late retry can execute well after the intended start, or an older start can be ignored after another admin has changed the shared anchor.
- **Fix direction:** Return a durable operation result/future; send success only after commit and shared-state confirmation. Distinguish pending, failed and superseded operations, with translated messages and an auditable operation ID. Keep JDBC off the server thread and revalidate the admin's current session before sending delayed feedback.
- **Retest:** Hold/fail the event write, issue start/stop from both shards, and then release the write. No success message may precede a committed applicable transition; old retries must not revive a stopped/superseded event.
- **Simply:** The command can say an event changed when the database has not actually saved the change.

## Required staging tests / commands

Use disposable worlds/databases and two Paper backends for network tests. Never crash or reset the live season to test these.

1. GC: `/gc admin set <test-player> 100`, `/gc withdraw 100`, `/gc redeem <code>`; race withdrawal and a GC auction/coinflip. Have two players redeem the same one-use code. Exactly one may receive it; no negative balance or unpaid reward.
2. Trades: `/trade <player>`; test locked offers, double-click, hotbar/offhand swaps, and right-clicking a filled shulker/barrel to preview. Regression-test ISS-04 on Paper by restarting only the other shard while the trade is active; it must not refund the trade. Crash/restart the owner, wait for its lease if needed, and verify one recovery only. Also test duplicate shard IDs, SQL outages, blocked GUI edits, and preserved legacy unowned escrow.
3. Delivery: fill the inventory, trigger a pending reward and reconnect. No ground drops; clear space and confirm one delivery only. Interrupt before/after SQL acknowledgement to investigate ISS-08.
4. Wands/bank: delay SQL, use one TNT Wand on two containers, move the wand, change collector contents/upgrades and disconnect. Also test `/f bank` with an inventory/bank that fills while its request is pending.
5. Claims/config: run a slow Chunk Buster, then change its claim owner; the next batch must stop. Use `/mines create <existing-mine>` or the configured mine KOTH selection flow; wrong-world KOTH corners must be rejected. Change each mine's hologram settings and run `/vertex reload`.
6. Hot Zones: enable early location announcements with two defined mines; the announced mine must start. Haven/Riftlands: set a small local mob target and `max-spawns-per-pass: 1`; check actual distances and per-pass spawn count.
7. Staff/settings: enable `/staffbuild`; authorized claim building should work, but rune placement and transfer locks must still block invalid actions. Rapidly change `/settings`, reconnect and check all saved toggles.
8. Network: hold items on the cursor/crafting grid during transfer; queue rewards while source/destination SQL is delayed; verify no stale inventory escapes or acknowledged rewards disappear.
9. Flight: land/disconnect/restart while slow falling, including with a legitimate potion active (ISS-26).
10. Shared data: two worlds named `world` on distinct shards, identical spawner/collector coordinates; verify ownership, F Top and reset isolation before any migration or release.

11. Shared events: `/haven admin event start` on A, earn Haven and Riftlands kills across A/B, then `/haven admin event stop` on B. Check combined scores, one durable winner set, restart recovery, and PvP Top refresh after remote awards/disband. Delay SQL and verify ISS-45's premature admin confirmation.
