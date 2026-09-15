# Custom Enchantments and Runes

Vertex uses one direct-inventory Rune system. There is no enchant-application
GUI and no confirmation inventory for applying a Rune.

## Player flow

1. Obtain a base Rune from `/runes`, an event, or staff.
2. Right-click it in the air or on a block to identify it. Candle Rune items
   are never placeable.
3. Optional: drag a Lucky Gem onto the identified Rune to add **3.50%** to
   its stored success chance.
4. Drag the identified Rune onto compatible equipment in your own inventory.

A valid application consumes exactly one identified Rune. A successful roll
adds its formatted enchant line to the equipment; a failed roll consumes the
Rune without changing the equipment. Invalid, incompatible, duplicate, and
lower-level attempts leave both items untouched.

If an inventory is full when a base Rune is identified, Vertex still consumes
that one base Rune and drops the newly identified Rune at the player’s feet.
The dropped item is owner-protected to prevent an immediate theft.

## Rune presentation

All Rune items use small-caps text. Identified legacy Runes use their source
tier color: Simple gray, Elite yellow, Rare light purple, and Legendary red.
Mob Arena Runes are blue. Only a maximum-level Roman numeral is bold.

Identified Runes show their custom-enchant subtitle, effect value, proc chance
when applicable, success/failure chance, any Haven/Riftlands restriction, and
the direct-drag instruction. Applied equipment intentionally shows only the
tier-colored enchant name and level; it never adds an Arena section or Rune
statistics to armor lore.

## Normal Rune pools and live effects

The four normal Rune tiers are intentionally separate: every tier has five
unique Rune types, and a Rune ID appears in only one normal tier. Mob Arena
Runes remain a separate system and are not part of these pools.

| Tier | Default Rune types |
| --- | --- |
| Simple | Ore Sense, Crop Bounty, Featherbound, Ironhide, Scholar's Mark |
| Elite | Sky Stepper, Dasher, Soul Siphon, Ember Edge, Windrunner |
| Rare | Ravager, Aegis, Executioner, Reclaimer, Deadeye |
| Legendary | Phoenix Heart, Titan's Fury, Void Leech, Riftwalker, Stormcall |

Normal Runes now have live, configurable effects. A Rune must be on compatible
equipped gear for its effect to run. For movement Runes, the player crouches
while airborne. When more than one movement Rune is eligible, Vertex checks
their configured `priority` from highest to lowest. By default Sky Stepper
(`200`) runs before Dasher (`100`); Dasher can still be used if Sky Stepper is
missing, unavailable, blocked by combat, or on cooldown.

## Lucky Gems

There is one universal Lucky Gem rule: every Gem adds **+3.50%** and success is
capped at 100%. At the cap no Gem is consumed. Both old legacy Gem items and
old Mob Arena Gem items remain valid, so players do not lose existing stock.

Base Runes and Lucky Gems stack to 64. Identified Runes stack only if every
stored value matches, including enchant, level, tier, and success chance.

## Rune Catalog

Right-click a Simple, Elite, Rare, Legendary, or Mob Arena Rune category in
`/runes` to open its read-only catalog tab. The catalog provides tabs for all
categories and lists every level available from that category, maximum-level
status, per-level value/proc/success behavior, compatible equipment,
restrictions, and source. Its back button returns to the Rune Shop. Catalog
items cannot be bought, identified, or applied from that menu.

## Shop and staff commands

`/runes`, `/ce`, `/customenchants`, and `/enchant` open the Rune Shop.
Clicking buys one Rune or Lucky Gem and keeps the shop open. Shift-left opens
a confirmation for up to 64; shift-right opens a confirmation for the maximum
that fits and is affordable. A confirmed bulk purchase closes that confirmation
and reports the exact quantity and total cost.

Shop, confirmation and catalog views block bottom-inventory transfers as well
as top clicks, so shift/collect actions cannot stash items in a temporary menu.
The arena catalog tab is hidden when its module is unavailable.

Staff with `vertex.enchant.give` can use:

```
/enchant give <player> rune <simple|elite|rare|legendary> [amount]
/enchant give <player> gem [amount]
```

## Identified Rune material

An identified, non-seasonal Rune's physical item is always a dye-colored
candle matching its tier -- Simple gray, Elite yellow, Rare magenta,
Legendary red, Mob Arena blue -- regardless of whatever `material` that
enchant's level configures. Tier alone decides the candle color, never the
individual enchant. Seasonal Runes are exempt and keep whatever material
their own config (or a linked catalog item, see below) specifies.

Because of this, the Incinerator (and Auto-Incineration) only ever accepts
an **identified** Rune. A still-sealed Rune box is never eligible, no matter
its protection settings -- incinerating an unopened box would destroy value
the player hasn't even seen yet.

## Seasonal admin distribution (`/seasonal`)

Seasonal Runes are never rolled by a player; they're admin/event-distributed
only, via `/seasonal` (shares the `vertex.enchant.give` permission with
`/enchant give`):

```
/seasonal give <id> [level] [amount]
/seasonal item <material> <customModelData> [name]
/seasonal catalog save <id>
/seasonal catalog remove <id>
/seasonal catalog list
/seasonal roll item <enchant-id> [amount]
```

`/seasonal give <id> [level] [amount]` is the one command that covers every
kind of seasonal reward, checking three id spaces in order: a
`backpacks.yml` tier id (e.g. `fallen_crate`, built fresh every time since
each backpack needs its own instance id -- `[level]` is its starting level),
a seasonal Rune id from `customEnchants/runes.yml` (an identified copy at
`[level]`, default 1, `[amount]` copies), then a saved catalog item.

`/seasonal item` stamps a plain item with a material and custom model data
(and optional name) for quickly staging a crate reward's display/win item --
no lore or enchants, independent of everything else here.

### The seasonal item catalog

`/seasonal catalog save <id>` snapshots whatever's in the sender's hand --
enchantments, lore, custom model data, any other plugin's PDC, all of it --
exactly as-is into `seasonal-items.yml`, via Bukkit's native `ItemStack`
serialization. `/seasonal give <id>` then reproduces that exact item
forever after, both for handing to players and for re-dropping into a crate
plugin's reward-item slot when a season rolls over. `catalog remove`/`list`
round it out.

### Tying a seasonal ability directly to a real item

A seasonal level may set `catalog-item: <id>` in `runes.yml` to reference a
catalog entry. When set, `EnchantManager.createEnchantItem` bakes that
ability directly onto a clone of the catalog's saved item (its real
material, custom model data, lore, vanilla enchants all preserved) instead
of a generic placeholder icon -- the item is wearable and active immediately,
no separate apply step. This applies everywhere a seasonal item is created
(`/seasonal give`, `/seasonal roll item`, `/enchant give ... seasonalset`,
and the `/ce` Seasonal Set preview menu), automatically, once the referenced
catalog id has been saved. Until it has, that level logs a startup warning
and falls back to the placeholder icon so nothing is ever silently missing.

Enchants may also be grouped under `sets: <set-name>: enchants:` (instead of
loose in the flat `enchants:` block) purely to keep one season's whole set
together in the file. A `sets: <set-name>:` block may itself set a default
`material`/`custom-model-data`/`catalog-item` that every nested level
inherits unless it configures its own. The set name is config-organizational
only -- it's never part of an enchant's id and never shows up in a rune's
in-game name.

## Configuration

Every rune on the server -- SIMPLE/ELITE/RARE/LEGENDARY and ARENA alike --
lives in one file: `customEnchants/runes.yml`. Its top-level `runes:` keys
are the five `RuneTier`s; each tier sets its base Rune material, model data,
shop price/currency, and an `enchants:` block of every enchant it can roll
(compatible equipment, levels, effect values, proc chance, success rate,
item icon, roll `weight`, and live-effect behaviour). Each definition may set
`effect`, `priority`, `blocked-in-combat`, `enabled-zones`/`disabled-zones`,
`damage-source`/`target-filter`, and `tags`; each level may set numeric
`effect-settings` such as movement velocity or cooldown. The universal Lucky
Gem's material/model/price lives at the file's top level. A `legacy:` block
holds enchant IDs retained only so already-applied items keep rendering --
they carry no roll weight and can never be rolled again. Arena's enchants use
`per-level-value` + the tier's `level-count`/`level-buckets` to generate their
levels instead of hand-authoring twenty near-identical blocks, and the tier's
`random-success-range` re-rolls a fresh success chance on every identify.
Changes take effect after the normal Vertex reload.

KOTH/Outpost capture settings, which are unrelated to runes, live in their
own `zone-controls.yml` instead.

The item PDC is the persistent record for identified Runes and equipment
enchants, so normal moves, trading, storage, and restarts retain the data.
