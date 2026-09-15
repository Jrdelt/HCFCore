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

## Configuration

- `runes.yml` sets base Rune materials, model data, tier shop prices, roll
  tables, and the universal Lucky Gem’s material/model/price. Its former
  per-tier Lucky Gem effectiveness section is retired.
- `enchants.yml` sets each valid enchant, compatible equipment, levels,
  effect values, proc chance, success rate, item icon, and live-effect
  behaviour. Each definition may set `effect`, `priority`, and
  `blocked-in-combat`; each level may set numeric `effect-settings` such as
  movement velocity or cooldown. Changes take effect after the normal Vertex
  reload.
- `arena-runes.yml` sets Mob Arena Rune price/currency and Arena effect
  behavior. It no longer has a separate Lucky Gem price or bonus.

The item PDC is the persistent record for identified Runes and equipment
enchants, so normal moves, trading, storage, and restarts retain the data.
