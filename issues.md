# Vertex audit — 2026-09-10

Scope: 353 production Java files, 70 test Java files, bundled configuration/language files, and repository documentation. mvn test passed during this audit. Its database-failure logs are intentional test paths, not test failures. This is a source audit; FactionsUUID, Vault, Paper, and each database backend still need staging-server smoke tests.

## Release blockers

### VTX-01 — Auction settlement has no durable delivery state

Evidence: AuctionManager.buy charges the buyer, then settle calls AuctionStorage.settleListing. That SQL transaction removes the listing and writes history before the buyer receives the item and before the seller's external money payout is confirmed. Overflow is dropped into the world (AuctionManager lines 314 and 498).

Failure path: A crash, failed Vault deposit, or despawned overflow drop can leave a permanently sold listing while the buyer, seller, or both never receive their entitlement. A retry cannot safely decide whether it already paid.

Required fix: In the same SQL transaction as the listing change, create idempotent buyer-item, seller-payout, and refund delivery records. Deliver only from that outbox/claim data, mark each record complete after success, retry at startup, and never make a world drop the only delivery copy. Check every Vault EconomyResponse for success.

Acceptance: Forced restart/failure at every settlement step creates no duplicate and loses no item, money, EXP, or GC.

### VTX-02 — Coinflip resolution can outlive its payout

Evidence: CoinflipManager.persistResolution calls CoinflipStorage.resolveCoinflip before winning currency/item claims are delivered. Similar ordering exists in cancellation/refund paths.

Failure path: Once the predetermined winner is committed, a crash or failed external payout can remove the open wager without a durable retryable record of exactly what the winner/refund recipient is still owed.

Required fix: Use OPEN -> RESOLVED -> DELIVERED transaction states. Persist immutable winner/refund entitlements in the same transaction that locks or resolves the flip. Deliver through idempotency keys and recover incomplete deliveries on startup.

Acceptance: Restarting during creation, acceptance, cancellation, animation, and payout produces exactly one final outcome and payout/refund.

### VTX-03 — GC write-behind is unsafe as auction/coinflip escrow

Evidence: GcManager.tryDebit changes memory and queues a database delta. Auction and coinflip accept this result as completed payment immediately (AuctionManager line 281; CoinflipManager line 591).

Failure path: If the queued GC write fails after another system grants an item/payout, compensation can restore GC while the other effect remains. A process crash before the write has the same unpaid-value risk.

Required fix: Create a durable GC reservation/ledger row before any dependent external transaction, then finalize or release it with an idempotency key. Recover in-progress reservations on startup; do not use cache-only debit as cross-system escrow.

Acceptance: Database failure/restart at each debit/finalize point cannot create, delete, or pay value twice.

### VTX-04 — Trade escrow is not durable at handoff

Evidence: TradeManager accepts items into memory and only then queues TradeStorage.replaceEscrow. Complete/cancel calls give(...) before final state persistence. give drops inventory overflow into the world.

Failure path: Restart after inventory removal but before escrow persistence loses items. Restart after delivery but before escrow clear restores old escrow and duplicates items. Drops can also despawn.

Required fix: Persist each accepted escrow revision before treating it as accepted. Complete/cancel by converting escrow to durable recipient claims in one SQL transaction, then deliver each claim idempotently. Block completion until the latest revision is durable.

Acceptance: Repeated forced restarts during offer edits, complete, cancel, and full-inventory delivery preserve each offered item exactly once.

### VTX-05 — Claim collection has a delete-before-delivery loss window

Evidence: Auction, coinflip, and trade claim flows consume/delete the durable claim before directly adding its items to an inventory. This blocks an easy replay but has no recovery after a hard stop between operations.

Failure path: A process termination in that gap permanently loses the serialized item because its only durable copy is gone.

Required fix: Use claim states such as PENDING, DELIVERING, and DELIVERED; retain the serialized item until acknowledged delivery. Attach a stable transaction ID and retry pending delivery after restart. Validate space first or leave the item in the claim GUI.

Acceptance: Crashing after every database/inventory step leaves an item either collectable or exactly once in its recipient inventory.

### VTX-06 — Zone player state writes race and can stall the server thread

Evidence: ZoneManager.state(UUID, String) calls ZoneStorage.loadPlayer synchronously inside computeIfAbsent from gameplay paths. Its persist method starts unrelated runTaskAsynchronously writes with no per-player ordering, tracking, shutdown drain, or migration drain.

Failure path: First zone interaction can block a server tick on SQL. Two quick updates can finish out of order and restore an older cooldown/session/score. A clean shutdown can close the database with queued state still absent.

Required fix: Preload zone player state asynchronously on join, serve all events from a main-thread cache, serialize writes per player/record, and expose awaitWrites() for shutdown/migration. Version snapshots so stale writes cannot overwrite newer state.

Acceptance: Delayed/out-of-order SQL tests and immediate shutdown after a zone action retain the newest state without main-thread database I/O.

### VTX-07 — Portal edits are fire-and-forget

Evidence: PortalManager.persist uses untracked asynchronous work. PortalManager.shutdown and VertexPlugin.awaitStorageWritesForMigration() do not wait for portal writes.

Failure path: Create, replace, or delete followed immediately by reload, migration, or restart can restore an old portal/route set even after staff see a success message.

Required fix: Queue portal and route mutations by ID, track futures, report the actual persistence error, and drain all writes before database close/migration. Only update the live cache after durable success, or compensate on failure.

Acceptance: Immediate restart/reload after every portal mutation reopens the exact saved definitions.

## High-priority correctness and protection issues

### VTX-08 — Zone terrain protection misses indirect block changes

Evidence: ZoneListener blocks player break/place and bucket fill/empty, but has no rules for pistons, explosions, fluid flow, dispensers, or structure growth across Haven/Riftlands boundaries.

Failure path: A player can change protected terrain indirectly or push a mechanism/effect from outside a boundary into it.

Required fix: Define allowed terrain behaviour and check both source and destination regions for piston extend/retract, block/entity explosions, fluid flow, dispenser placement, structure growth, and relevant dependency events. Add boundary tests for every protected event.

Acceptance: No unprivileged action can modify a zone block even when the player or mechanism begins outside the zone.

### VTX-09 — Dupe reconciliation is not periodic and can false-flag movement

Evidence: DupeManager.load reads/registers scan-interval-ticks, but it does not start a repeating task. A scan only starts after another event. A reconciliation cycle combines observations from multiple loaded-chunk passes over time.

Failure path: A quiet server never receives the promised periodic scan. A legitimate tracked item moved between containers/chunks during one cycle can be observed twice and reported as a duplicate with stale holder information.

Required fix: Start/stop a genuine repeating task at the configured interval. Use scan epochs and stable snapshots: scan evidence in one consistent window, invalidate/retry on holder change, or compare simultaneous observations. Keep event scans as debounced supplements.

Acceptance: A passive server scans on schedule, and moving one unique item during a scan never opens a dupe case.

### VTX-10 — Dupe scans miss nested high-value items

Evidence: Direct inventories, ender chests, tile inventories, and drops are inspected, but Backpack serialized contents, carried shulkers, and other nested storage can contain tracked items without being scanned.

Failure path: A duplicate tracked wand, armor piece, or blueprint can hide in a nested container and escape detection until a later transfer.

Required fix: Add read-only recursive scanners for Vertex serialized inventories and supported vanilla nested containers. Bound depth/size, record the full holder path, and avoid confusing the container's ID with an item's ID. Test backpack, shulker, collector, trade, and move scenarios.

### VTX-11 — Mine/Riftlands entry can select unsafe or invalid locations

Evidence: MineTeleportManager.destination uses exact region center plus getHighestBlockYAt, without validating headroom, a non-hazardous floor, or a nearby fallback. ZoneManager.findSafeTicketLocation uses a random range that throws when region width/depth is no larger than twice ticketBorder.

Failure path: Mine entry can put a player into a blocked/damaging center structure. A small but valid Riftlands region can throw from ticket use instead of returning the configured no-safe-location response.

Required fix: Share a bounded safe-location finder that verifies world, region, two passable blocks, and non-hazardous solid floor, then tries nearby candidates. Clamp/validate effective ticket border against dimensions before random selection.

### VTX-12 — PvP Top awards synchronously write to SQL

Evidence: PvpTopManager.awardCapture calls storage.award(...) directly from capture handling.

Failure path: Slow SQL can freeze the main thread during a KOTH/Outpost capture. A database error permits the capture but loses the point award without a durable retry record.

Required fix: Create an idempotent durable award event (type, event ID, faction) with a unique database constraint; process it asynchronously, retry pending awards at startup, and update memory after persistence succeeds.

### VTX-13 — F Top state is not drained before shutdown/migration

Evidence: FTopManager.persist serializes its writeChain, but exposes no awaitWrites(). VertexPlugin.onDisable and migration waiting omit it.

Failure path: A scheduled or forced recalculation immediately followed by a restart/migration can lose scores or the next-update deadline.

Required fix: Add a bounded awaitWrites() call to shutdown/migration, surface write failures, and test restart immediately after automatic and forced F Top updates.

### VTX-14 — Coinflip creation leaks wager details in public chat

Evidence: CoinflipManager passes summarize(created) to coinflip.public-created; summarizeItems includes every item/amount and money, EXP, and GC amounts are also included.

Failure path: Public chat reveals information that should be limited to the coinflip UI and staff logs. The intended message is creator plus coinflip type, not specific items or a value.

Required fix: Use a separate public type label (item, money, XP, GC) for broadcasts. Retain exact details only in the UI, transaction log, and authorized staff tools. Update every locale and test all wager types.

## Lower-priority follow-up

### VTX-15 — Non-English language files are far behind English

Evidence: en_us.yml has 1,228 lines; de_de.yml, es_us.yml, and pt_br.yml each have 884 and lack whole newer sections such as GC, dupe, zones, and portals. Runtime fallback prevents a crash but creates mixed-language messages.

Required fix: Treat English as the translation schema. Add a test reporting missing/unknown keys per locale, translate all missing messages, and increment language versions intentionally so bundled changes reach live installations.

## Documentation reconciled

- Added/indexed physical-portal documentation.
- Corrected player trading to item-only.
- Corrected Mine/Event documentation to describe the implemented event hub.
- Corrected Sell/TNT Wand activation to left-click.
- Documented GC as Auction House and Coinflip currency.
- Updated bundled non-English coinflip/Auction usage text for GC and xp.

## Required staging checks before release

1. Test Vault, FactionsUUID claims/ranks, SQLite, and MySQL integrations.
2. Force-stop/restart through each VTX-01 through VTX-07 transaction point.
3. Test zone borders with pistons, explosions, fluids, dispensers, and projectiles.
4. Test coinflips with both players online, disconnects, full inventories, and every currency type.
5. Check every bundled language after completing the translation follow-up.
