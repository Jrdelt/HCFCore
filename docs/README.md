# Vertex Documentation

Vertex is a Paper plugin that adds a full HCF
gameplay layer on top of **FactionsUUID**: armor-based kit classes, PvP
ability items, 1.8-style legacy combat, a combat-tag system,
faction-aware chat and nametags, rallies, a faction permission matrix,
faction upgrades, a shared faction bank, cosmetic tags, stackable
spawners, Chunk Collectors, a progressive schematic base-builder,
KOTH/Outpost captures, and a staff toolkit.

This is the detailed, developer-facing reference. If you just want a
quick tour of what the plugin does, see the [main README](../README.md)
instead — it covers the same features in a few lines each. Everything
here goes deeper: exact mechanics, every config key, edge cases, and
integration behavior.

## Contents

| Guide | Covers |
|---|---|
| [**Player Guide**](player-guide.md) | **Start here** — every feature and command a player can use |
| [Installation](installation.md) | Requirements, building from source, first deploy, upgrading, database migrations |
| [Configuration](configuration.md) | Full walkthrough of `config.yml`: database, chat, factions/upgrades, nametags, reboot, and reload semantics |
| [PvP & Combat](pvp-and-combat.md) | Combat tag, action bar, item cooldowns, no-pearl zones, Legacy Combat (1.8 PvP), Archer Tag |
| [Kits & Abilities](kits-and-abilities.md) | Kit classes, class effects, kit costs, the full ability item catalog and their mechanics |
| [Factions Integration](factions-integration.md) | FactionsUUID compatibility, chat placeholders, nametags, rallies, permissions, upgrades, bank, leader-leave protection |
| [Faction Leaderboards](faction-leaderboards.md) | `/f top`'s claimed, individually-aged spawner-value F Top, and `/f pvptop`'s KOTH/Outpost objective points |
| [Spawners & Collectors](spawners-and-collectors.md) | Spawner shop/economy, daylight and lava behavior, stacking, mob stacks, and Chunk Collectors |
| [Blueprint Base Builder](blueprints.md) | Automated `.schem` base building with FastAsyncWorldEdit + DecentHolograms |
| [Backpacks](backpacks.md) | Tiered offhand auto-storage: drop bonuses, filters, leveling, and custom models |
| [Player Trading](trading.md) | Secure two-player item, money, and XP escrow trades |
| [Sand Bots](sandbots.md) | Automated falling-block column filling |
| [Coinflips](coinflips.md) | 50/50 money, experience, and item wagers, claim stashes, self-ban, and the staff audit log |
| [Personal settings](settings.md) | Per-player optional announcement preferences |
| [Shop](shop.md) | Categorized, dynamic-price item trading |
| [Boosters](boosters.md) | How every bonus stacks into one effective figure, and `/boosters` |
| [Wands](wands.md) | Sell Wands and TNT Wands: dynamic-priced container selling and Gunpowder banking |
| [Mining Worlds](mines.md) | Stonewake and Bloodvein: region selection, ore generation, and regeneration |
| [GUI framework](gui-framework.md) | Configuring new menus: sizes, slots, materials, lore, sounds |
| [Auction House](auctionhouse.md) | Buy-it-now listings for any item, claim stashes, expiry, fees, and the staff audit log |
| [KOTH & Outposts](koth-and-outposts.md) | Faction capture rules, directions/focus, schedules, staff selection, holograms, rewards, and Outpost XP boosters |
| [Tags & Cosmetics](tags-and-cosmetics.md) | The `/tags` system: unlocking, equipping, nickname-match, the GUI |
| [Staff Tools](staff-tools.md) | Vanish, staff chat, staff-build, freeze, invsee/endersee, and death rollback |
| [Localization](localization.md) | Supported languages, `/language`, adding a translation, color/placeholder conventions |
| [Commands & Permissions](commands-and-permissions.md) | Every command and permission node in one place |
| [Integrations](integrations.md) | Exactly what each supported plugin unlocks, and what happens without it |
| [Placeholders](placeholders.md) | Every `%vertex_...%` PlaceholderAPI token Vertex provides for other plugins to read |
| [Architecture](architecture.md) | Package layout, internal design notes, and testing — for contributors |

## Conventions used throughout

- Config keys are written as dotted paths into `config.yml` (e.g.
  `pvp.combat-tag-seconds`) unless another file is named.
- Commands omit the leading `/` in prose but are shown in full in tables.
- "No effect without X installed" means the feature quietly does nothing
  rather than erroring — Vertex never requires an optional integration to
  boot.
- `/vertex reload` reloads `config.yml`, language files, kits, abilities,
  tags, spawner tuning, collector tuning, Backpack tiers, Blueprint
  templates, capture-event definitions, and Vertex upgrade definitions. It also rechecks all required
  and optional integrations, enabling Blueprints or Ghost Players if their
  dependencies became available after Vertex started. A full restart is
  still required after replacing a jar or switching the storage backend.

Found something these docs don't cover, or something that's out of date?
Check [issues.md](../issues.md) at the repo root for known open items, or
open an issue on [GitHub](https://github.com/Jrdelt/HCFCore).
