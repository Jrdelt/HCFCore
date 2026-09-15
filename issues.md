# Vertex — unresolved issues

Updated 2026-09-13. **9 open or partially fixed findings.**
This is not a production sign-off or a completed every-file audit.

Only unresolved issues belong here. Completed fixes and their evidence are kept
in [audit progress](docs/audit-progress.md). Latest clean package: **809 tests,
808 passed, 1 existing MockBukkit skip, zero failures/errors**.
Cross-shard Paper transfer/crash testing remains required.

Priority: **P1** = duplication, loss, protection or shared-data risk;
**P2** = broken behavior/configuration. Source links identify the file;
the named method is the evidence anchor because line numbers change during repairs.

## Open findings

### ISS-11 [P1] TNT Wand use-count debit and bank-withdrawal compensation still lack a crash-safe journal

**Partial fix:** one player can own only one pending wand operation. The live session, original slot/item, faction and remaining use are rechecked; item movement and transfer snapshot capture are blocked while reserved. Both halves of a double chest, hopper moves and explosion removal are guarded. The source-material debit itself is now journaled: `WandManager.journalDebit`/`clearDebitIfCurrent` durably record the gunpowder/sand a settled conversion still owes its container *before* removal, and `WandManager.reconcileChunk` (driven by `ChunkLoadEvent`, plus an immediate pass at startup for already-loaded chunks) forces a survived entry back onto the container instead of trusting whichever state happened to reach disk. Covers both a chest (autosave-dependent) and a Chunk Collector (PDC-write-dependent). `WandDebitRecoveryTest` (5 tests) covers survived-debit replay for both container types, the already-reconciled no-op case, and immediate reconciliation of an already-loaded chunk at journal-load time.
- **Still open:** the wand item's own use-count debit (`WandManager.consumeUse`, a PDC write on the held item) is not journaled -- it is only as durable as the next player-data save, so a crash there could let a use survive uncounted. If revalidation fails, the bank-withdrawal compensation (`bank.withdrawTnt`) can still lose a race against another faction bank withdrawal.
- **Fix direction:** journal the use-count debit the same way as the material debit, keyed to the wand item rather than a block location. Give the compensation withdrawal a durable, idempotent operation id so a losing race is retried rather than dropped.
- **Evidence:** [WandListener.settleTnt](Vertex/src/main/java/me/vertex/core/wand/WandListener.java), [WandManager.consumeUse](Vertex/src/main/java/me/vertex/core/wand/WandManager.java).
- **Retest:** kill the server between a settled conversion's material removal and its use-count write; verify the use was still spent on restart. Race the revalidation-failure compensation withdrawal against a concurrent faction bank withdrawal.

### ISS-12 [P1] TNT deposit crash recovery has no automatic, exactly-once repair

**Partial fix:** TNT withdrawals now debit the faction and insert a player-owned delivery in the same SQL transaction. Full inventory/disconnect no longer triggers a capacity-sensitive bank refund. A failed deposit returns TNT only to the same ready session, otherwise uses the durable inbox. Cache-invalidation failure no longer turns a committed bank write into a reported failure. The duplication direction is now closed: `FactionBankMenu.depositTnt` journals the operation (`FactionBankManager.journalDepositIntent`, a synchronous fsynced WAL, `TntDepositWal`) before removing any source TNT, then calls `ClaimDelivery.checkpoint` (forces `Player#saveData()`, reusing ISS-08's fix) immediately after removal and before the bank credit is attempted -- so a crash after a committed credit can no longer restore the pre-removal inventory. The same checkpoint now also covers a direct-to-inventory refund on the failure path, closing the mirrored gap there. The journal entry is cleared once the credit or the refund is confirmed.
- **Still open:** the bank balance carries no per-operation receipt, so a deposit whose journal entry survives a crash (the process died before this JVM learned whether the credit or a refund had completed) cannot be safely auto-resolved -- crediting an already-credited deposit, or refunding an already-refunded one, would itself duplicate value. `FactionBankManager.replayDepositJournal` now reports the owner/faction/amount as a severe log for staff to reconcile against the actual balance and mail, instead of losing it silently, but reconciliation is still manual. If both refund WAL and SQL admission fail and inventory restoration is impossible, only a severe recovery log remains -- unchanged.
- **Fix direction:** a full fix needs the bank mutation itself to carry a durable per-operation receipt/idempotency key (write it inside the same SQL transaction as the credit, the way `DeliveryStorage.enqueueNew`'s stable IDs do for the withdrawal/delivery side) so a replayed operation can tell "already applied" apart from "never happened" and repair itself automatically.
- **Evidence:** [FactionBankMenu.depositTnt](Vertex/src/main/java/me/vertex/core/faction/FactionBankMenu.java), [FactionBankManager.journalDepositIntent/replayDepositJournal](Vertex/src/main/java/me/vertex/core/faction/FactionBankManager.java), [TntDepositWal](Vertex/src/main/java/me/vertex/core/faction/TntDepositWal.java).
- **Retest:** kill the server between the checkpoint and the credit landing (or between a direct refund give and its own checkpoint); verify the journal reports it on restart and the balance/inventory were not silently duplicated or lost. `FactionBankManagerTest` covers journal write/clear and that a survived entry is reported, cleared, and never auto-credited.

### ISS-13 [P1] Trade offer editing has an unsaved escrow window

Evidence: [TradeListener.onClick/onDrag](Vertex/src/main/java/me/vertex/core/trade/TradeListener.java), [TradeManager.persistEscrow](Vertex/src/main/java/me/vertex/core/trade/TradeManager.java).

- **Problem:** Bukkit changes the live inventory first. A next-tick task captures it and then queues a SQL snapshot; SQL errors are only logged. A crash between these stages, or an unsuccessful snapshot followed by a crash, leaves recovery with an older offer. Items moved into the offer can be lost; items returned to the player can be refunded again from old escrow.
- **Fix direction:** Journal/serialize offer mutations with their inventory ownership changes and keep uncertain edits unavailable until durable. Preserve operation IDs and failures for reconciliation; simply adding more async retries does not close the crash gap.
- **Retest:** Crash after adding/removing an item but before the next-tick snapshot, and fail the escrow write. Recovery must match the last completed ownership transfer.

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

### ISS-32 [P2] GC format propagation still has an initial chat-protection gap

**Partial fix:** code issuance publishes the existing network invalidation event; running shards merge persisted historical formats asynchronously. A ten-second refresh repairs missed events and never forgets old formats. Regression coverage verifies a second running manager learns the new format.

- **Still open:** propagation is eventual. Between first issuance in a new format and the remote refresh, that remote chat guard can still miss it.
- **Fix direction:** pre-register/version allowed formats network-wide before enabling issuance, or add an authoritative asynchronous chat admission check. Never query JDBC synchronously from chat handling.
- **Evidence:** [GcManager.refreshCodeFormatsAsync/issuedCode](Vertex/src/main/java/me/vertex/core/gc/GcManager.java), [GcChatProtectionListener](Vertex/src/main/java/me/vertex/core/gc/GcChatProtectionListener.java).
- **Retest:** issue the first new-format code on A and immediately post it on B before invalidation delivery. Keep code-generation settings aligned during rollout; the chat warning is not a secrecy guarantee.

### ISS-35 [P1] Finish cross-module incoming-transfer isolation and recovery fencing

**Partial fix:** joins freeze immediately, before the asynchronous handoff lookup. Admission checks use the exact live player session; delivery, auction, coinflip and trade claims wait for readiness. Coinflip/trade EXP payouts also defer while unavailable. Native mutation guards run early and transfers refuse active inventory reservations. Cursor/crafting inputs are settled without drops before snapshots; custom menus must finish first.

- **Still open:** direct/delayed inventory writers outside these paths still need the same admission boundary. Recovery/teleport acknowledgement must be crash-tested and fully fenced against disconnect/reconnect callbacks, including failed/uncertain SQL transitions. Inventory checkpoints remain ISS-08.
- **Evidence:** [NetworkManager.onJoin/acceptIncoming/recoverOrphan](Vertex/src/main/java/me/vertex/core/network/NetworkManager.java), [InventoryAccess](Vertex/src/main/java/me/vertex/core/storage/InventoryAccess.java).
- **Retest:** pause incoming lookup and acknowledgement; trigger staff restore, grants and delayed GUI returns; disconnect/reconnect before old callbacks run. Source/destination inventories must have one owner throughout.

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
9. Flight: land/disconnect/restart while slow falling, including with a legitimate potion active. The potion must remain; the flight-only effect expires within five seconds after renewal stops.
10. Shared data: two worlds named `world` on distinct shards, identical spawner/collector coordinates; verify ownership, F Top and reset isolation before any migration or release.

11. Shared events: `/haven admin event start` on A, earn Haven and Riftlands kills across A/B, then `/haven admin event stop` on B. Check combined scores, one durable winner set, restart recovery, and PvP Top refresh after remote awards/disband. Delay SQL: only the pending message may appear before commit; failure/no-op must not say started/stopped.
12. Fixed inventory cases: shift-click personal items while `/runes` is open; shift-right a rune category and confirm it opens the maximum-purchase confirmation. With `/invsee`, move the target's item before staff shift-click it; no second copy may reach staff, and the view should refresh.
