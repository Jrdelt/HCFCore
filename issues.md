# Vertex — Review Status

Updated 2026-09-07. Full test suite is run before each release build.

## Fixed

- **Storage migration:** draining writes no longer shuts down Tags or Death
  saving. The `storage.type` config change is now written atomically, so a
  crash cannot leave a partial `config.yml`.
- **Kit saves:** `/kit save` and `/kit delete` return without blocking the
  server thread. Pending saves now flush correctly during shutdown.
- **Ninja Star:** it rechecks both players at teleport time and cancels when
  either is in a configured protected/no-pearl region.
- **Ghost Players:** only kicked, timed-out, or erroneous disconnects can
  spawn an NPC. Voluntary logouts use the normal combat-logout penalty.
  Failed Citizens spawns now remove their saved record safely.
- **Ranked tab list:** grouped rank sections are enabled by default, use the
  highest LuckPerms group weight, refresh immediately on join, retain
  colored prefixes, and use an Essentials nickname when available.
- **Nametags:** disabling nametags with `/vertex reload` removes Vertex's
  old scoreboard teams instead of leaving stale colors behind.
- **Chunk Collectors:** exactly 24 material types are supported and every
  type has a visible withdrawal slot. A new 25th tagged farm-drop type is
  destroyed to prevent item/PDC lag.
- **Spawner selling:** a spawner stack is not reduced if the Vault refund
  fails.
- **Anti-Blockup Bone:** waits for player data to load before applying its
  effect or consuming the item, so its cooldown cannot be bypassed on join.
- **Dependency checks:** startup and `/vertex reload` verify required and
  optional integrations. A reload can enable Blueprints or Ghost Players
  after their dependencies become available.
- **Documentation:** Backpack drop bonuses, collector limits, Ghost Player
  behavior, safezone Ninja Stars, grouped tab formatting, reload behavior,
  and atomic storage migration documentation are current.

## Intentional behavior

- **Co-Leaders** share Vertex custom permissions with faction leaders; only
  FactionsUUID's native disband rule remains different.
- **Collector capacity reductions** are an offseason/reset-only operation.
- **Chunk Collectors** handle automatic farm drops, not manually peeled mob
  stack kills.
- **Invsee armor-slot handling** is accepted as-is.

## Coinflip / Shop / Auction House review — 2026-09-07

Covers the three economy modules added this cycle (Coinflip, Shop, Auction
House) plus the item-wager-approval and result-animation work just added to
Coinflip. Nothing below is a data-loss bug -- money/levels/items always come
from a real balance/inventory, never a merely-reserved count -- these are
either small hardening gaps or informational notes about known trade-offs.

### Resolved (2026-09-07, following your answers above)

- **One coinflip open at a time, one match being taken at a time.**
  `CoinflipManager.createMoneyCoinflip`/`createExpCoinflip`/`createItemsCoinflip`
  now refuse (`CreateResult.ALREADY_HOSTING`) if the host already has an
  unresolved coinflip listed, and `requestItemMatch` refuses
  (`PlayResult.ALREADY_TAKING_ONE`) if the opponent is already waiting on
  approval for a different coinflip. Both are enforced before any
  balance/level/item ever leaves the player, so a refused attempt never
  touches anything.
- **The Active Coinflips browser now live-refreshes.** It re-renders in
  place (not a full reopen -- no flicker, no dropped clicks) on
  `gui-refresh-interval-ticks`, now actually wired to a repeating task and
  defaulted to 1 tick (the fastest a server can meaningfully update
  anything). Clicking your own listing now says so
  (`coinflip.play-is-host`) instead of silently doing nothing.
- **`/cf <amount> <type>` no longer tab-completes player names in the type
  slot.** Only `exp`/`money` suggest there now; a player name only
  tab-completes after the type is typed (or as the second word of
  `/cf hand <player>`, which really is a player-target slot). Manually
  typing `/cf <amount> <player>` directly still works -- this only changed
  what's *offered* via tab-complete, not what the command accepts.
- **Item-match approval is now a real side-by-side review, not a blind
  chat decision.** The chat prompt now links to `/cf review <id>`, a GUI
  showing the host's own wager on top and the opponent's proposed items
  below it, with green Accept / red Deny buttons (`/cf approve`/`/cf deny`
  still work too, for deciding without opening the GUI). Denied, expired,
  and orphaned matches now *always* return items to the opponent's claim
  stash -- never straight into a live inventory -- matching how every
  other payout in this system already worked, and the underlying coinflip
  goes back to being listed and joinable by someone else.
- **The result animation no longer force-opens on a player in combat.**
  `CoinflipManager` now takes a `CombatManager` and checks
  `isTagged(uuid)` before opening it for either participant, so it can't
  yank a fighting player's screen away over something already decided.
- **Shop price feedback + a "dead zone" before price moves at all.** Block
  icons in `/shop` now show `▲ Above base price` / `▼ Below base price`
  in their lore. A new `price-change-threshold-units` (default 50) means
  the first that-many units of net volume, in either direction, don't
  move the price at all -- only real, sustained buying/selling pressure
  past that threshold actually changes it.

### Decided: keeping the async-write pattern (2026-09-07)

Two "Known trade-off" findings below (the crash-write window and the
placeholder-id race) both trace back to the same design choice: writes
persist asynchronously after the in-memory state already changed. Asked
directly, you chose to keep that pattern rather than make wager-creation
and item-claim writes synchronous -- consistent with how Faction Banks
and everything else in the plugin already works, and the crash window is
milliseconds wide and requires a hard crash to land exactly there. No
code change from this; both findings below stay as accepted, documented
trade-offs, not open items.

### Clarified, no change needed

- **"`CoinflipManager.loadState()` only runs once, at startup" was
  answered with "this needs to be synced... refreshed as fast as
  possible."** To be clear: this was never about live gameplay staleness
  -- the in-memory `activeCoinflips` map is a single shared object that
  every GUI render, command, and click already reads directly, so it's
  instantly current for everyone on the server at all times, and the new
  live-refresh above makes that even more visible. The `loadState()`
  note was narrowly about `/vertex reload` specifically choosing not to
  re-read the database mid-session -- which is the *safe* choice (a
  reload re-reading disk could clobber an in-memory state that's newer
  than what's on disk). There's nothing actually stale here to fix.

### Known trade-offs (informational, not bugs)

- **A brief crash window between an in-memory state change and its async
  database write landing exists across Coinflip, Shop, and Auction House.**
  Every write in these three modules follows the same pattern established
  earlier for faction banks: update the in-memory map synchronously (so the
  action feels instant and is immediately correct for any other in-game
  interaction), then persist to the database on a background thread. A hard
  crash (kill -9, OOM, JVM crash -- not a clean shutdown, which already waits
  for pending writes via `awaitWrites()`) in the small gap between those two
  steps means that one write never reaches disk, and the change is lost on
  the next boot even though it briefly "happened". This window is
  milliseconds wide and requires the crash to land exactly there, so the
  real-world odds are low, but it's the one residual risk that can't be
  fully closed without a slower, fully-synchronous-write architecture (which
  earlier findings in this document explicitly steered away from for
  performance reasons). Documenting it here so it's a known, accepted
  trade-off rather than a surprise later. See "Decided: keeping the
  async-write pattern" above.

- **The same placeholder-id pattern (finishCreate for a new coinflip,
  requestItemMatch for a pending item match, AuctionManager.list for a new
  listing) has an analogous tiny race:** if the action is cancelled/played/
  approved via its temporary negative placeholder id in the same instant the
  real database id arrives, the real-id entry can be silently re-added to
  the live map after the fact, becoming untouchable until the next restart
  reloads it fresh from the database. Not a loss -- the wager/listing is
  still safe and still owned by the right player -- just temporarily
  inconvenient. Requires a human to interact within roughly a millisecond of
  hosting/joining to ever trigger, so it's accepted as-is rather than adding
  locking around every placeholder swap. Also covered by "Needs a
  decision" above -- the same synchronous-write change would close this too.

- **Sweep tasks (Shop's price decay, Auction's expiry sweep, Coinflip's
  item-match-timeout sweep, and the log-pruning that now rides along with
  it) only run while the server is actually up**, since they're plain
  `BukkitTask`s with no persisted "last ran at" timestamp. An auction listing
  that should have expired at 3am while the server was down simply expires
  the moment the server comes back and the next sweep tick fires -- nothing
  is lost or double-processed, it's just late. This matches how KOTH/Outpost
  capture state already works (deliberately reset on restart rather than
  persisted) and needs no fix, just awareness if "why didn't this expire
  exactly on time" ever comes up in support.

- **`CoinflipManager.loadState()` (active coinflips, bans, pending
  experience, pending item matches) only runs once, at plugin startup --
  `/vertex reload` re-reads `coinflips.yml` but does not re-sync live state
  from the database.** This is the correct choice, not an oversight: the
  in-memory maps are the authoritative live state while the server is
  running, so re-reading the database mid-session could clobber something
  that happened after the last write landed. Worth stating explicitly so a
  future change doesn't "fix" this by adding a reload-time reload that would
  actually make things less safe. See "Clarified, no change needed" above.

### Verified clean

- Coinflip's claim stash is populated synchronously (in-memory) the instant
  a flip resolves, before the cosmetic result animation ever opens -- a
  player disconnecting mid-animation, or never seeing it because animations
  are off, has no effect on whether their winnings are claimable.
- Shop and Auction House don't need item-wager host-approval the way
  Coinflip now does: Shop trades against the server's own dynamic price (no
  other player to be scammed by), and Auction House is fixed-price
  buy-it-now (the seller sets the price up front and the buyer pays exactly
  that, with no dueling wager on either side).

## Database persistence & command wiring review — 2026-09-07

A plugin-wide deep dive: every `*Storage` class's schema against
`StorageMigrator`, every `pendingWrites`/`awaitWrites()` manager's wiring
into shutdown and migration-drain, and every declared command's executor/
tab-completer/permission/usage-string/doc consistency. Good news first:
this came back mostly clean. Nothing below is Critical (no silent data
loss, no startup crash).

### Persistence

- **Medium — SQLite's single connection can starve `DeathManager`'s
  6-hour cleanup timer during a storage migration.** `Database.java`
  hard-caps the SQLite pool at 1 connection (`setMaximumPoolSize(1)`).
  `StorageMigrator.migrate()` holds that one connection open for the
  entire multi-table copy when SQLite is the source. `DeathManager`'s
  `player_deaths` prune timer (`runTaskTimerAsynchronously`, every 6
  hours, regardless of whether anyone's online) isn't paused for a
  migration the way online players are (`beginStorageMigration()` only
  checks for zero online players and zero active Blueprint builds). If
  that timer happens to fire mid-migration, its `getConnection()` call
  blocks against the exhausted pool for up to 10 seconds and then throws
  — logged as "Failed to clean up expired rollback records." No data is
  lost (it's just a housekeeping DELETE that retries in 6 hours), but
  it's a real, previously-undocumented contention path, and the resulting
  log line right next to a migration could read as a bigger problem than
  it is. Fix: either have the migration briefly pause player-independent
  background timers like this one, or note in the warning log that it's
  an expected/harmless collision during a migration.
- **Low — `coinflip_claims.winner_uuid` and `auction_claims.owner_uuid`
  have no index.** Every claim lookup (`loadClaims`/`hasClaims`/
  `deleteClaims`) filters on these columns via a full table scan;
  `hasClaims` is plausibly called on every player join, so this gets
  linearly slower as claims accumulate on a long-lived server. Fix: add
  a `CREATE INDEX IF NOT EXISTS` on each, alongside the existing `init()`
  table creation.
- **Low — per-player log lookups (`/cf logs <player>`, the equivalent
  Auction House log) aren't covered by the existing index.** Both
  `coinflip_log`/`auction_log` only index `resolved_at` (for the no-filter
  admin view); the per-player `WHERE host_uuid = ? OR opponent_uuid = ?`
  style query can't use that efficiently. Self-limiting since both tables
  are already periodically pruned (`log-retention-days`), so this is the
  lowest priority item in this whole review — worth a composite/second
  index only if it's ever actually noticeable.

**Verified clean (the bulk of the review):** every table any `*Storage`
class creates is present in `StorageMigrator.TABLES` with an exact
column-for-column match (20 tables, scripted diff, zero orphans either
direction) — this includes `coinflip_pending_matches`, added earlier this
session. `ensureSchema()` calls `init()` for the same 9 storage classes
`VertexPlugin.onEnable()` does, no gaps. Every manager with a
`pendingWrites`/`awaitWrites()` pattern (10 of them: Ability, Auction,
Blueprint, Coinflip, ChunkCollector, FactionBank, FactionUpgrade,
Language, Shop, Spawner) is drained from both `onDisable()` and the
migration-drain method, no exceptions; every async write in each of them
traces back to that manager's own tracking, no untracked fire-and-forget
writes found. No SQL injection anywhere — every value-bearing statement
across all 9 storage classes uses `?` placeholders; the only string-built
SQL (in `StorageMigrator` itself) concatenates only hardcoded, compile-
time-constant table/column names, never anything dynamic. No SQLite/MySQL
schema drift in any dual-dialect table. `player_deaths` deliberately drops
its row id on migration (nothing ever looks a death up by numeric id,
confirmed) while every other table's id is deliberately preserved
(`coinflip_pending_matches.coinflip_id` genuinely references
`coinflips.id`, and losing that link would silently orphan a pending
match). Migration itself is transactional (wipe + copy inside one
`autoCommit(false)` block, rollback on any failure) and login-blocking
(`onPlayerLogin` kicks anyone trying to join mid-migration) closes the gap
between "no one online when it started" and "someone joins and writes
mid-copy." Backpacks are correctly excluded from all of this — their
state lives entirely in the item's own PersistentDataContainer, by
design, not in a database table.

### Command wiring

- **High — `/vertex` has no tab-completer registered; its `TabCompleter`
  logic is dead code.** `VertexCommand implements CommandExecutor,
  TabCompleter` with real completion logic for `reload`/`clearmobstacks`/
  `storage` (and `local`/`mysql`/`confirm`), but
  `VertexPlugin.java` only calls `getCommand("vertex").setExecutor(new
  VertexCommand(...))` — never `.setTabCompleter(...)`. Every other
  multi-subcommand command in the plugin (19 others checked) correctly
  registers both. Fix: capture the `VertexCommand` instance in a local
  variable (like every other command does) and add the missing
  `setTabCompleter` call.
- **Medium — `vertex.backpack.debug` is checked in code twice
  (`BackpackCommand`) but never declared in `plugin.yml`'s
  `permissions:` block.** Bukkit's fallback behavior for an undeclared
  permission is identical to this node's intended `default: op` (verified
  against `PermissibleBase` behavior), so this isn't a functional or
  security bug — it just means a permission-management plugin like
  LuckPerms can't discover or describe this node from Vertex's registered
  list. Fix: add the missing entry, matching the other `vertex.backpack.*`
  nodes already there.
- **Medium — `plugin.yml`'s usage string for `/kit` is stale.** It only
  shows `/kit <name>`, omitting `create`, `delete`, and the `save` alias
  that the command has supported for a while (confirmed via
  `KitCommand.onCommand`'s actual branching) — `docs/commands-and-
  permissions.md` and the in-game usage message both already have the
  full syntax, only `plugin.yml` (what Bukkit's own `/help kit` reads)
  is behind.
- **Medium — `plugin.yml`'s usage string for `/backpack` is stale.** It
  shows `give <player> <tier>` only, omitting the optional `[level]`
  argument and the `debug` subcommand that `BackpackCommand` has actually
  supported for a while. Same fix shape as the `/kit` finding above.
- **Low — `docs/commands-and-permissions.md` documents a `/reboot
  [minutes]` argument that doesn't exist.** `RebootCommand` only ever
  accepts `cancel` as an argument; a bare `/reboot` always uses the
  configured `reboot.default-delay-minutes` — there's no minutes-parsing
  path at all. `plugin.yml` and the in-game usage message both already
  say `/reboot [cancel]` correctly; only this one doc table row is wrong.
- **Low — `/kit`'s tab-completer never suggests `save`,** even though
  `save` is a working, permission-gated alias for `create` (confirmed in
  `KitCommand.onCommand`). It still works fine if typed in full; tab-
  complete just doesn't hint at it.
- **Low — `/reboot` takes an argument (`cancel`) but has no
  `TabCompleter` at all** (no such class exists to wire up, unlike the
  `/kit`/`vertex` findings above which are registration gaps). Worth a
  trivial `TabCompleter` if `/reboot` gets more arguments later; low
  priority as-is since `cancel` is the only option and it's documented
  everywhere else.

**Verified clean:** no startup-crash risk anywhere — all 32 commands
declared in `plugin.yml` have a matching `getCommand(...).setExecutor(...)`
in `VertexPlugin.java` and vice versa (no command registered under a name
`plugin.yml` doesn't declare, which would NPE at startup). Every
permission string checked anywhere in the codebase (28 static nodes, plus
the three intentionally dynamic per-kit/per-tag/per-capture-type families)
traces back to a real declared node with no typo'd near-misses. The
following commands were individually checked end-to-end (executor +
tab-completer where applicable + permission + usage string + doc) and are
fully consistent: `koth`/`outpost`, `ah`, `shop`, `chunkcollector`,
`language`, `getitem`, `rollback`, `invsee`, `endersee`, `freeze`,
`filter`, `cf`/`coinflip`, `combattag`, `uncombat`, `combatcheck`,
`blueprint`, `staff`, `vanish`, `staffchat`, `staffbuild`, `frally`,
`kits`, `abilities`, `cooldowns`, `tags`, `spawners`, `nextreboot`.

## Full branch review — 2026-09-07

Everything currently uncommitted at once: the scoreboard/tablist removal,
the new TNT Cannon and Sand Bot modules, the Vertex PlaceholderAPI
expansion, the new combat-tag-blocks-safezone-entry lock, and the
already-in-progress Auction House / Coinflip / Shop / Player Trading /
Backpack-pricing-curve work. Money/item-duplication findings are called
out explicitly — none were found in Shop (server-priced, no player-vs-
player wager) or in Auction/Coinflip's core same-tick resolution logic
(Bukkit's single-threaded event model already prevents the classic
"two players click at once" race there). The real bugs are elsewhere:
async-callback/snapshot races, unvalidated numeric input, and a couple of
genuine regressions from this session's own changes.

### Fixed this pass

- **Critical — nametag relation colors silently broke for everyone when
  `ScoreboardManager` was removed.** `NametagManager` registers one team
  per (viewer, subject) pair on *the viewer's own* `Scoreboard`, so the
  same subject can show a different relation color to different viewers
  at once — that only works if every viewer actually has a distinct
  `Scoreboard` object. The now-deleted `ScoreboardManager.setup(player)`
  was the only code that ever gave a player one; nothing replaced it.
  Every player defaulted to Bukkit's single shared main scoreboard, so
  every viewer's team registration collided on the same object and each
  overwrote the last — every player ended up seeing whichever relation
  color was computed for the most-recently-processed viewer, not their
  own (an enemy could render green/"friendly"). Fixed: `NametagListener
  .onJoin` now gives each player a fresh scoreboard on join, unless
  something else (e.g. another plugin) already assigned a non-default
  one.
- **High — `/shop buy|sell` had no upper bound on `<amount>`, a
  main-thread-hang DoS reachable by any player.** `ShopManager
  .totalBuyCost`/`totalSellPayout` loop once per unit for dynamically-
  priced items; `/shop buy dirt 2000000000` ran roughly two billion loop
  iterations synchronously on the main thread before responding —
  freezing the server for everyone and likely triggering a watchdog
  kill. Fixed: capped at 10,000 per trade in `ShopCommand`.
- **Medium — a Sand Bot converted blocks for free if its output
  material had no Shop price entry.** `ShopManager.buyPrice` returns `0`
  for an unlisted material, and `SandBotManager` charged that silently
  successfully. Fixed: `spawn()` and each tick now refuse (and despawn)
  if the output material isn't a registered, priced Shop entry.
- **Medium — a Sand Bot's per-tick batch was priced once, not per
  block,** unlike a real `/shop buy`'s `totalBuyCost`. Harmless under the
  shipped flat-priced config, but would silently undercharge a full
  tick's conversions at the tick's starting price if dynamic pricing were
  ever turned on for one of the four output materials. Fixed: re-priced
  per block inside the loop.
- **Low — `cannon.velocity-scan-radius-blocks` had a floor but no
  ceiling,** unlike Sand Bot's `radius-blocks` (capped at 5). A
  misconfigured huge value turns every cannon explosion's velocity-clamp
  pass (run twice — immediate + 1-tick-delayed) into a large-volume
  entity scan. Fixed: capped at 32.
- **Medium — storage migration (`/vertex storage`) never drained
  `TradeManager`'s pending async writes,** unlike every other manager
  added this cycle. `awaitStorageWritesForMigration()` awaited coinflip/
  shop/auction but not trade, so an in-flight escrow save triggered by a
  departing player's last trade action could be silently absent from the
  migration copy. Fixed: added to the drain list.

### Open — High (Player Trading: real money/item duplication)

- **Money duplicated on cancel after a failed lock.**
  `TradeManager.lock()` (line ~105) withdraws offered money *before*
  checking whether the offered XP is affordable; if that XP check fails,
  the money is refunded but `session.requesterHeldMoney`/
  `targetHeldMoney` is never reset back to `0`. If the trade is then
  cancelled (either player, a timeout, or a disconnect) without a
  successful re-lock, `cancel()` refunds `requesterHeldMoney` a second
  time — reproducible on demand by any player who intentionally offers
  more XP than they have alongside a money offer.
- **`restoreEscrow()` can re-grant the same money/items every restart.**
  On startup, money is deposited and items queued as claims for every row
  in `trade_escrow` *before* that row is deleted — and the delete only
  happens inside a later async step. If that step throws (or the server
  crashes again shortly after boot), the row survives and is reprocessed
  — granting the same money/items again — on every subsequent restart
  until manually cleared.
- **Low — `complete()` doesn't re-check distance/allowed-state at final
  confirm,** only at request/accept time and via the 1-second `sweep()`
  timer. A trade that goes out of range and is confirmed within that
  same second completes anyway, contradicting `docs/trading.md`'s
  documented "moving out of range ... cancels safely" guarantee.
- **Cosmetic — a locked player editing the money/XP amount sees
  "invalid amount" instead of a "you're locked" message.** No state
  corruption; `TradeManager.setValue` already blocks the actual change.

### Open — High (Coinflip)

- **`NaN` bypasses the money-wager range check.** `Double.parseDouble
  ("NaN")` succeeds, and `NaN < min`/`NaN > max` are both `false` in
  Java, so `/cf NaN` reaches `economy.withdrawPlayer` directly —
  depending on the economy plugin's own `NaN` handling, this can corrupt
  the stored balance or bypass `min-money-wager`/`max-money-wager`
  entirely. (The EXP path is incidentally safe: `Math.round(NaN) == 0`
  fails its own min-wager check.)
- **Auto-refund-on-persist-failure only runs if the host is online,**
  contradicting `docs/coinflips.md`'s documented "the host is refunded
  automatically" guarantee with no online caveat. A player who wagers
  then disconnects before the async DB insert completes loses the wager
  permanently if that insert then fails (DB hiccup/timeout) — money, XP,
  or items, all three paths gate the refund behind `host.isOnline()`.
- **Claim-All uses a stale GUI snapshot plus an unconditional
  delete-all-rows-for-player.** Opening the Claim Stash snapshots claims
  once; if a new claim arrives (another coinflip resolves) while the GUI
  is still open, clicking "Claim All" gives only the stale snapshot but
  deletes every row including the new one — permanently lost. The
  inverse (rapid double-click before the async delete lands) can instead
  double-give the same items.
- **Wager GUI border-slot exploit loses items.** Shift-clicking an
  unnamed `GRAY_STAINED_GLASS_PANE` (an ordinary block a player might
  legitimately want to wager) from the player's own inventory merges it
  into the menu's border/filler slot via vanilla shift-click behavior —
  outside both the confirm-gather range and the close-handler refund
  range, so it's silently dropped on either Confirm or Cancel.

### Open — High (Auction House)

- **Item and fee permanently lost if the seller disconnects right as
  their listing's DB insert fails.** The refund-on-failure path in
  `list()` is nested inside a `seller.isOnline()` check that guards the
  entire recovery, not just the delivery method — `giveOrClaim` already
  branches on online/offline internally, so the outer guard is
  unnecessary and actively harmful.
- **Claim-All has the same stale-snapshot + delete-all-rows race as
  Coinflip above** (loss if a new claim arrives while the GUI is open;
  dupe on rapid double-click).
- **`NaN` price bypasses min/max validation for EXP listings.**
  `/ah sell NaN exp` creates a listing that then sells for effectively
  free — `(int) Math.ceil(NaN)` casts to `0` both for the buyer's charge
  and the seller's payout.
- **EXP price can overflow `int` if `max-price` is configured above
  ~2.147 billion** (no ceiling on that config value) — the cast can wrap
  negative, at which point buying the listing *grants* the buyer levels
  instead of charging them.
- **Pending offline-EXP proceeds can double-credit.** `applyPendingExp`
  grants levels immediately on login and only *then* asynchronously
  deletes the DB row; if that delete silently fails (logged as a
  warning, not retried), the row survives and re-grants the same levels
  again on the next restart's `loadState()`.
- **Cosmetic — ~1 EXP level leaks per fractional-price sale** from a
  `ceil` (buyer charge) vs. `round` (seller payout) mismatch, undisclosed
  and independent of the `tax` setting.

### Open — Medium (Backpacks / Capture Events)

- **`BackpackProgression.upgradeCost` has no upper bound on Backpack
  level.** Changed from closed-form `Math.pow` to a per-step loop this
  cycle; `/backpack give <player> <tier> [level]` only checks
  `level < 1`, so an extreme level (or a legal `max-multiplier: 1.0`
  config) can stall the main thread when the upgrade button is rendered.
- **`capture-events.yml`'s file-header comment is stale** — it still
  claims per-event hologram coordinates are staff-editable after
  `/vertex reload`; that field was removed this cycle in favor of a
  hard-derived position. A server with a manually-tuned hologram
  position will see it silently snap to the new formula on reload.
- **`BackpackManager`'s upgrade-cost comment describes the retired
  two-phase pricing curve;** the code two lines below now implements a
  three-phase easy/ramp/cap curve. Comment-only, but misleading for
  anyone tuning `backpacks.yml`.
- **`CaptureEventManager.tick()`'s broadened `catch (Throwable e)`**
  (intentional, so an incompatible DecentHolograms API doesn't abort the
  whole tick) also now swallows genuinely fatal `Error`s (OOM,
  StackOverflow) from inside `DHAPI` calls, retrying forever instead of
  surfacing them.

### Architecture / cleanup (not bugs — maintenance risk)

- **New feature persistence must be wired in three unrelated places by
  hand** (`VertexPlugin`'s storage construction, `StorageMigrator
  .TABLES`, `StorageMigrator.ensureSchema()`'s init-call list), with
  nothing tying them together — a forgotten entry silently excludes that
  feature's table from a storage migration.
- **The "pending experience" key-value pattern is duplicated near-
  verbatim in Coinflip and Auction storage, and reimplemented a third,
  differently-written way in Trade** — Trade catches *any* `SQLException`
  as a signal to retry with the other dialect's syntax, rather than a
  specific syntax error, so an unrelated failure (constraint violation,
  dropped connection) can trigger a bogus retry against the wrong
  dialect.
- **The audit-log pattern (insert/paginate/prune/nullable-UUID) is
  duplicated near-identically between Auction and Coinflip storage.**
- **`VertexPlugin.onEnable`/`reload`/`onDisable` hand-copy a ~20-30 line
  wiring block per economy feature,** and it's already visibly drifted:
  Trade's periodic sweep task isn't stored in a field the way Coinflip's/
  Shop's/Auction's equivalents are, so unlike those three, its interval
  can't be safely rescheduled from `/vertex reload`.
- **`SandBotManager`'s construction reads `shopManager`/
  `factionBankManager` fields directly,** relying entirely on
  `onEnable()`'s linear ordering with no compiler enforcement — correct
  today, but a future reorder could silently pass a `null` dependency.
- **`border()`/`noItalic()` menu-chrome helpers are duplicated verbatim
  across 7-8 menu classes** (Auction/Coinflip/Shop) — no shared `MenuUtil`
  despite a `util` package already existing.
- **"Add to inventory or drop the overflow" is reimplemented ~8 times**
  across Auction/Coinflip/Shop/Trade under three different names, with
  inconsistent `.clone()` usage before handing out the stack — a
  correctness landmine on top of the duplication.
- **`formatDuration(millis)` is triplicated within the Coinflip module
  alone** (command, menu, menu listener) despite an obvious shared home.
- **The Cannon module's per-tick ignition-counter-reset task runs
  unconditionally every tick (20 Hz), for the plugin's entire uptime,**
  regardless of whether `cannon.enabled` is true or a cannon has ever
  fired — cheap per-call, but permanent scheduler overhead for a feature
  that may never be used on a given server.
- **`trade/` is formatted in a drastically denser single-line style
  than the rest of the codebase** (whole methods, in one case a whole
  class, packed onto one line) — functionally fine, but defeats
  line-level diffing/blame and makes the module's trickiest logic
  (escrow chaining) harder to review than it needs to be.
