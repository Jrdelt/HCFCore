# Vertex code audit — confirmed issues

Audited 2026-09-10. Remediation and a second static/restart-safety pass were
completed after this initial report. Scope: 343 production Java files, 69 test files, 41
resource/configuration files, and the root/documentation files. `./mvnw -q
test && ./mvnw -q package` passed and all bundled YAML parsed successfully.
Some test output deliberately injects storage failures; it was not treated as
a build failure. These are confirmed source/configuration/documentation issues,
not a replacement for a live-server smoke test.

## Remediation status — 2026-09-10

The following were implemented and rebuilt after this report:

- **CI-01 resolved:** Mine drops now have one authoritative path. Vanilla
  `Block#getDrops` is skipped inside mine regions; the finalized configured
  mine reward is routed through the Backpack once.
- **CI-02 resolved:** Backpack Empty only transfers items that fit in the
  inventory. The remainder stays in that Backpack; nothing is dropped.
- **CI-03 resolved:** `backpacks.yml` now has a validated 250% default cap,
  applied before drop calculation and lore/booster display.
- **CI-05 substantially resolved:** distinct faction actions now exist for
  Collector withdraw/sell/upgrade/filter, Wands enforce collector ownership
  and `collector-sell`, and staff bypass needs `vertex.collector.bypass`.
  The bypass still needs its own audit-log event before release.
- **CI-11 substantially resolved:** TNT-bank and Collector withdrawals now
  preflight inventory capacity and never intentionally drop stored contents.
  See CI-11 remaining work for the asynchronous compensation edge case.
- **CI-12 resolved:** `/gc withdraw <amount>` atomically debits GC, creates a
  one-use code, and logs the code. The retired sign/anvil runtime path and
  tests were removed; the red/green wallet hints and GC documentation match.
- **CI-13 resolved:** `/pvptop` is registered; `/f pvptop` is retained as a
  compatibility route.
- **CI-14 substantially resolved:** the unused Nametag package was removed
  and references were removed from architecture/integration/config docs.
- **CI-08 partial:** the confirmed off-main-thread `Bukkit.getPlayer` call
  during trade recovery now runs on the server thread. The escrow lifecycle
  remains a critical redesign item.
- **CI-09 partial:** Sell Wands honor Vertex Filter entries, Collector Wands
  use faction role checks, active target inventories/break attempts are
  locked, and TNT compensation failure is logged. Durable Wand transactions
  are still required.

`./mvnw -q test && ./mvnw -q package` passes after the changes. GC withdrawal
tests now cover atomic debit/code/audit creation, reload persistence,
single-use redemption, and concurrent no-overdraft behavior. The expected
test-suite console errors are deliberate database-failure injection checks.

## Current release blockers

Do not release economy/escrow features as crash-safe until **CI-06**, **CI-07**,
the remaining part of **CI-08**, **CI-09**, **CI-10**, and the remaining part
of **CI-11** are implemented. These require durable operation/outbox state,
not more GUI locks or callback ordering.

## CI-01 — Mine ore drops are processed twice and bypass the Backpack

**Severity: critical — duplicates/incorrect mine rewards**

**Evidence**

- `mine/MineListener.java:42-87` replaces a mine ore's drops with the
  configured material and Ore Drop booster, then directly calls
  `giveOrDrop`.
- `backpack/BackpackAutoStoreListener.java:27-42` runs at the same `HIGH`
  priority, reads vanilla `Block#getDrops`, disables normal drops, and stores
  those drops first.
- `VertexPlugin.java:620-622` registers the Backpack listener before
  `VertexPlugin.java:685-688` registers the mine listener.

For an ore inside a mine, the Backpack listener can store vanilla/Silk Touch
drops, then the mine listener separately gives the configured boosted ingot or
item to normal inventory. This explains both ore-block drops and boosted mine
drops missing the Backpack.

**AI implementation requirements**

1. Make MineListener the only authority for mine-block drops. It must produce
   the final configured `ItemStack` list after its booster calculation.
2. Add a public Backpack route method for already-finalized drops. It must
   accept the final list, apply the player filter and Backpack bonus exactly
   once, and return leftovers.
3. Make BackpackAutoStoreListener skip every block in `MineManager.regionAt`;
   it must never call vanilla `Block#getDrops` for a mine block.
4. Keep `BlockBreakEvent#setDropItems(false)` and zero experience for mine
   ores. Do not use Silk Touch/Fortune's vanilla result as a fallback.
5. Add tests for: no Backpack, Backpack with free space, Backpack full,
   Silk Touch, an Ore Drop booster, and a mine base block with no payout.

**Plain-language reason:** The Backpack grabs Minecraft's normal ore drop
before the mine system replaces it. Then the real mine reward goes somewhere
else, so players can get the wrong item and effectively receive two reward
paths.

## CI-02 — Emptying a Backpack throws overflow on the ground and clears it

**Severity: high — player item loss/theft**

**Evidence**

`backpack/BackpackMenuListener.java:51-70` adds every stored item to the
player inventory, drops each leftover at the player's feet, then always writes
an empty Backpack. This is the opposite of the required repeated-empty
behavior.

**AI implementation requirements**

1. Snapshot the Backpack contents and simulate inventory insertion first.
2. Insert only what fits. Subtract only the amount actually inserted from each
   stored material and persist the remainder in the same Backpack item.
3. Never call `dropItemNaturally` in this flow. If nothing fits, keep all
   contents and show a clear inventory-full message.
4. Revalidate that the same Backpack instance is still in the offhand before
   writing its new data, and lock/ignore repeated clicks until the operation
   completes.
5. Test a full inventory, one partial stack slot, a Backpack holding more than
   one vanilla stack, and two rapid empty clicks.

**Plain-language reason:** Right now the Backpack empties everything even when
the player cannot carry it, then throws the rest onto the floor where it can be
lost or stolen.

## CI-03 — Backpack bonus has no 250% cap and can grow without bound

**Severity: medium — progression/economy imbalance**

**Evidence**

`backpack/BackpackProgression.java:52-55` compounds the bonus indefinitely and
returns `Double.MAX_VALUE` on overflow. `backpacks.yml` has no maximum bonus
key and explicitly says there is no level cap.

**AI implementation requirements**

1. Add a validated `max-drop-bonus-percent` setting to `backpacks.yml`, with
   `250.0` as the safe default.
2. Clamp the calculated bonus before it is used for storage, drop generation,
   action-bar text, Booster UI, and item lore. Invalid/negative/non-finite
   values must fall back safely and log one clear warning.
3. Preserve unlimited levels if desired, but levels beyond the cap must not
   increase reward output.
4. Test levels below, exactly at, and far beyond the configured cap.

**Plain-language reason:** A high-level Backpack can keep multiplying its
extra drops forever. The requested maximum is about 250%, but the code has no
maximum at all.

## CI-04 — Vertex Filter is only an auto-collection discard list

**Severity: high — required filter behavior is missing**

**Evidence**

- `backpack/BackpackFilterManager.java` stores only a per-player material set.
- Its only runtime check is in
  `backpack/BackpackAutoStoreListener.java:65`.
- `wand/WandListener.java` and `wand/WandManager.java` never receive or check
  that manager.

The current `/filter` does not filter a player's ordinary inventory, does not
override Essentials' filtering, and does not protect filtered items from Sell
Wands. Existing comments call it a Backpack discard list, which conflicts with
the later server rules.

**AI implementation requirements**

1. Define one authoritative Vertex Filter contract: whether each entry means
   "discard on pickup", "protect from automation", or two separately named
   lists. Do not keep one ambiguous list with incompatible meanings.
2. Put the filter service behind a stable API and route Backpack pickup,
   player-inventory handling, collector handling where applicable, and Sell
   Wand eligibility through it.
3. Pass the filter service into WandListener and reject protected entries
   without removing or paying for them. Keep custom/NBT item protections too.
4. Disable or deliberately supersede overlapping Essentials behavior so two
   plugins cannot make different decisions for the same item.
5. Migrate existing `backpack-filters.yml` entries deliberately and document
   the chosen meaning. Add tests for plain, filtered, custom/NBT, and mixed
   containers.

**Plain-language reason:** The current filter only tells a Backpack not to
pick something up. It does not protect the item anywhere else, including when
a Sell Wand empties a container.

## CI-05 — Chunk Collector faction permissions are too broad and Wands bypass them

**Severity: high — faction authorization bypass**

**Evidence**

- `collector/ChunkCollectorMenuListener.java:128-145` uses only
  `collector-open` for opening, withdrawing, and upgrading.
- `collector/ChunkCollectorListener.java:252` and `:279` contain only
  `collector-open` and `collector-break` checks.
- `config.yml:380-382` defines only those two Collector actions.
- `wand/WandListener.java` can sell/TNT-convert a Collector without a Collector
  faction-rank check.

The required independent controls for breaking, selling, upgrading, and filter
changes do not exist. A rank allowed to open a Collector can withdraw and
upgrade it; a member with a Wand can act on it without the intended rank rule.

**AI implementation requirements**

1. Add separate faction actions such as `collector-break`, `collector-withdraw`,
   `collector-sell`, `collector-upgrade`, and `collector-filter` to the
   configurable role matrix. Keep `collector-open` as view/open only.
2. Check the exact action again immediately before every click/prompt commit,
   including chat amount callbacks.
3. Make WandListener ask the same permission service for a Collector target;
   normal chest rules may remain separate.
4. Give staff bypass its own explicit high-risk permission and log its use,
   rather than treating every staff-build toggle as universal Collector access.
5. Add role-matrix tests proving that each action is independently allowed or
   denied.

**Plain-language reason:** Opening a Collector currently gives more power than
it should. The faction cannot decide separately who may withdraw, sell, or
upgrade its stored items.

## CI-06 — Auction House and Coinflips lack a crash-safe escrow/payout lifecycle

**Severity: critical — loss, duplicate, or replay risk after a crash**

**Evidence**

- `auction/AuctionManager.java:149-230` removes the seller's item and may take
  a fee before its asynchronous listing insert. If the seller disconnects and
  that insert fails, the callback does not return the item or fee.
- `auction/AuctionManager.java:289-315` marks a sale settled in storage before
  paying the seller and giving/claiming the buyer's item.
- `coinflip/CoinflipManager.java:424-463` accepts a wager before an async
  insert is durable. `:479-497` refunds before its cancellation is durable.
- `coinflip/CoinflipManager.java:577-632` removes the listing and commits the
  result before the winner's payout. Item-match payout claims are queued later
  at `:749-754`, outside the resolution transaction.
- `CoinflipStorage.resolveCoinflip` persists a log and notifications but no
  payout/escrow completion state.

The current negative in-memory placeholders stop simple double-clicks, but do
not recover a process stop between external balance/item movement and database
state.

**AI implementation requirements**

1. Introduce durable transaction IDs and an explicit state machine for every
   listing/coinflip: e.g. `CREATING`, `OPEN`, `MATCH_PENDING`,
   `RESOLVED_PENDING_PAYOUT`, `PAYOUT_IN_PROGRESS`, `PAID`,
   `CANCEL_PENDING_REFUND`, and `REFUNDED`.
2. Persist the immutable wager, predetermined winner, escrow ownership,
   expected payout, and audit data before showing an action as successful.
3. In one SQL transaction, reserve/remove the live listing, write the result,
   and create durable delivery/outbox rows. Do not delete the only escrow row
   before a recovery record exists.
4. Treat Vault calls as external side effects: write an idempotency/outbox
   operation before calling Vault, record the result, and reconcile incomplete
   operations on startup. Never reroll a winner during recovery.
5. For item wagers, insert winner claims in the same transaction as resolution
   (not in a later fire-and-forget task).
6. Add fault-injection tests for a stop/failure after each phase: wager debit,
   listing insert, settlement, payout, refund, and item-match approval.

**Plain-language reason:** The database can remember that a sale or coinflip
finished before the winner actually receives the reward. A crash in that gap
can make the money/item disappear, be paid twice, or make an old wager return.

## CI-07 — Claim delivery and pending XP have opposite restart-loss/dupe windows

**Severity: critical — delivery is not idempotent**

**Evidence**

- `auction/AuctionStorage.java:288-324` deletes claim rows in a transaction
  before `AuctionMenuListener` gives the items. A stop after deletion loses
  them.
- `coinflip/CoinflipMenuListener.java:150-165` gives claim items first, then
  `CoinflipManager.finishClaimAll` asynchronously deletes rows. A stop in
  between can give the same claim again.
- `trade/TradeManager.java:165` follows the same give-then-delete pattern.
- Auction and Coinflip apply offline XP to a player, then asynchronously delete
  the pending row (`AuctionManager.java:339-353`,
  `CoinflipManager.java:1135-1149`). Trade deletes pending XP/money before
  scheduling delivery (`TradeManager.java:91`).

**AI implementation requirements**

1. Replace raw read/delete claim handling with delivery-operation rows that
   have stable IDs and a durable state (`READY`, `RESERVED`, `DELIVERED`,
   `NEEDS_REVIEW`). A repeated click must reuse the same operation, never make
   a new payment.
2. Preflight inventory capacity and keep any remainder in durable claims;
   never drop a financial/claim item at a player's feet.
3. Do not automatically reissue an operation left uncertain by a hard crash.
   Reconcile it using a durable receipt where possible (unique IDs for
   high-value items) or send it to a staff review queue. Temporary withholding
   is safer than silently duplicating it.
4. Use the same operation framework for offline XP and money; record the
   exact planned amount and only mark it completed after an acknowledged,
   recoverable delivery path.
5. Add restart/failure tests before and after reservation, inventory insertion,
   XP award, and final acknowledgement.

**Plain-language reason:** Some rewards are deleted from storage before they
are given, while others are given before storage forgets them. A restart can
therefore lose a reward or let it be claimed twice.

## CI-08 — Player trades hand over items before escrow is durably cleared

**Severity: critical — trade duplication/loss risk and unsafe server-thread use**

**Evidence**

- `trade/TradeManager.java:108-124` transfers trade items directly to both
  inventories, then starts asynchronous escrow deletion/history persistence.
  A crash after `give` but before deletion restores the old escrow and can
  duplicate items.
- `:114-124` has the same order for cancellation refunds.
- `:156-161` calls `Bukkit.getPlayer` from `CompletableFuture.runAsync`, which
  accesses Bukkit/Paper server state from a non-main thread.
- `:91` removes pending money/XP in storage before the scheduled player/Vault
  delivery occurs.

**AI implementation requirements**

1. Move trades onto the same durable escrow/delivery model described in
   CI-06/CI-07. Completion/cancel must create recoverable delivery records
   before in-world inventory changes.
2. Keep all Bukkit API access, including `Bukkit.getPlayer`, inventory access,
   and player status checks, on the primary server thread. Background work may
   contain JDBC and immutable data only.
3. Do not delete a pending award before there is a durable delivery state.
4. Test a forced stop after each completion/cancellation phase and run with a
   Paper thread checker or equivalent test guard.

**Plain-language reason:** A trade can give both players their items and then
forget to erase the saved escrow if the server stops. It also asks Minecraft
for players from a database thread, which can cause random thread errors.

## CI-09 — Sell/TNT Wand processing has incomplete rollback and locking

**Severity: high — free TNT/money or lost materials after failures**

**Evidence**

- `wand/WandListener.java:147-160` credits Vault money, then removes the
  container contents and records market movement with no durable operation.
- `:193-225` deposits TNT asynchronously before removing ingredients. If the
  container changes, it calls `bank.withdrawTnt` but ignores whether that
  compensation succeeds.
- The `busy` set only stops another Wand click. It does not prevent breaking,
  moving, exploding, or opening a target while the TNT bank operation is in
  progress.

**AI implementation requirements**

1. Add a persistent Wand operation ID with snapshots/expected quantities and
   state transitions. Revalidate the exact target, contents, Wand instance,
   and use count immediately before commit.
2. Reserve bank capacity and container contents under one operation. If a
   rollback is needed, wait for and verify compensation; unresolved failures
   must enter a durable repair queue rather than silently succeed.
3. During a transaction, block relevant break/place/explosion/piston/container
   interactions for the exact target. Release every lock in a single finally
   path, including disconnect/restart recovery.
4. Persist audit rows with player, faction, target, materials, price/TNT,
   Wand ID, and operation ID.
5. Add tests for bank-write failure, compensation failure, target changed,
   target broken, double-click, and server restart.

**Plain-language reason:** The Wand says it is all-or-nothing, but some steps
finish in different places at different times. If a later step fails, the
server may fail to put the money, TNT, or materials back correctly.

## CI-10 — Chunk Busters are not restart-safe and leave managed-block state behind

**Severity: high — partial irreversible clears and stale Collector records**

**Evidence**

- `chunkbuster/ChunkBusterManager.java:220-239` explicitly deletes an
  unfinished operation after restart and leaves an already-partially-cleared
  area as-is, even though the item was consumed.
- `:457-474` directly calls `block.setType(AIR, false)`. It removes tracked
  Spawners, but does not unregister Chunk Collectors or use a shared managed
  block destruction path.
- Its area lock only handles player `BlockPlaceEvent` and `BlockBreakEvent`
  (`ChunkBusterListener.java:74-88`), not other changes while removal runs.
- Documentation describes restart-safe processing although the implementation
  intentionally abandons the operation.

**AI implementation requirements**

1. Decide and document one recovery policy: resumable operations with a
   persisted cursor/checkpoint is preferred; otherwise a preflighted atomic
   operation size must be small enough to finish synchronously. Never silently
   abandon a consumed buster after restart.
2. Persist operation owner, target/type, cursor/progress, started time, and
   cleanup state. Startup recovery must be idempotent.
3. Before removing a block, route it through central cleanup hooks for
   Collectors, Spawners, and any future managed block. Also apply all intended
   claim/protection policies rather than bypassing them with `setType`.
4. Lock all interactions that can mutate the active target area, or define an
   explicit safe behavior for them.
5. Test a restart in the middle of each Buster type and clearing a chunk with
   a Collector, Spawner, chest, and protected block.

**Plain-language reason:** If the server restarts mid-Buster, it consumes the
Buster, removes only some blocks, and then forgets the job. It can also leave
Collector database data behind after deleting the Collector block.

## CI-11 — TNT Bank and Collector withdrawals can convert secure items into floor drops

**Severity: high — theft/loss on full inventories or failed writes**

**Evidence**

- `faction/FactionBankMenu.java:392-411` debits TNT from the bank and drops
  inventory overflow on the ground. Its failed-deposit compensation uses the
  same dropping behavior at `:381-385`.
- `collector/ChunkCollectorMenuListener.java:155-170` gives the requested
  amount, drops overflow, then subtracts the full requested amount from the
  Collector.

**AI implementation requirements**

1. Simulate inventory insertion before debiting the bank/Collector. For a
   normal withdraw, either reject the whole action or deliver only the exact
   fitting amount and retain the remainder; choose and document one rule.
2. Never use world drops as compensation for a bank/Collector transaction.
   Keep overflow in the original bank/Collector or a durable personal claim.
3. Revalidate state and persist the debit/change before or together with the
   safe delivery record. If storage fails, do not rely on a floor drop as a
   refund.
4. Add full-inventory, partial-stack, disconnect, and database-failure tests.

**Plain-language reason:** Valuable TNT or Collector items can be removed from
secure storage and thrown onto the floor if the player has no room.

## CI-12 — GC wallet behavior and documentation conflict with the agreed GC-code design

**Severity: high — shipped behavior is the wrong product flow**

**Evidence**

- `gc/GcCommand.java:74-80` has no `/gc withdraw <amount>` command.
- `gc/GcMenu.java:119-124` and `:196-234` make Withdraw convert GC back into
  Vault money through the shared sign.
- `gui/gc.yml:27-40` still shows a red-dye money withdrawal and a PAPER redeem
  button.
- `README.md`, `docs/gc-currency.md`, and commands documentation all describe
  the old sign-based deposit/withdraw conversion flow.

This conflicts with the requested flow: remove GC-to-money conversion, show
`/gc withdraw <amount>` as the red-dye hint, create a one-time code, print it
in chat, and store that code in GC logs. A debit followed by a separate async
code insert would also be unsafe, so this must be one durable operation.

**AI implementation requirements**

1. Remove the GC-to-Vault withdrawal UI/action and old withdraw-sign path.
   Keep a deposit path only if money-to-GC deposit remains intended.
2. Implement `/gc withdraw <amount>` for players. Atomically debit the GC
   balance, create a one-use redeem code, and insert a log row containing the
   code/note in one database transaction.
3. On success send a copyable chat code; on failure do not change the balance.
   Make code collision retry bounded and explicit.
4. Update `plugin.yml`, `gui/gc.yml`, locale messages, README, GC guide, and
   command reference together. Remove the PAPER/anvil wording and use the
   intended green/red dye layout.
5. Test concurrent withdrawals, code creation failure, restart after commit,
   and one-time redemption.

**Plain-language reason:** The live source still turns GC back into money with
a sign. It does not have the command that should turn GC into a redeem code.

## CI-13 — PvP Top is wired to `/f pvptop`, not the requested `/pvptop`

**Severity: medium — command/documentation mismatch**

**Evidence**

- `faction/PvpTopCommand.java:12-41` only intercepts the Factions command
  alias plus `pvptop` as its second word.
- `plugin.yml` does not declare a root `pvptop` command.
- Documentation repeatedly advertises `/f pvptop`, while the requested command
  is `/pvptop`.

**AI implementation requirements**

1. Declare `pvptop` in `plugin.yml` and register an ordinary command executor
   and tab completer instead of relying only on command-preprocess interception.
2. Retain `/f pvptop` only as an explicitly documented compatibility alias if
   desired; do not make it the only route.
3. Update help text, docs, and the Events/leaderboard references in the same
   change. Add a command registration test.

**Plain-language reason:** Players are told to use `/pvptop`, but the plugin
only listens for `/f pvptop`.

## CI-14 — Documentation and dead Nametag code describe features that do not run

**Severity: medium — misleading admin guidance and stale maintenance burden**

**Evidence**

- `nametag/NametagManager.java` and `NametagListener.java` exist, but no code
  constructs or registers either class from `VertexPlugin`.
- README and multiple docs claim relation-aware nametags and describe
  `nametags.*` configuration keys that are not present in `config.yml`.
- `addme.md` still lists Dupe Investigation, F Top, and PvP Top as future work
  even though their modules are in source; it also documents the old GC flow.
- `plugin.yml:6` describes only kits/combat timers despite the much larger
  plugin surface.

**AI implementation requirements**

1. Because Nametags were intentionally removed, delete the unused Nametag
   package and every corresponding document/config reference. If they are
   meant to return instead, wire and test them; do not leave inert code.
2. Reconcile `addme.md` against actual shipped modules, moving only truly
   unfinished work to the roadmap.
3. Update the manifest description and all command/configuration docs from
   source-of-truth command registrations and resource keys.
4. Correct the Chunk Buster "restart-safe" wording unless CI-10 is fixed.
5. Add a release documentation check that compares declared commands, config
   headings, and enabled modules against the docs index.

**Plain-language reason:** Admins are being told to configure and use features
that either no longer run or have already been added. That makes setup and
support much harder than it needs to be.

## Cross-cutting release requirement

Do not treat an in-memory lock, a GUI refresh, or an async callback as a
financial transaction. Auction purchases, Coinflips, Trades, GC withdrawals,
Wand actions, TNT bank changes, and claims need durable operation IDs, explicit
states, idempotent recovery, and failure/restart tests before a production
release. For uncertain delivery after a hard crash, preserve evidence and send
the operation to staff review rather than automatically reissuing valuable
items.
