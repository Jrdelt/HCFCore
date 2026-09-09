# Spawners, Mob Stacking & Chunk Collectors

Three systems, all in `spawners.yml` and `collectors.yml`, that together
form the grinder/farm economy: buyable stackable spawners, entity-count
control for what they produce, and automatic loot collection.

## Spawner shop & stacking

Buyable spawner blocks are reached from the **Spawners & Mob Drops**
category in [`/shop`](shop.md) — not a standalone command — via the button
in that category's top row. It lists every mob type configured under `spawners.yml`'s `mobs`
section, each with its own price and death-drop table (`drops` replaces
the mob's vanilla drops entirely; leave it empty to keep vanilla drops).
Buy one, place it inside your own faction's claimed land, and right-click
it with a matching spawner item to stack (shift-right-click deposits
every matching spawner in your inventory at once).

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
its own stock for free. Spawner-produced mobs are protected from ordinary
fire, fire ticks, and magma-block heat. **Lava is not cancelled**: it
remains a valid grinder kill method. Death drops are replaced entirely by
that mob's configured `drops` list.

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
- An overclaim **does not drop or destroy spawners**. They stay in place
  and ownership transfers to the faction that now owns the land, allowing
  the new faction to manage them normally.


### Vertex has the final say over its own spawners

`override-other-plugins` (default true) reinstates a spawn from a
Vertex-tracked spawner that another plugin cancelled — a mob limiter, a
rival stacker, a region protector. Without it a player's paid-for spawner
can sit silently dead with nothing to point at.

Only spawns from Vertex-tracked spawners are reinstated; other plugins'
spawners are left alone, as is Vertex's own stacking merge — that cancel is
*how* a merge happens, so undoing it would duplicate the mob alongside the
stack it was just folded into.

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

`max-stored-material-types` is fixed at **24**, matching all 24 material
slots in the GUI. Once that cap is reached, a tagged farm drop of a new
material type is destroyed instead of being left on the ground; this bounds
PDC growth and prevents item-entity lag. Already-stored materials continue
collecting normally.

Malformed Collector PDC values are normalized on read: tier, per-item
counts, total capacity, and distinct type count are capped by the active
configuration. Collector lookup is indexed by chunk, so item collection and
hopper checks do not scan every Collector on the server.

### Placement & protection

- Gated on faction claim ownership exactly like spawners (staff-build
  bypasses it), capped at `max-per-chunk` and `max-per-player`.
- Does **not** require Silk Touch. Breaking one drops it with its stored
  counts and upgrade tier intact, so moving a collector never loses
  anything.
- If a role is denied **Open Collectors** in `/f permissions`, its
  right-click is cancelled before Minecraft can open the underlying Shulker
  Box inventory; it cannot be used as a vanilla Shulker workaround.
- Hoppers can't be placed within `hopper-block-radius` blocks of a
  collector, and one already touching the collector block can't push
  into or pull out of it — this prevents automating around the
  intentional manual-withdrawal design.
- A collector keeps its contents and tier when traded, but its ownership is
  reassigned to the player/faction that places it. This keeps the owner
  limit and land protections correct.
- Unclaiming or disbanding does not change, drop, or de-tag a placed
  collector: it remains a Chunk Collector with all stored contents intact.
  When another faction claims that chunk, the Collector remains in place
  and its faction ownership transfers to the new claim owner.

There's no in-game shop — `/chunkcollector give <player>`
(`vertex.collector.give`) hands one out directly.
