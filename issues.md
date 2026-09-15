# Vertex — unresolved issues

Updated 2026-09-15. **28 open or partially fixed findings.**
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

### ISS-46 [P1] In-flight deposit inventory desync and duplication exploit

- **Evidence:** [`ResetVaultMenuListener.handleDepositConfirmMenuClick`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultMenuListener.java); [`ResetVaultManager.depositItem`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). The confirmation menu closes player inventory before `depositItem` begins. While asynchronous database persistence runs, the player can move, shift-click, or drop the item. In the post-commit Bukkit callback, `depositItem` detects the changed slot, logs an in-flight warning, commits `updateCache(updated)`, and leaves the item in the world.
- **Problem:** The item is credited to the player's vault record in SQL and cache, but never removed from the player's inventory or the world. A player can deliberately close or drop the item during the database write window to duplicate items infinitely.
- **Fix direction:** Lock or withdraw the item from the player's inventory synchronously on the main thread prior to dispatching the asynchronous database save. If the database transaction fails, refund the item to the player or register it with [`ClaimDelivery`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/claims/ClaimDelivery.java) if the inventory is full or the player disconnected.
- **Retest:** Artificially delay `ResetVaultStorage.savePlayerData`. Confirm a deposit and drop the item before the SQL query finishes. Verify the item cannot exist in both the vault and the world.
- **Simply:** Dropping or moving an item while depositing saves it to the vault without removing it from your inventory.

### ISS-47 [P1] Infinite item duplication via admin vault GUI cursor insertion

- **Evidence:** [`ResetVaultMenuListener.handleAdminVaultMenuClick`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultMenuListener.java). When an admin with an item on their cursor clicks an empty slot in `/rv open <player>`, `manager.adminInsert` clones the cursor item into the target's vault.
- **Problem:** The listener never decrements or clears the item on the cursor (`event.getView().setCursor(null)`). Clicking repeatedly inserts endless duplicate copies into vault slots without consuming the cursor item.
- **Fix direction:** Clear or decrement the cursor item synchronously upon successful insertion into the target vault.
- **Retest:** Open `/rv open <player>`, hold an item on the cursor, click multiple empty vault slots. Verify the cursor item is consumed on the first click.
- **Simply:** Inserting items into a vault via the admin GUI duplicates the item infinitely because the cursor is never cleared.

### ISS-48 [P1] Zero multi-shard synchronization for vault items and phase transitions

- **Evidence:** [`ResetVaultManager`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Vault contents, current phase, and item blacklists are kept purely in local memory caches with zero calls to [`NetworkManager.publishInvalidation`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/network/NetworkManager.java) or cross-shard invalidation listeners.
- **Problem:** When a player deposits or withdraws items on Shard A, Shard B retains stale in-memory cached vault items. A player transferring to Shard B can withdraw the same items a second time. Additionally, administrative phase changes (`/rv setphase`) or blacklist modifications remain local to one shard.
- **Fix direction:** Publish invalidation keys through `NetworkManager` for player vault changes (`"rv_vault:<uuid>"`), phase transitions (`"rv_phase"`), and blacklist modifications. Evict local player cache on receiving invalidations and re-query SQL with optimistic version locking.
- **Retest:** Deposit items on Shard A, switch to Shard B without server restarts; verify Shard B shows the deposited items, and withdrawing on Shard B prevents any subsequent withdrawal on Shard A.
- **Simply:** Vault contents and phase changes do not sync across servers, enabling cross-shard item duplication.

### ISS-49 [P1] Withdrawal item voiding on full inventory or player disconnect

- **Evidence:** [`ResetVaultManager.withdrawItem`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Space checks occur before the async SQL save. In the post-commit callback, `player.getInventory().addItem(toWithdraw)` is called without validating leftover items or active online connection.
- **Problem:** If the player receives an item or fills their inventory during the async DB write, `addItem` fails to place the item. If the player disconnects, `player.getInventory()` mutates a detached player instance whose state is discarded on quit. The item is deleted from `ResetVaultData` in the database, permanently destroying the player's item.
- **Fix direction:** Check `addItem` return values for leftovers and verify the player is still online. Route undelivered items through [`ClaimDelivery.enqueueItemDelivery`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/claims/ClaimDelivery.java) or roll back the database transaction before confirming deletion.
- **Retest:** Initiate a withdrawal with 1 inventory slot open, fill the slot before SQL completes or disconnect. Verify the withdrawn item is safely preserved in claim delivery or retained in the vault.
- **Simply:** If your inventory fills up or you disconnect while withdrawing, your items are permanently deleted.

### ISS-50 [P1] Main-thread synchronous SQL execution during admin vault editing

- **Evidence:** [`ResetVaultManager.adminInsert`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java); [`ResetVaultManager.adminRemove`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Both methods call `storage.savePlayerData(data)` and `storage.appendAuditLog(...)` synchronously on the calling thread (the Bukkit main thread during GUI clicks).
- **Problem:** Blocking SQL queries and serialization on the main thread cause server tick freezes and lag spikes under remote MySQL latency or database contention, violating the plugin's async persistence architecture.
- **Fix direction:** Refactor `adminInsert` and `adminRemove` to execute database writes asynchronously, identical to player deposits and withdrawals, with optimistic cached state or async GUI refreshes.
- **Retest:** Perform admin insertions and removals on a remote MySQL database with artificial latency. Verify TPS is unaffected and GUI interactions do not freeze the main thread.
- **Simply:** Admin vault operations run blocking database queries directly on the main thread, freezing the server.

### ISS-51 [P1] Unbounded container item transport via shulker box and bundle bypass

- **Evidence:** [`ResetVaultManager.checkEligibility`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Eligibility filters check custom backpacks and seasonal enchants, but do not inspect vanilla container items such as `SHULKER_BOX`, colored shulker boxes, or `BUNDLE`.
- **Problem:** Players can bypass slot limits and item restrictions by filling a Shulker Box with 27 stacks of prohibited or end-game equipment and depositing the container into a single reset vault slot, carrying massive inventories across season resets.
- **Fix direction:** Recursively inspect container block states (`BlockStateMeta`) or disallow all `SHULKER_BOX` variants and `BUNDLE` materials by default unless explicitly whitelisted in `config.yml`.
- **Retest:** Place high-tier equipment inside a Shulker Box and attempt to deposit it into the reset vault. Verify it is rejected by eligibility checks.
- **Simply:** Shulker boxes full of items can be deposited into the vault, completely bypassing slot limits.

### ISS-52 [P1] Silent data overwrite and vault loss on non-cached players

- **Evidence:** [`ResetVaultManager.getVaultData`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Returns `vaultCache.computeIfAbsent(uuid, id -> new ResetVaultData(id, 0, List.of()))`. `ResetVaultManager` has no player join listener to preload vault data from [`ResetVaultStorage.loadPlayerData`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultStorage.java).
- **Problem:** If a player has existing items in the database and the server restarts or they connect to another shard, their cache is empty. If they consume a token or an admin runs `/rv give`, the manager constructs a blank 0-item record and saves it, silently overwriting and wiping all previously stored vault items in SQL.
- **Fix direction:** Ensure `loadPlayerData` is queried asynchronously on player join or before opening/modifying any vault. Never blind-overwrite SQL records with blank cache defaults without reading storage first.
- **Retest:** Deposit items, restart the server, consume a vault token. Verify existing items remain intact in the database and menu.
- **Simply:** Using a vault token after a server restart overwrites and erases all previously saved vault items.

### ISS-53 [P1] Token consumption desync and permanent loss on SQL failure

- **Evidence:** [`ResetVaultTokenListener.handleTokenConsume`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultTokenListener.java). Decrements the player's held item and updates memory cache via `manager.updateCache(updated)` before calling `manager.storage().savePlayerData(updated)`.
- **Problem:** If `savePlayerData` times out or fails on the database connection, the error is only logged to console. No rollback or item refund occurs. On the next restart or cache eviction, the unlocked slot is lost while the physical token item is already gone.
- **Fix direction:** Decrement token count only after successful asynchronous database confirmation, or provide a transactional rollback/refund mechanism if the database write fails.
- **Retest:** Disconnect database connectivity and right-click a Reset Vault Token. Verify the physical token is not consumed if persistence fails.
- **Simply:** Using a token during a database hiccup eats the token without permanently unlocking the slot.

### ISS-54 [P1] Permanent backup drain deadlock on AFK player sessions

- **Evidence:** [`ResetVaultManager.executeBackup`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Sets `isDrainInProgress = true` and loops in `scheduleDrainCheck` waiting for `activeSessions` to reach 0.
- **Problem:** Active sessions are only removed when players close their vault menus. If any player is AFK with a vault open, the backup hangs indefinitely. Furthermore, `openVault` does not check `isDrainInProgress`, so other players can open new sessions, starving the drain check forever.
- **Fix direction:** Block new vault opens while `isDrainInProgress` is true. Add a timeout to force-close open vault menus and flush pending writes if sessions do not drain within a set duration (e.g. 5 seconds).
- **Retest:** Leave a vault open on an alt account and run `/rv backup <label>`. Verify the backup does not stall indefinitely.
- **Simply:** If a player is AFK with their vault open, `/rv backup` gets stuck in a permanent loop.

### ISS-55 [P2] Inconsistent configuration architecture and reload lifecycle

- **Evidence:** [`config.yml`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/resources/config.yml); [`VertexPlugin.reloadPluginConfig`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/VertexPlugin.java); [`ResetVaultManager.reloadConfig`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). Reset vault settings are embedded in `config.yml` rather than a dedicated `resetvault.yml`. `reloadConfig()` only re-reads phase and tokens; it ignores `map-label`, never refreshes access block holograms or blacklist database records, and lacks an `enabled` toggle.
- **Problem:** Configuration diverges from modular plugin standards (like `backpacks.yml`), cannot be cleanly disabled, and hologram text changes cannot take effect without a full server restart.
- **Fix direction:** Move reset vault settings to a dedicated `resetvault.yml` (or complete reload lifecycle in `config.yml`), add an `enabled` toggle checked across listeners/commands, and refresh block holograms and blacklists on reload.
- **Retest:** Change access block hologram lines in config and execute `/vertex reload`. Verify holograms update immediately in-game.
- **Simply:** Reset vault settings are inconsistent with other modules and `/vertex reload` does not update holograms or blacklists.

### ISS-56 [P2] Custom item display names corrupted by anti-injection zero-width spaces in chat

- **Evidence:** [`ItemDisplayNameResolver.resolve`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ItemDisplayNameResolver.java#L18); [`MessageFormatter.escapeForSubstitution`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/util/MessageFormatter.java#L71); [`ResetVaultManager.broadcastDeposit`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java#L792). When resolving custom named items (e.g. `&6Eagle Ascendant Boots`), `nameResolver.resolve` converts Adventure Components to legacy ampersand strings via `MessageFormatter.serialize`. When substituted into `messages.get(..., "item", itemName)`, `Messages.applyPlaceholders` routes all placeholder values through `MessageFormatter.escapeForSubstitution`, which replaces `&` with `&\u200B` (zero-width space) to prevent template injection.
- **Problem:** Because `\u200B` is inserted between `&` and color codes, `MessageFormatter.deserialize` fails to recognize formatting codes like `&6`. Broadcasts and notifications render literal unformatted text with visible unicode glyphs (e.g. `Reset Vault » vertex Volos_ has reset vaulted &[ZWSP]6Eagle Ascendant Boots.`), making it appear as though the plugin does not support custom-named items.
- **Fix direction:** Support custom item names by formatting messages with native MiniMessage tags or Adventure Components directly, or pre-color the template without passing color-coded strings into untrusted placeholder substitution.
- **Retest:** Deposit an item with a custom colored name (such as `&6Eagle Ascendant Boots`). Verify the broadcast displays gold colored text without `&` or `[ZWSP]` glyphs.
- **Simply:** Custom item names show broken color codes with visible [ZWSP] boxes in chat announcements.

### ISS-57 [P2] Blacklist editor GUI crashes on block-only materials

- **Evidence:** [`BlacklistEditorMenu.render`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/BlacklistEditorMenu.java). The menu constructs icons for blacklisted materials using `new ItemStack(mat)`.
- **Problem:** If a non-item material (e.g. `WATER`, `LAVA`, `PISTON_HEAD`, `AIR`) is added to the blacklist via command or database, Bukkit throws `IllegalArgumentException: Material is not an item`, crashing GUI rendering and closing the menu for admins.
- **Fix direction:** Check `mat.isItem()` before constructing `ItemStack`, falling back to `Material.BARRIER` with the material name in lore for non-item blocks.
- **Retest:** Blacklist `WATER` or `PISTON_HEAD` and run `/rv blacklist edit`. Verify the GUI renders safely.
- **Simply:** Blacklisting block-only materials crashes the admin blacklist editor GUI.

### ISS-58 [P2] Admin item selector coarse PDC matching over-blacklists entire feature sets

- **Evidence:** [`AdminItemSelectorMenu.buildBlacklistRule`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/AdminItemSelectorMenu.java). Checks generic PDC keys: if `ability_id` exists, it blacklists `ability_id`; if `wand_tier` exists, it blacklists `wand_tier`. Unmatched custom items fall back to generic vanilla materials (`PAPER`, `BOOK`).
- **Problem:** Blacklisting a single ability item or wand bans all abilities or wands server-wide. Unrecognized custom items cause standard vanilla materials to be blacklisted.
- **Fix direction:** Store specific key-value pairs (e.g. `ability_id=grappling_hook`) or item tags for blacklist rules rather than key presence alone, and distinguish custom items with display names from base vanilla materials.
- **Retest:** Select a specific ability item in the blacklist selector GUI. Verify other abilities remain eligible for deposit.
- **Simply:** Blacklisting one ability or wand in the admin menu accidentally blacklists every ability and wand on the server.

### ISS-59 [P2] Orphaned SOTW milestone event and broken automatic phase transition

- **Evidence:** [`SotwMilestoneSubscriber`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/SotwMilestoneSubscriber.java); [`SotwMilestoneEvent`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/SotwMilestoneEvent.java). `SotwMilestoneSubscriber` listens for `SotwMilestoneEvent`, but this event is never instantiated or dispatched anywhere in the plugin.
- **Problem:** Automatic transition from `LOCKED` to `WITHDRAW_ONLY` on SOTW start or milestone completion is dead code. Admins must remember to manually execute `/rv setphase` on SOTW day.
- **Fix direction:** Dispatch `SotwMilestoneEvent` from `SotwManager` when SOTW activates or ends, or subscribe directly to SOTW lifecycle listeners.
- **Retest:** Start SOTW via `/sotw start 1h`. Verify `ResetVaultManager` transitions phase automatically as configured.
- **Simply:** Automatic phase switching during SOTW is never triggered because the milestone event is never fired.

### ISS-60 [P2] Sub-menu navigation prematurely terminates active vault session

- **Evidence:** [`ResetVaultMenuListener.handleResetVaultMenuClick`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultMenuListener.java); [`ResetVaultMenuListener.handleInventoryClose`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultMenuListener.java). Opening [`EligibleItemsMenu`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/EligibleItemsMenu.java) or [`DepositConfirmMenu`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/DepositConfirmMenu.java) closes `ResetVaultMenu.Holder`, which calls `manager.endSession(uuid)`.
- **Problem:** Opening sub-menus destroys `VaultSession`, resetting `accessBlockId` to `-1`. If the vault was opened from a physical block, other players can break or claim the physical block while the player is still confirming a deposit in the sub-menu.
- **Fix direction:** Retain session state across sub-menu transitions or share a common inventory holder interface so physical block locks remain held until the player exits all vault GUIs.
- **Retest:** Open the vault at an access block, navigate to "Eligible Items". Verify the session remains active and the physical block cannot be broken by another player.
- **Simply:** Clicking sub-menus inside the vault ends your session early, allowing other players to break the vault block you are using.

### ISS-61 [P2] `/rv status` object string formatting bug and incomplete tab completion

- **Evidence:** [`ResetVaultCommand.handleStatus`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultCommand.java); [`ResetVaultCommand.onTabComplete`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultCommand.java). `handleStatus` prints `"Active physical access blocks: " + manager.storage()`. In `/rv give` and `/rv token give`, leftover items from `player.getInventory().addItem()` are ignored. Tab completion stops at argument length 2, missing player completion for `/rv token give <player>`.
- **Problem:** `/rv status` displays raw Java object references instead of block counts, staff commands void tokens on full player inventories, and player name tab completion is missing.
- **Fix direction:** Output actual access block counts, route leftover token items to claim delivery or ground drop, and extend tab completion to suggest online players for argument 3.
- **Retest:** Run `/rv status` and verify integer count; run `/rv token give ` and press Tab to verify player names autocomplete.
- **Simply:** `/rv status` prints raw Java object text and `/rv token give` is missing player name tab completion.

### ISS-62 [P2] Backup crash flag never persisted to SQL and non-advancing undo state machine

- **Evidence:** [`ResetVaultManager.executeBackup`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java); [`ResetVaultStorage.load`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultStorage.java); [`ResetVaultManager.executeUndo`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultManager.java). `executeBackup` never writes `BACKUP_RUNNING` to SQL, rendering crash detection in `load()` dead code. In `executeUndo`, restoring a `PRE_RESTORE` backup does not mark or advance the record, allowing repeated undos to cycle the same snapshot.
- **Problem:** Server crash recovery during backups is completely non-functional, and repeated undo executions can corrupt or cycle vault state unpredictably.
- **Fix direction:** Persist backup state to SQL before snapshotting, and advance/resolve `PRE_RESTORE` records upon successful undo.
- **Retest:** Terminate server process during a backup; verify startup detects the interrupted backup and cleans up state cleanly.
- **Simply:** Backup crash recovery never triggers because the running state is never saved to the database.

### ISS-63 [P2] Physical access blocks lack placement permission checks and entity protection

- **Evidence:** [`ResetVaultBlockListener.onBlockPlace`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultBlockListener.java); [`ResetVaultBlockListener`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultBlockListener.java). `onBlockPlace` checks PDC keys but does not check `vertex.reset.admin` or `vertex.developer`. The listener handles `BlockBreakEvent` and `BlockExplodeEvent`, but misses `EntityChangeBlockEvent`.
- **Problem:** Non-admin players who obtain an access block item can place permanent reset vault blocks. Furthermore, Endermen and Withers can pick up or destroy the block without triggering cleanup, leaving orphaned floating holograms.
- **Fix direction:** Enforce admin permissions in `onBlockPlace` and listen to `EntityChangeBlockEvent` to cancel entity alterations to access blocks.
- **Retest:** Place an access block without admin permissions; verify it is denied. Attempt to destroy an access block with a Wither or Enderman; verify it is protected.
- **Simply:** Non-staff can place access blocks if they obtain the item, and Endermen or Withers can grief them into ghost holograms.

### ISS-64 [P1] Virtual GUI item cloning triggers false-positive dupe detector alerts

- **Evidence:** [`DepositConfirmMenu`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/DepositConfirmMenu.java#L50); [`EligibleItemsMenu`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/EligibleItemsMenu.java#L54); [`ResetVaultMenu`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/resetvault/ResetVaultMenu.java#L75); [`DupeListener.onClick`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/dupe/DupeListener.java#L54); [`DupeManager.scanInventorySoon`](file:///Users/cesardeltoro-lemire/Minecraft%20Projects/Vertex%20HCF%20Core/Vertex/src/main/java/me/vertex/core/dupe/DupeManager.java#L174).
- **Problem:** Tracked high-value items (such as custom armor and weapons) carry a unique persistent UUID in their PDC (`identityKey`). When rendered in Reset Vault preview or candidate selection GUIs, the menus clone the `ItemStack`s directly into virtual inventory slots (`Bukkit.createInventory`). When a player clicks inside the GUI, `DupeListener.onClick` passes `event.getView().getTopInventory()` to `scanInventorySoon`. Because `DupeManager` does not check whether the top inventory is a virtual plugin GUI rather than a physical world block, it treats the GUI as an authoritative world container while simultaneously scanning the player's inventory. Observing the identical UUID in both locations flags a false-positive duplication exploit, opening cases (e.g. `case 212a6f0c...` and `case 807f6b3f...` during deposit of custom boots such as `Eagle Ascendant Boots`) and broadcasting `DUPE > Suspected duplicate detected` alerts to staff during legitimate vault operations.
- **Fix direction:** Strip the `dupe_identity` PDC tag from GUI preview/display clones, or update `DupeListener`/`DupeManager` to ignore virtual plugin GUI holders (`!(holder instanceof Container)`).
- **Retest:** Deposit a tracked piece of armor into the Reset Vault. Verify no staff dupe alerts or database cases are opened during preview, confirmation, or vault viewing.
- **Simply:** Looking at or depositing items in the vault GUI falsely triggers staff dupe alerts because the dupe scanner scans GUI menus like real chests.


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
