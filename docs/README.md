# Vertex Documentation

Vertex is a Paper plugin that adds a full HCF
gameplay layer on top of **FactionsUUID**: armor-based kit classes, PvP
ability items, 1.8-style legacy combat, a combat-tag system, a live
sidebar scoreboard, faction-aware chat and nametags, faction rallies,
faction upgrades, cosmetic tags, stackable spawners, chunk collectors, an automated
schematic base-builder, and a staff toolkit.

This is the detailed, developer-facing reference. If you just want a
quick tour of what the plugin does, see the [main README](../README.md)
instead — it covers the same features in a few lines each. Everything
here goes deeper: exact mechanics, every config key, edge cases, and
integration behavior.

## Contents

| Guide | Covers |
|---|---|
| [Installation](installation.md) | Requirements, building from source, first deploy, upgrading, database migrations |
| [Configuration](configuration.md) | Full walkthrough of `config.yml`: database, scoreboard, chat, factions/upgrades, nametags, reboot, and reload semantics |
| [PvP & Combat](pvp-and-combat.md) | Combat tag, action bar, item cooldowns, no-pearl zones, Legacy Combat (1.8 PvP), Archer Tag |
| [Kits & Abilities](kits-and-abilities.md) | Kit classes, class effects, kit costs, the full ability item catalog and their mechanics |
| [Factions Integration](factions-integration.md) | Chat/scoreboard placeholders, nametag coloring, rallies, permission and upgrades GUIs, leader-leave protection |
| [Spawners & Mob Stacking](spawners-and-collectors.md) | The spawner shop and economy, stack scaling, mob AI stripping, mob stacking, Chunk Collectors |
| [Blueprint Base Builder](blueprints.md) | Automated `.schem` base building with FastAsyncWorldEdit + DecentHolograms |
| [Tags & Cosmetics](tags-and-cosmetics.md) | The `/tags` system: unlocking, equipping, nickname-match, the GUI |
| [Staff Tools](staff-tools.md) | Vanish, staff chat, staff-build, freeze, invsee/endersee, and death rollback |
| [Localization](localization.md) | Supported languages, `/language`, adding a translation, color/placeholder conventions |
| [Commands & Permissions](commands-and-permissions.md) | Every command and permission node in one place |
| [Integrations](integrations.md) | Exactly what each supported plugin unlocks, and what happens without it |
| [Architecture](architecture.md) | Package layout, internal design notes, and testing — for contributors |

## Conventions used throughout

- Config keys are written as dotted paths into `config.yml` (e.g.
  `pvp.combat-tag-seconds`) unless another file is named.
- Commands omit the leading `/` in prose but are shown in full in tables.
- "No effect without X installed" means the feature quietly does nothing
  rather than erroring — Vertex never requires an optional integration to
  boot.
- Everything under `kits.yml`, `abilities.yml`, `tags.yml`, `spawners.yml`,
  `collectors.yml`, `blueprints.yml`, `config.yml`, and `lang/*.yml`
  reloads live with `/vertex reload` — no restart needed for content or
  message changes.

Found something these docs don't cover, or something that's out of date?
Check [issues.md](../issues.md) at the repo root for known open items, or
open an issue on [GitHub](https://github.com/Jrdelt/HCFCore).
