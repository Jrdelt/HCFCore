# Boosters

Vertex stacks bonuses from many systems. `/boosters` is the one place that
answers "what bonus do I actually have right now, and why".

## The service owns no bonuses

This is the important property. The booster service grants nothing of its
own — every figure is read back from the system that already produces it:

| Source | Feeds | Read from | Active while... |
|---|---|---|---|
| Backpack drop bonus | Ore Drop, Mob Drop | `BackpackManager.equippedDropBonusPercent` | A Backpack is equipped |
| Faction **spawner-rate** upgrade | Mob Spawn Rate | `FactionUpgradeManager.bonus` | Always, if purchased |
| Faction **mob-xp** upgrade | EXP | `FactionUpgradeManager.bonus` | Always, if purchased |
| Mine KOTH ownership | Ore Drop | `MineKothBoosterSource` | Your faction holds that mining world's KOTH point, and you're mining in it |
| Mining Hot Zone | Ore Drop | `HotZoneBoosterSource` | You're mining in a world [Hot Zones](mines.md) has currently made hot |
| Haven/Riftlands progression | Mob Drop | `ZoneBoosterSource` | You're inside Haven or Riftlands -- combines the zone's milestone boost and any Top-3 event winner boost |
| Arena KOTH/Outpost control | Mob Drop, EXP, Sell, Shop Discount | `ArenaControlBoosterSource` | Your faction currently controls that [KOTH or Outpost](koth-and-outposts.md) |
| Arena Runes | Mob Drop | `ArenaRuneListener`'s source | You're standing in the rune arena |

Because the tracker calls the same methods the gameplay code calls, it
cannot quote a number the server would not apply, and enabling it cannot
change what a player receives. Every source above behaves exactly as it did
before `/boosters` existed to show it.

## Categories

Ore Drop, Sell, Shop Discount, Mob Spawn Rate, Mob Drop, and EXP. A source
may feed more than one — a Backpack feeds both drop categories, because that
is the single bonus its manager applies to routed mining and mob drops alike.

## Stacking (`boosters.yml`)

Per category:

- `stacking: additive` — every active contribution adds together (default).
- `stacking: highest_only` — only the largest contribution counts.
- `max-percent: -1` — no ceiling; any other value clamps the combined total.

**Sell** and **Shop Discount** are the only categories with a ceiling —
`+500%` (a 6.00x maximum) for Sell, `+75%` for Shop Discount. Both multiply
an already-dynamic price, so an uncapped stack there is the fastest way to
wreck the economy. Every other category is uncapped by default. When a cap
bites, `/boosters` shows the raw total, the cap, and the effective result
rather than silently showing the smaller number.

`boosters.yml` decides only how contributions **combine**. Each value is
still configured where it is produced — Backpack tiers in `backpacks.yml`,
faction upgrades in `factions.yml`.

## Inactive sources

A source that exists but does not currently apply is listed with its reason
rather than hidden — "why am I not getting this" is the question the screen
exists to answer. Current reasons include: no Backpack equipped, not in a
faction, upgrade not purchased yet, faction doesn't hold that KOTH/Outpost,
not currently in a Hot Zone world, and outside Haven/Riftlands.

## Commands

| Command | Permission | Notes |
|---|---|---|
| `/boosters` | Open to all | Your own categories; click one for the per-source breakdown. |
| `/boosters inspect <player>` | `vertex.boosters.inspect` | The same view for someone else. The subcommand is only shown in the usage text to players who hold the permission. |

The subject must be online: boosters are live state, so an offline player's
breakdown cannot be read truthfully.

## Appearance

Both `/boosters` screens are defined in `gui/boosters.yml` — size, title,
slots, materials, names, lore, and sounds. See
[GUI framework](gui-framework.md) for the format. Chat messages (usage
errors, "that player went offline") stay in `en_us.yml`; GUI text lives with
the GUI.
