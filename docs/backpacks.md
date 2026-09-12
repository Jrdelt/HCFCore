# Backpacks

A Backpack is an item, not a block — it carries its own hidden inventory
wherever it goes. Hold one in your **offhand** and **Sneak + Right Click
in the air** to open its controls.

## Item model

Backpacks use a normal Bukkit `item-type` plus numeric
`custom-model-data`; they do not require ItemsAdder or any other
custom-item plugin. Configure those two values per tier to match your
resource pack. A value of `0` leaves the item on its vanilla model.
Existing Backpacks adopt the configured material/model the next time
their data is saved (for example, after opening and closing them).

## Giving one out

There's no in-game shop for these yet: `/backpack give <player> <tier>
[level]` (`vertex.backpack.give`) hands one out directly, starting at
`level` (default 1) -- values below 1 become level 1, with no
upper level cap. Tiers are defined in `backpacks.yml`
(see [Configuration](configuration.md) for the full key reference) —
each has an item type, custom model value, and its own
drop-bonus stat.

## The GUI

The GUI is always three rows and contains only the Backpack display,
**Empty Backpack**, and **Upgrade Backpack** controls. Stored items are
never displayed or manually moved into the Backpack. Storage lives entirely
on the item itself (in its PersistentDataContainer, alongside its
tier/level data) —
there's no separate database table to keep in sync, since unlike a
[Chunk Collector](spawners-and-collectors.md) there's no fixed world
location to index it by.

Storage capacity is measured in **individual items**, not used slots. A
64-stack therefore uses 64 capacity. The shipped level-one capacity is
1,250 items (`base-item-capacity`), and `item-capacity-per-level` adds
capacity on every upgrade. Items that do not fit remain on their normal routing
path; account-bound command/shop rewards use Vertex's delivery inbox instead of
being thrown on the ground. The Backpack's lore is rebuilt as soon as items are
automatically stored or emptied, so its displayed `contents` value always
matches what it currently holds.

- **Empty Backpack** fills only the available inventory space. Everything else
  remains inside the Backpack; click Empty again after freeing more room. It
  never throws Backpack contents onto the ground.
- **Upgrade Backpack** spends Vault money and reopens the GUI. The default
  `upgrade-cost` curve starts at `$500` and grows by `1.10` per level
  through level 50; from there the per-level multiplier ramps up
  gradually (not a sudden jump) until it caps at `2.0` (double) once the
  Backpack reaches level 150, and stays at that cap for every level
  after. Contents carry over untouched -- there's nothing to resize,
  since capacity is a count, not a slot array. There is no configured
  Backpack level cap; upgrades stop only when the player chooses not to
  buy another one.

## Automatic collection and filters

With a valid single Backpack in the offhand, normal drops from blocks listed
under `auto-store.mining-materials` are routed into it. The block's ordinary
Fortune/Silk Touch calculation happens first; Vertex then applies the
Backpack tier/level bonus to those resulting drops. The bundled tiers all
start at a guaranteed **+25%** bonus at level 1, compounding higher every
level from there (see [Leveling and the drop-bonus stat](#leveling-and-the-drop-bonus-stat)
below) -- so a fresh Backpack gives a modest boost, and a heavily upgraded
one grows until the configured `max-drop-bonus-percent` cap (250% by default).
This means enchantment and Backpack bonuses
stack without replacing each other.

Player-killed, non-player mob drops are handled the same way when
`auto-store.mob-drops` is enabled. A nearby Chunk Collector still receives
any overflow that did not fit in the Backpack.

Whenever a Backpack accepts one or more items, the player receives the
`backpack.store-action-bar` action bar. Its configurable placeholders are
`{backpack_name}`, `{tier}`, `{backpack_level}`, `{used_storage}`, and
`{storage}`. It uses the post-collection total (including any configured
drop bonus), and does not appear when all drops were filtered or the bag was
already full.

`/filter add <material>` and `/filter remove <material>` edit the player's
persistent Backpack filter; `/filter list` displays it and `/filter clear`
removes every filter. The older `/filter <material>` toggle still works.
Vertex intercepts these forms before Essentials. A filtered configured drop is
discarded only while that player has a Backpack equipped. Without one, it is
left to Minecraft's normal ground-drop behavior.

## Storage backend

A Backpack's entire state — tier, level, and its stored contents —
lives in the item's own PersistentDataContainer, not in
Vertex's database. `/vertex storage local|mysql` (see
[Configuration](configuration.md#database)) never touches Backpacks at
all: there's nothing for it to migrate, so switching between SQLite and
MySQL is always safe for existing Backpacks, and can never lose their
data. A Backpack survives exactly the way any other item does — through
the world/player data Minecraft itself saves.

## Storage safety

Each Backpack has its own non-stacking identity. Keep it as one item: a
legacy stack must be split before it can be opened. Backpacks also cannot
be stored inside another Backpack, preventing recursive item-data bloat
and duplication-prone nested inventories.

Storage isn't a fixed-size slot array -- it's one entry per distinct
material, each able to hold far more than a vanilla max stack, so
`item-capacity-per-level` is the real, only ceiling at every level. A
Backpack that's already over its capacity (for example, after an admin
lowers it in `backpacks.yml`) is never trimmed to fit: it just stops
accepting new items until emptied or upgraded back above the new cap.
Nothing already earned is ever silently deleted.

## Leveling and the drop-bonus stat

Every tier's drop bonus **compounds** with level, like interest, rather
than adding a flat amount each time:
`drop-bonus-base-percent × (1 + drop-bonus-per-level-percent / 100) ^ (level - 1)`.
A level 1 Backpack only has its base bonus; by dozens of levels in, the
compounding has pulled it dramatically ahead of a fresh one, rewarding
real investment instead of the bonus only creeping up by a fixed amount
each time. Higher tiers compound faster (a larger
`drop-bonus-per-level-percent`), keeping their advantage at every level.
It applies to every item routed by automatic ore mining or player-killed
mob collection. Upgrades are purchased with Vault money; Backpacks never
store or award experience.

## Configuration reference (`backpacks.yml`)

| Key | Purpose |
|---|---|
| `enabled` | Master on/off switch |
| `base-item-capacity` / `item-capacity-per-level` | Individual-item capacity at level 1 and extra capacity per level; stack amounts, not slots, are counted -- this is the only storage ceiling |
| `max-drop-bonus-percent` | Hard cap on a Backpack's calculated extra-drop bonus (default 250%) |
| `auto-store.mining-materials` | Block materials whose normal drops are automatically routed to an equipped Backpack |
| `auto-store.mob-drops` | Whether player-killed non-player mob drops are automatically routed |
| `upgrade-cost.base` | Cost to upgrade from level 1 to level 2 (default `500`) |
| `upgrade-cost.easy-through-level` / `easy-multiplier` | Affordable flat curve through this level (default `50` levels at `1.10`) |
| `upgrade-cost.ramp-through-level` | The level by which the per-level multiplier has gradually climbed to and capped at `max-multiplier` (default `150`) |
| `upgrade-cost.max-multiplier` | The per-level multiplier cap the ramp climbs to (default `2.0`, i.e. double) |
| `tiers.<id>.display-name` | The item's name, shown in-game |
| `tiers.<id>.item-type` | Bukkit material to use (for example `LEATHER`) |
| `tiers.<id>.custom-model-data` | Positive resource-pack model number; `0` uses the vanilla item model |
| `tiers.<id>.drop-bonus-base-percent` | Automatic-collection bonus at level 1 |
| `tiers.<id>.drop-bonus-per-level-percent` | The percentage the bonus *compounds* by every level after that (not a flat add-on) -- it applies after normal mining-drop calculations |

After changing this file, run `/vertex reload` or restart the server.
