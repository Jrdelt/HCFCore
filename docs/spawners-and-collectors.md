# Spawners, Mob Stacking & Chunk Collectors

Three systems, all in `spawners.yml` and `collectors.yml`, that together
form the grinder/farm economy: buyable stackable spawners, entity-count
control for what they produce, and automatic loot collection.

## Spawner shop & stacking

`/spawners` opens a shop GUI listing every mob type configured under
`spawners.yml`'s `mobs` section, each with its own price and death-drop
table (`drops` replaces the mob's vanilla drops entirely; leave it empty
to keep vanilla drops). Buy one, place it inside your own faction's
claimed land, and right-click it with a matching spawner item to stack
(shift-right-click deposits every matching spawner in your inventory at
once).

Right-clicking a spawner with an empty or non-matching hand opens a
management GUI to withdraw or sell spawners from the stack for
`sell-refund-percent` of the shop price.

**Stacking scales vanilla behavior, not a re-implementation of it** — a
stack's own `CreatureSpawner` block-entity fields (spawn count, nearby
entity cap, etc.) are scaled by stack size via `spawn-count-per-stack`
and `max-nearby-entities-per-stack` (each with a hard cap:
`max-spawn-count`, `max-nearby-entities-cap`), so a bigger stack really
does spawn more mobs per cycle using vanilla's own spawner logic, not a
manual re-spawn loop. The exceptions are Iron Golems (which vanilla
spawners cannot produce reliably) and sunlit spawners: with
`spawn-in-daylight: true` (the default), Vertex supplies the same
player-range and nearby-mob-capped fallback spawn cycle during daylight.
This keeps hostile spawners working outside without doubling normal
covered/dark-room spawning.

### Faction Spawner Rate upgrade

The **Spawner Rate** entry in `/f upgrades` scales both of those counts
for Vertex spawners located in the purchasing faction's own claim. It
also applies to the manual Iron Golem fallback. The normal spawner caps
remain the baseline and scale by the same earned rate, so the bonus takes
effect immediately without changing spawners in another faction's land.
Configure its levels, cost, and percentage in
`faction-upgrades.upgrades.spawner-rate`.

**Mining** requires Silk Touch if `silk-touch-required` is set, and only
works for members of the claim's owning faction (or staff-build) —
everyone else is blocked outright. `break-mode: drop-all` drops the whole
stack at once; `decrement` drops one spawner at a time, leaving the block
if any remain.

### Spawned mobs are AFK-farm safe

Every mob spawned from a tracked spawner has **all AI goals stripped**
(movement, look, jump, targeting) — it stands still and can never attack
a player, only moving if actually pushed by lava, a water current, or a
player. Zombies and skeletons are additionally made immune to sunlight,
since with no AI to seek shade an above-ground farm would otherwise burn
its own stock for free. Death drops are replaced entirely by that mob's
configured `drops` list.

**Iron Golems are a special case** — a vanilla monster-spawner block can
never actually produce one; golems have a built-in spawn-rule check a
spawner block can never satisfy (a known vanilla limitation, not
plugin-specific). Tracked Iron Golem spawners are instead ticked manually
every 5 seconds, respecting the same range/cap/count tuning as every
other mob type, and spawn the golem directly — tagged so it's
indistinguishable from a normal spawner-produced mob. Every other
configured mob type spawns through the normal vanilla mechanism.

### Claim integration

- A chunk containing active spawners can't be `/f unclaim`'d on its own —
  the plugin refuses and explains why.
- `/f unclaimall` isn't blocked (there's no practical way to protect
  specific chunks from a whole-faction unclaim); instead, every spawner
  in the released land drops as an item, and the player is told how many.
- A faction disbanding (voluntarily or automatically) drops every spawner
  in all of its claimed land the same way.
- Each spawner remembers which faction actually placed it — a chunk
  overclaimed by a *different* faction drops whatever spawners the
  previous owner had there, while a faction re-claiming its own land
  leaves its own spawners untouched.

## Mob stacking (`spawners.yml` → `mob-stacking`)

Nearby mobs of the same type merge into a single tracked entity instead
of letting entity count balloon once a stacked spawner is producing
dozens of spawns per cycle:

- A mob of a `stackable-types` entry within `merge-radius-blocks` of an
  existing stack of the same type (under `max-stack-limit`) merges into
  it instead of remaining separate — checked both at spawn and on a
  periodic sweep afterward, so a mob that free-falls or rides a water
  current down a multi-level shaft still merges once it settles near the
  bottom. Set `merge-radius-blocks` generously enough to cover your
  tallest shaft.
- Natural mobs and spawner-produced mobs never merge with one another.
  This preserves the correct loot table even when both reach the same
  grinder collection area.
- The merged entity shows a nametag (`display-format`, with
  `{count}`/`{name}` placeholders) once its count reaches 2, and never
  despawns from players being far away — only a restart or a manual clear
  removes it.
- **Killing a stack**: a direct player hit peels exactly **one** mob off
  — that mob's own drops and EXP come out, the stack shrinks by one, and
  the rest is untouched. Any other lethal cause (fire, lava, etc.) kills
  the **entire stack** at once: every mob's drops come out, batched into
  stacks of at most `drop-batch-size` to avoid flooding the ground with
  one item entity per mob — but grants **no EXP**, since only a direct
  player kill does.

`/vertex clearmobstacks` (`vertex.admin`) manually removes every
currently-tracked stacked mob across all loaded chunks — the equivalent
of a lagclear/entity-clear task's manual trigger. There's no automatic
scheduled version built in.

## Chunk Collector (`collectors.yml`)

A placeable Green Shulker Box that vacuums up mob-kill drops so a
grinder/farm doesn't carpet the ground in loot.

- Every item a **non-player** entity drops on death is tagged the instant
  it dies. Player deaths, manually-dropped items, and block-break drops
  are never tagged (they don't go through that code path), and EXP orbs
  are never touched.
- A tagged item spawning strictly **above** a same-chunk collector's Y
  level is absorbed immediately; a periodic sweep (every
  `scan-interval-ticks`) catches anything that fell through another path
  (e.g. it existed before the collector was placed).

### Storage & withdrawal

Storage is virtual with one shared capacity across every item type.
Right-click a collector to open a 27-slot GUI, one slot per unique stored
item type:

- **Left-click** an item to open a free amount-entry prompt — type the
  exact positive whole number you want and confirm.
- **Shift-click** withdraws `shift-withdraw-amount` at once (64 by
  default, or the remaining balance if less is stored).

`base-capacity` (default 50,000) is the total across every item combined
— 20,000 bones plus 30,000 rotten flesh fills a 50,000-capacity
collector. Raise it with the upgrade button, up to `max-upgrade-tier`, at
an increasing cost: `upgrade-cost-base * upgrade-cost-multiplier^tier`.

The summary icon shows tier, total stored items, distinct item type
count, and total capacity. Every label is configurable under `collector:`
in `lang/*.yml`. `collector.item-name` is the name written to the actual
Collector item, both when `/chunkcollector give` grants one and when a
placed Collector is broken.

`max-stored-material-types` (default 64) bounds how many distinct item
types can be written into one block's PDC. Once that cap is reached,
already-stored materials continue collecting normally; a new material is
left on the ground instead of risking oversized chunk metadata.

### Placement & protection

- Gated on faction claim ownership exactly like spawners (staff-build
  bypasses it), capped at `max-per-chunk` and `max-per-player`.
- Requires Silk Touch to break if `silk-touch-required` is set. Breaking
  one drops it with its stored counts and upgrade tier intact, so moving
  a collector never loses anything.
- Hoppers can't be placed within `hopper-block-radius` blocks of a
  collector, and one already touching the collector block can't push
  into or pull out of it — this prevents automating around the
  intentional manual-withdrawal design.
- A collector keeps its contents and tier when traded, but its ownership is
  reassigned to the player/faction that places it. This keeps the owner
  limit and land protections correct.
- Single-chunk `/f unclaim` is refused while it contains a collector.
  Overclaim, `/f unclaimall`, and faction disband drop collectors as their
  original items (including stored data) rather than exposing their
  contents to a new land owner.

There's no in-game shop — `/chunkcollector give <player>`
(`vertex.collector.give`) hands one out directly.
