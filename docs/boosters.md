# Boosters

Vertex stacks bonuses from many systems. `/boosters` is the one place that
answers "what bonus do I actually have right now, and why".

## The service owns no bonuses

This is the important property. The booster service grants nothing of its
own — every figure is read back from the system that already produces it:

| Source | Feeds | Read from |
|---|---|---|
| Backpack drop bonus | Ore Drop, Mob Drop | `BackpackManager.equippedDropBonusPercent` |
| Faction **spawner-rate** upgrade | Mob Spawn Rate | `FactionUpgradeManager.bonus` |
| Faction **mob-xp** upgrade | EXP | `FactionUpgradeManager.bonus` |

Because the tracker calls the same methods the gameplay code calls, it
cannot quote a number the server would not apply, and enabling it cannot
change what a player receives. Backpacks and faction upgrades behave exactly
as they did before.

Mine KOTH ownership, Mining Hot Zones, Resource Rush, and temporary event
rewards register as additional sources when built. Nothing in the stacking
or display code changes to accept them.

## Categories

Ore Drop, Sell, Mob Spawn Rate, Mob Drop, and EXP. A source may feed more
than one — a Backpack feeds both drop categories, because that is the single
bonus its manager applies to routed mining and mob drops alike.

## Stacking (`boosters.yml`)

Per category:

- `stacking: additive` — every active contribution adds together (default).
- `stacking: highest_only` — only the largest contribution counts.
- `max-percent: -1` — no ceiling; any other value clamps the combined total.

Only **Sell** ships with a ceiling, at `+500%` (a 6.00x maximum). A sell
bonus multiplies an already-dynamic price, so an uncapped stack is the
fastest way to wreck the economy. When a cap bites, `/boosters` shows the
raw total, the cap, and the effective result rather than silently showing
the smaller number.

`boosters.yml` decides only how contributions **combine**. Each value is
still configured where it is produced — Backpack tiers in `backpacks.yml`,
faction upgrades in `config.yml`.

## Inactive sources

A source that exists but does not currently apply is listed with its reason
rather than hidden — "why am I not getting this" is the question the screen
exists to answer. Current reasons: no Backpack equipped, not in a faction,
upgrade not purchased yet.

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
