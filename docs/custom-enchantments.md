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

## Lucky Gems

There is one universal Lucky Gem rule: every Gem adds **+3.50%** and success is
capped at 100%. At the cap no Gem is consumed. Both old legacy Gem items and
old Mob Arena Gem items remain valid, so players do not lose existing stock.

Base Runes and Lucky Gems stack to 64. Identified Runes stack only if every
stored value matches, including enchant, level, tier, and success chance.

## Rune Catalog

Right-click a Simple, Elite, Rare, Legendary, or Mob Arena Rune category in
`/runes` to open its read-only catalog tab. The catalog provides tabs for all
categories and lists available levels, maximum-level status, proc and success
behavior, compatible equipment, restrictions, and source. Its back button
returns to the Rune Shop. Catalog items cannot be bought, identified, or
applied from that menu.

## Shop and staff commands

`/runes`, `/ce`, `/customenchants`, and `/enchant` open the Rune Shop.
Clicking buys one Rune or Lucky Gem and keeps the shop open. Shift-clicking
opens a bulk confirmation; a confirmed bulk purchase closes that confirmation
and reports the exact quantity and total cost.

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
  effect values, proc chance, success rate, and item icon.
- `arena-runes.yml` sets Mob Arena Rune price/currency and Arena effect
  behavior. It no longer has a separate Lucky Gem price or bonus.

The item PDC is the persistent record for identified Runes and equipment
enchants, so normal moves, trading, storage, and restarts retain the data.
