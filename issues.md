# Vertex Addons — working punch list

Status legend: **[done]** shipped in the working tree · **[open]** not started ·
**[partial]** some of it landed, remainder noted.

---

## Milestone 1 — foundations + known bugs

### [done] Shared short-number parsing/formatting
`Numbers` / `NumberSettings` / `number-formatting.yml`. BigDecimal end to end so
money never picks up float drift; k/m/b/t; validated comma grouping; rejects
`1..5m`, `10kk`, `m10`, `1e9`, `Infinity`, and signed input; overflow ceiling;
configurable symbol/rounding/precision. `AmountParser` now delegates here so
there is exactly one grammar. Still to do: route the remaining call sites
(coinflip wagers, auction prices, shop totals) through it.

### [done] Lang files can receive fixes at all
`saveResource(.., false)` never overwrites an existing lang file and an on-disk
key always beats the bundled fallback, so a corrected default could never reach
a live server — every future message fix would have silently done nothing.
Added `lang-version` plus an auto-refresh that backs the admin's file up to
`lang/backup/` before replacing it.

### [done] Sand Bots filled outside the claim
The anchor scan walked its whole square radius filtering only on block type,
with no claim check, so a trigger block just past the border was filled at the
faction's expense. Now claim-gated with a per-chunk cache (claims are
chunk-granular, so one lookup covers every anchor in that chunk).

### [done] Faction TNT bank
Root cause: TNT was the only resource Vertex did not own. Money and EXP persist
through `FactionBankManager`; TNT wrote to FactionsUUID's `tntBank(int)`, which
is a bare field write with no dirty-marking or save hook, and whose ceiling
comes from *their* `commands.tnt.max-storage`. Deposits could not reliably
survive a restart and no Vertex upgrade could raise the cap.

Vertex now owns the TNT balance in `faction_banks.tnt`, with a guarded one-time
migration that moves any native balance across and clears the native field so
the two can never both claim the same TNT. `/tntfill` moved onto the same bank
and now debits before filling, refunding whatever the dispensers cannot take.
Deposit also no longer removes items before the capacity check — the old order
could throw on `int` overflow *after* the TNT was gone.

### [done] TNT bank capacity upgrade
New `tnt-bank` faction upgrade. Its per-level `bonus` is an absolute capacity
rather than a percentage, so levels are read straight from config with no
multiplier. Base 1,000,000 (`faction-upgrades.tnt-base-capacity`), levels
2M → 10M.

### [done, uncommitted] Already fixed but not yet committed
These are fixed in the working tree; a running server on the last commit still
shows the old behaviour until this is built and deployed:
- Coinflip animation clamped to 5–10s, result deferred until it finishes, and
  disconnect results persisted (`CoinflipResultNotification`).
- `BACKPACKS > Filtered: <gray>none` — the raw tag came from `filter-none`
  being `"<gray>none"`; placeholder values are tag-escaped by design.
- Auction House sort: per-mode default direction (price high→low, date
  oldest→newest) and an in-place refresh on change.
- Shop `spawners_and_mob_drops` category and creeper spawners.
- Chunk Collector item lore (level/stored/capacity, no owner or faction).
- KOTH focus + live direction arrow rework.

### [open] Remaining known bugs
- **Staff kick/ban combat check** — no guard exists. Vertex owns neither
  `/kick` nor `/ban`, so this needs a `PlayerCommandPreprocessEvent` intercept
  on configurable aliases, same pattern as the leader-leave block.
- **Invsee stale-item anti-dupe** — staff GUI must revalidate the target's real
  inventory before applying an edit.
- **`/f map` size** — not Vertex code. This is FactionsUUID's own command and
  its own config; either change it there or intercept and re-render.
- **Auction House tie-break** — equal price should fall back to oldest first,
  then listing id.

---

## Open questions blocking later milestones

- **Mining worlds** — need the actual world names, and whether regions are
  WorldGuard or cuboid coordinates.
- **Warzone** — undefined today. `FactionsHook` only distinguishes wilderness
  from claimed; the Artifact Event is built entirely on "inside Warzone".
  FactionsUUID exposes `Factions.factions().warZone()`, which is the likely
  answer, but it needs confirming.
- **`/f top` composition** — assumed spawners only. FactionsUUID owns `/f top`,
  so Vertex either registers its own or intercepts and re-renders.
- **Spawner F Top value** — assumed a new per-type `ftop-value` in
  `spawners.yml`, independent of shop sell price.

---

## Decisions taken

- **Dynamic pricing** will be replaced with the spec's engine (activation
  volume, separate buy/sell volume, price weight, smoothing). The current
  engine is `base × (1 + changePerUnit)^netVolume` with a dead zone and tracks
  only *net* volume, so it cannot express the spec's model. Live price state
  resets on switchover.
- **GUI standard** (config-driven layout + small caps) applies to new GUIs
  only; the ~30 existing GUIs are left alone.
- **Locales** — new work adds keys to `en_us.yml` only. The existing de/es/pt
  files keep working via the bundled fallback.
- **Vertex Filter** — `/filter` is a *discard* list, so reusing it to mean
  "protected from Sell Wands" would protect exactly the items a player marked
  as junk. Sell Wands will use a separate sell-protection list.
- **Fortune in mining worlds** — disabled, per the spec's "Finalized Tuning
  Defaults" section, which explicitly overrides the earlier Netherite text.

---

## Known flakiness

`KitManager.persistAsync` intermittently surfaces an async error during test
teardown (seen once in ~6 full runs, passes on re-run). Pre-existing, unrelated
to current work, but worth chasing before it masks a real failure.

---

## Deep scan findings — 2026-09-08

Source review and a fresh `./mvnw test -q` passed, but the passing suite does
not cover several high-risk persistence and packet-race paths below.

### In-game / player-visible issues

- **[FIXED] Fresh Auction House and Coinflip entries can be acted on before
  they have a database ID.** Both managers put a negative-ID placeholder in
  their live map while an async insert is in flight, and both browsers render
  those placeholders as normal clickable entries. A buyer/opponent can settle
  it before the insert completes; the later callback then inserts the real,
  still-open entry, allowing the same auction item or coinflip wager to be
  settled again. Do not render/click a listing until it is durable, or mark
  placeholders explicitly non-actionable.

- **[FIXED] Auction collection-box claiming has no transaction lock or row
  identity.** The GUI hands out its cached item snapshot first and then starts
  an async `DELETE` of *all* claims for the player. Replayed/double-clicked
  inventory packets can grant the same snapshot more than once before that
  deletion completes. A claim created after the GUI snapshot can also be
  deleted without ever being shown. Mirror Coinflip's row-ID claim approach,
  but make the database claim/reservation authoritative before giving items.

- **[FIXED] TNT Wands do not remain locked through their bank transaction.**
  Gunpowder is removed and the collector/chest is committed before the async
  TNT-bank deposit returns; the container lock is released immediately and
  the wand use is consumed even if that deposit later fails. A concurrent
  bank change can therefore consume the use and partially process the
  container despite the advertised all-or-nothing/full-bank behavior. Reserve
  capacity and retain the lock until one commit/rollback path finishes.

- **[FIXED] Shop and Sell-Wand sales can delete items without paying the
  player.** `ShopManager.sell` and `WandListener.runSell` remove/commit items
  and ignore Vault's `depositPlayer` response. A failed economy deposit leaves
  the sold items gone and the market volume changed with no compensation.
  Check the response and implement a recoverable rollback/claim path.

- **[FIXED] Spawner-stack withdrawal drops spawners on the ground when the
  player's inventory is full.** The management GUI gives/drops each physical
  spawner before it decrements the stack. This violates the finalized
  no-drop-on-full rule and risks loss or theft; preflight inventory capacity,
  then atomically decrement and give the exact items.

- **[gap] Chunk Collector rank controls are incomplete.** The current faction
  permission action list only has `collector-open` and `collector-break`.
  Selling, upgrading, and filter modification cannot be independently
  controlled as required, so a rank allowed to open a collector may gain more
  authority than intended.

### Backend / integrity issues

- **[FIXED] Auction settlement is only durable after money and item state
  has already changed.** Buying, cancellation, and expiry remove the live
  listing and move money/items first; `deleteListing`, audit logging, and
  offline claims happen later in independent async writes. A database failure
  or crash in that window can resurrect a sold/cancelled listing after restart
  while the item has already been delivered, or lose an offline return. Use a
  single durable settlement record/transaction before exposing the result.

- **[FIXED] Coinflip money/item settlement has the same crash window.**
  The opponent is charged and the winner is paid or queued before
  `resolveCoinflip` completes. If persistence fails, the in-memory listing is
  gone while the original database listing can remain; after restart it is
  playable again. Item payout claims are also queued in a separate async
  write, outside the resolution transaction. Persist a predetermined outcome,
  both escrow movements, and payout state atomically before delivering money
  or items.

- **[FIXED] Dynamic-shop state writes can land out of order.** Every price
  change starts an independent async save with no per-material write chain.
  Under rapid buys/sells or a decay tick, an older write may finish last and
  overwrite the newest `net_volume` in storage; the live price looks correct
  until the next restart, when it reverts. Serialize or version writes per
  material.

- **[high] The native TNT-bank migration can permanently lose TNT.** It clears
  FactionsUUID's native balance before the asynchronous Vertex deposit is
  confirmed, then writes the migration marker even when a deposit returned
  `false` rather than throwing. A crash or database failure can therefore
  leave both stores empty and prevent a retry. Persist/verify the Vertex copy
  first, clear the native value only after success, and mark completion only
  after every faction is reconciled.

- **[gap] No persistent unique-item ID or dupe-investigation system exists.**
  There is no tracked-item schema, duplicate-ID detector, unresolved-case
  queue, staff-only inspection command, or persistent offline staff alert.
  This leaves the required rollback/desync investigation workflow unavailable.

- **[gap] The spawner data model cannot implement individual aging safely.**
  A stack currently persists only mob type, one aggregate stack size, and a
  faction tag. It has no per-spawner timestamps, value progress, stack-member
  ordering, or F Top association, so youngest-first removal and per-spawner
  aged value cannot be added as a small GUI change; the persistent schema and
  migration need design first.

- **[test gap] There are no failure/restart/race tests for the paths above.**
  Current Auction/Coinflip/Wand tests cover normal flows and pricing, but not
  negative-ID placeholders, repeated claim packets, failed external deposits,
  database-write failure, or restart between escrow/payout steps. Add these
  before treating economy and item-transfer transactions as exploit-safe.
