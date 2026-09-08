# Sell Wands & TNT Wands

Right-click a **Chest** or a **Chunk Collector** with a wand. Wands work in
your own claims, neutral claims, and enemy claims alike.

Remaining uses are stored on the item itself, so the count follows the wand
through restarts, trades, chests, and enderchests — there is no server-side
table that could drift out of sync with what the lore says.

| Command | Permission | Notes |
|---|---|---|
| `/wand give <player> <tier> [uses]` | `vertex.wand.give` | No in-game shop for wands. `uses` defaults to the tier's configured count. |

## Sell Wands

Sells the container's eligible contents and pays the user's personal
balance. Shipped tiers: **iron** (50 uses), **gold** (150), **diamond**
(500) — all configurable in `wands.yml`.

The payout summary reports the total only, never a list of items. If nothing
eligible was found, it says so and the use is not spent.

### Containers cannot dodge dynamic pricing

This is the point of the feature, not a detail. Selling is priced through
the shop **one unit at a time**, walking the price down as the batch is
sold, so emptying a full container earns exactly what selling the same
amount by hand would. A single flat rate applied to the whole container
would make a chest a way to sell at the pre-sale price — it isn't.

Splitting a sale across two wand uses earns the same as doing it in one.

### What never gets sold

- **Anything carrying custom item data** — a named, enchanted, or
  model-data item that merely shares a material with a shop entry is not
  that entry. This is why a wand can never sweep up your custom gear, and
  why Vertex's own items (wands, Backpacks, Chunk Collectors) are safe
  without needing to be listed anywhere.
- **Materials on `sell-wands.never-sell`** — server-controlled protection.
  Ships with `SPAWNER` and `BEDROCK`.
- **Anything the shop does not trade.**

Ineligible items are left untouched in the container.

## TNT Wands

Converts Gunpowder in the container into TNT and banks it directly into the
user's [faction TNT bank](factions-integration.md#faction-bank).

`gunpowder-per-tnt` defaults to 5. Set `require-sand: true` to also consume
`sand-per-tnt` (4) per TNT, matching the vanilla recipe.

**A full TNT bank costs the player nothing.** No use is spent, no Gunpowder
is taken, and the container is not partially processed — the wand simply
reports that the bank is full. If the bank has *some* room, only as much TNT
as fits is converted.

If the bank write fails after the Gunpowder was taken, the TNT is handed
back as items rather than lost.

## Transaction safety

- A container being processed is locked for the duration, so two players
  cannot empty the same chest at once and a click repeat cannot double-fire.
- Nothing is removed, no use is spent, and no money or TNT moves unless the
  whole operation can complete.
- Sell Wand sales and TNT Wand conversions are logged to console with the
  player, the amounts, and the tier used.

## Sell bonuses

Sell Wand payouts run through the shared Sell Bonus in
[Boosters](boosters.md). Nothing contributes to that category yet, so the
current multiplier is exactly 1.0x — it is wired now so Resource Rush and
event boosters apply to wands, the shop, and collectors simultaneously
rather than each having to remember them.
