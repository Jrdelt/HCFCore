# Chunk Busters

Chunk Busters are four purchasable, destructive area-clear items — each
right-click use requires confirmation, clears blocks in a batched
background task (never in one tick), and never drops or refunds anything
it removes.

- **Upward Chunk Buster** — clears every column in the affected chunk,
  from the block you use it on upward to the world's build height.
- **Downward Chunk Buster** — clears every column in the affected chunk,
  from the world's minimum height up to the block you use it on.
- **Full Chunk Buster** — clears the entire chunk you use it in, top to
  bottom.
- **Single-Column Buster** — clears only the one 1×1 column at the exact
  block you use it on, full height — *not* the whole chunk.

Every type's affected area is always confined to the single chunk
containing the block you right-click — see `ChunkBusterType`'s class doc
for the exact reasoning behind reading "above/below the placement point"
as "that chunk's columns, above/below the placement Y" rather than a
radius or a fixed-size cuboid, which the source spec text left ambiguous.

## Where they work

| Location | Allowed? |
|---|---|
| Your own faction's claim (including your own Base Claims and Raid Claims — both are just "your faction's claim" for this purpose) | Yes, subject to the role-permission check below |
| Wilderness | Yes, for anyone — including factionless players — with **no** faction-permission check at all |
| Another faction's claim | Never |
| SafeZone / WarZone (`chunkbuster.yml`'s `disabled-claim-names`) | **Never — no admin bypass exists for this feature in these two zone types specifically.** |

## Combat and confirmation

- Blocked entirely while combat-tagged (`CombatManager.isTagged`) — no
  cooldown otherwise.
- Right-clicking a block with a Chunk Buster opens a confirmation GUI
  (`gui/chunkbuster.yml`); nothing is consumed or validated beyond an
  initial check at this point.
- Confirming **fully revalidates everything** — combat, zone, faction
  ownership, role permission, and that you're still holding the same
  Chunk Buster in your main hand — before consuming the item or touching
  a single block. The world can change while the GUI sits open (you could
  leave combat and re-enter it, lose the claim, etc.), so nothing from the
  first check is trusted at confirm-time.
- If a tracked spawner exists anywhere in the affected chunk, a
  noticeably different confirmation is shown instead: a red warning
  title, an extra spawner-warning banner item, and a differently
  materialed/labeled confirm button (`confirm-spawner-warning` in
  `gui/chunkbuster.yml`) — entirely config-driven, no changes to the menu
  framework itself.
- The item is consumed the instant confirmation succeeds, before any
  block is touched — not before, and not after processing finishes.

## What gets removed

- Bedrock is **always** protected, in every type, and confirmation can
  never override it. `chunkbuster.yml`'s `protected-blocks` list adds any
  other indestructible/system blocks (barriers, command blocks, portal
  blocks, structure blocks, etc.) to that same unconditional protection.
- Containers (chests, furnaces, barrels, shulker boxes, ...) are destroyed
  along with their contents — never opened, never dropped, never saved
  anywhere.
- Tracked spawners inside the affected area are removed via
  `SpawnerManager.remove` (the same "untrack + clean up" path a sold/
  broken spawner uses) — not the sell/withdraw path, since this is
  destructive with no payout.
- **Entities are never touched.** Chunk Busters only ever remove blocks —
  mobs, dropped items, armor stands, everything else is left exactly
  where it is.
- No item or block drops of any kind, for anything removed.

## Batched processing and restart behavior

A Full/Upward/Downward Chunk Buster in a tall world can touch on the
order of 98,000 blocks (16×16 columns × full build height) — clearing
that in a single tick would freeze the server. `ChunkBusterManager`
precomputes every position to remove (`ChunkBusterArea`, kept entirely
free of Bukkit types so its coverage is unit-testable directly) into a
plain in-memory queue, then drains it in fixed-size batches on a
`runTaskTimer`, mirroring `MineRegenQueue`'s bounded-drain shape.

**Batching parameters** (`chunkbuster.yml`'s `processing` section):
`blocks-per-tick: 1000`, `period-ticks: 1`. At that rate a worst-case full
chunk (~98,000 blocks in a -64..320 world) finishes in roughly 100 ticks
— about 5 seconds at 20 TPS — matching the "roughly 5-6 seconds" target;
a Single-Column Buster (at most a few hundred blocks) finishes in well
under a second regardless.

**The area lock.** While an operation is in flight, its entire chunk
footprint (not just the sub-area being cleared — the whole chunk, for
every type including Single-Column, out of caution) is locked against
block placement and breaking: new `BlockPlaceEvent`/`BlockBreakEvent`
handlers in `ChunkBusterListener` cancel any attempt inside a chunk with
an operation currently running.

**The persisted operation lock.** This is the one genuinely new
mechanism in this phase. It records an interrupted operation for staff
visibility, but it does **not** currently resume it after a restart. Here's
how it works:

1. The instant an operation is validated and about to start, a row is
   inserted into `chunk_buster_operations` (`world`, `chunk_x`, `chunk_z`,
   `type`, `started_at`, `status = 'IN_PROGRESS'`) — synchronously, before
   the item is consumed or any block is touched. This is a plain,
   dialect-agnostic `INSERT`, mirroring how `ClaimStorage`/`ShieldStorage`
   already do all of their (rare, low-frequency) writes directly on the
   calling thread rather than through a `CompletableFuture`.
2. The batched `runTaskTimer` then runs to completion. When the queue
   drains, the row is deleted — a *completed* operation always cleans up
   its own row.
3. If the server goes down (crash or normal shutdown) while a row still
   exists, the `BukkitTask` simply disappears with it — no code runs to
   "notice" the interruption at the moment it happens.
4. On the next boot, `ChunkBusterManager.recoverAbandonedOperations()`
   runs once, before any player can trigger a new operation: it loads
   every row still in `chunk_buster_operations` (there should never be
   more than a handful, since a normal shutdown or crash is rare), logs a
   console warning naming the type/world/chunk, and deletes the row.
   **The operation is never resumed** — whatever blocks were already
   removed before the restart stay removed, and the rest of the affected
   area is simply left as-is. The row's own deletion *is* what "clears
   the lock on startup" means here: the in-memory area-lock `Set` a fresh
   `ChunkBusterManager` starts with is already empty by construction, so
   the only state that could otherwise look "still locked" to anything
   checking later is that durable row — and recovery is what removes it.

## Faction role permission

Faction leaders configure the **Use Chunk Busters** row inside the existing
`/f permissions` (or `/f perms`) GUI. It uses the same green/allowed,
red/denied rank matrix as collectors, banks, and spawners; there is no
separate Chunk Buster permission command.

The default rank settings live in `config.yml` under
`rally.permission-gui.roles`: admins and moderators are allowed; members
and recruits are denied. Wilderness use never checks a faction rank.

The permission check is revalidated at confirm-time, identically to
combat — see "Combat and confirmation" above.

## Acquisition

Chunk Busters are normal, paginated product tiles in `/shop` → **Raiding
Materials**. They use their configured fixed prices from `chunkbuster.yml`
(not the material market's dynamic prices), and left-click buys one. The
physical item and its shop icon are always a glowing magma block; its PDC
type distinguishes upward, downward, full, and single-column variants.

Staff may also run `/chunkbusters give <player> <upward|downward|full|single-column> [amount]`
with `vertex.chunkbuster.give`. `/chunkbuster` is kept as an alias.

## Logging

Every completed use is recorded in `chunk_buster_log` (`player_uuid`,
`type`, `world`, `x`, `y`, `z`, `created_at`) — **not** a block-removed
count, per spec. There is no `/f logs`-style reader command in this
codebase yet, so this phase only writes the rows in the right shape for a
future reader, the same scope choice `ShieldManager`'s event log made.

## Data model

New tables (`me.vertex.core.chunkbuster.ChunkBusterStorage`), added to
`StorageMigrator`:

- `chunk_buster_operations` — the persisted operation lock
  described above. Should normally be empty; a leftover row only exists
  between a crash/shutdown and the next boot's recovery pass.
- `chunk_buster_log` — the write-only use log described above.

## Commands & permissions

| Command | Permission | Notes |
|---|---|---|
| Right-click a block with a Chunk Buster | *(none — gated by zone/combat/role checks in-code)* | Opens the confirmation GUI. |
| `/chunkbusters give <player> <type> [amount]` | `vertex.chunkbuster.give` | Staff distribution command. |

## Configuration

- `chunkbuster.yml` — per-type enable/price/name/lore/custom-model-data,
  the global protected-block list, `disabled-claim-names`
  (SafeZone/WarZone, independent from `abilities.disabled-claim-names` —
  see below), and batching parameters. Chunk Busters always render as
  glowing magma blocks.
- `gui/chunkbuster.yml` — the confirmation GUI, including the flashy
  spawner-warning variant, following the same `MenuLayout`-driven format
  as every other Vertex GUI (see [GUI framework](gui-framework.md)).
- `lang/en_us.yml`'s `chunkbuster:` section.

### Why `chunkbuster.yml` has its own `disabled-claim-names` instead of reusing `abilities.disabled-claim-names`

The existing `abilities.disabled-claim-names` list (`config.yml`) ships
with only `safezone` in it by default — `warzone` is left as a
documented-but-not-enabled example for admins who named their WarZone
faction that. Chunk Busters need **both** blocked by default, with no
admin bypass in either, so this phase gave them their own independently
configurable list (defaulting to `[safezone, warzone]`) rather than
silently depending on an admin having already extended a different
feature's list to cover a requirement that feature never had.
