<div align="center">

# Vertex

**A complete HCF gameplay layer for Paper, built on FactionsUUID.**

Kit classes · PvP abilities · Legacy combat · Faction rallies · Cosmetic tags
Stackable spawners · Chunk collectors · Automated base building · Staff toolkit

[**📖 Full Documentation**](docs/README.md) · [Installation](docs/installation.md) · [Commands](docs/commands-and-permissions.md) · [GitHub](https://github.com/Jrdelt/HCFCore)

</div>

---

Vertex turns a plain FactionsUUID server into a full HCF experience: six
armor-based kit classes with their own PvP ability items, 1.8-style
legacy combat, a combat-tag system, a live sidebar scoreboard,
faction-aware chat and nametags, a stackable spawner economy, automatic
loot collection, and a complete staff toolkit — all configurable, all
reloadable live, and all documented in depth in [`docs/`](docs/README.md).

This page is the quick tour. For exact mechanics, every config option,
and what each integration does, see the [full documentation](docs/README.md).

## Requirements

| | |
| --- | --- |
| **Required** | Paper 1.21.10+, [FactionsUUID](https://www.spigotmc.org/resources/factionsuuid.1035/) |
| **Optional** | MySQL 5.7+ / MariaDB (only if you'd rather not use the built-in local database), Vault, WorldGuard, LuckPerms, PlaceholderAPI, EssentialsX, FastAsyncWorldEdit + DecentHolograms |

**No database setup needed.** Vertex saves everything to a local file
(`plugins/Vertex/vertex.db`) out of the box. Point it at a MySQL server
instead only if you need several servers to share the same data.

Every optional plugin only unlocks extra behavior — Vertex runs fine
without any of them. See [Integrations](docs/integrations.md) for exactly
what each one does.

## Quick start

```bash
./mvnw clean package
```

1. Drop the shaded jar from `Vertex/target/` into `plugins/`, next to `FactionsUUID.jar`.
2. Start the server. You'll see the Vertex banner in the console with the
   running version, and everything works immediately — data goes into a
   local `plugins/Vertex/vertex.db` file with no setup.
3. Optionally tune `plugins/Vertex/config.yml`, then run `/vertex reload`.
   To use MySQL instead of the local file, set `storage.type: mysql` and
   fill in the `mysql` section.

Full steps, upgrade notes, and database details are in
[Installation](docs/installation.md).

## Features

### Kits & Abilities

- **Six kit classes** (Archer, Miner, Bard, Diamond, Rogue, Mage), each
  with a free tier and a permission-gated donator tier — armor-based, so
  a class is recognized by what a player is wearing, not a hidden state.
- **Passive class effects** — wearing a kit's exact armor set grants
  buffs like Speed, Haste, or Invisibility after a short warmup.
- **21 PvP ability items** — pearl-blocking, backstabs, mage debuffs, a
  grappling hook, a teleport-on-hit ninja star, party buffs, and more,
  each with its own cooldown and full customization.
- Money and/or item costs, per-kit permissions, and an in-game kit
  creator (`/kit create`).

→ [Kits & Abilities](docs/kits-and-abilities.md)

### PvP & Combat

- **Combat tag system** with a live action bar, a shortened tag on a
  kill, and configurable commands blocked while tagged.
- **Legacy Combat** — an optional full 1.8 PvP restoration: instant
  attacks, a custom weapon-damage table, flat armor reduction, no
  offhand, tunable knockback, slow health regen, and admin-defined
  golden apple effects.
- **Archer Tag** — arrows mark victims, stacking bonus damage and a
  faction-wide melee bonus.
- Persistent, logout-proof cooldowns on pearls and golden apples, with
  no-pearl safezones enforced at both the throw and the landing.

→ [PvP & Combat](docs/pvp-and-combat.md)

### Factions

- **Faction-aware chat and scoreboard**, with PlaceholderAPI support
  alongside built-in placeholders.
- **Relation-colored nametags** — your faction is green, allies are
  purple, everyone else is red, rendered independently for every viewer.
- **Rally points** (`/f rally`) with a live compass and distance bossbar.
- **A visual permission-matrix GUI** for faction leaders, extending
  FactionsUUID's own role permissions, with clear green/red state panes,
  small-caps labels, and `/f perms` / `/f permissions` completion.

→ [Factions Integration](docs/factions-integration.md)

### Economy & Automation

- **A stackable spawner shop** (`/spawners`) with per-mob pricing, custom
  drop tables, and vanilla-accurate stacked spawn rates.
- **Mob stacking** keeps entity counts sane on busy grinders.
- **Chunk Collectors** — a placeable block that vacuums up mob-kill
  drops into shared, upgradeable storage.
- **Automated Blueprint base building** — place an item, and a full
  `.schem` structure builds itself inside your claim over time (needs
  FastAsyncWorldEdit + DecentHolograms).

→ [Spawners & Collectors](docs/spawners-and-collectors.md) · [Blueprints](docs/blueprints.md)

### Cosmetics

- **Equippable tags** with gradient colors, a searchable/sortable GUI,
  and optional name recoloring to match.

→ [Tags & Cosmetics](docs/tags-and-cosmetics.md)

### Staff & Administration

- **Vanish, staff chat, staff-build, and freeze**, each independently
  toggleable, plus a one-command combined staff mode.
- **Invsee/Endersee** for live inventory and ender chest inspection.
- **Death rollback** — restore a player's items from any of their last
  20 deaths.
- **Scheduled reboots** with broadcasted countdown reminders.

→ [Staff Tools](docs/staff-tools.md)

### Everything else

- **Four languages** out of the box (English, Spanish, Portuguese,
  German), with per-player language selection and easy translation.
- Every command, permission, and config option is documented in
  [`docs/`](docs/README.md) — nothing here is hidden or undocumented.

→ [Localization](docs/localization.md)

## Documentation

| | |
| --- | --- |
| [Full documentation index](docs/README.md) | Start here |
| [Installation](docs/installation.md) | Requirements, build, deploy, upgrading |
| [Configuration](docs/configuration.md) | Every `config.yml` option explained |
| [Commands & Permissions](docs/commands-and-permissions.md) | Every command and permission node |
| [Integrations](docs/integrations.md) | What each supported plugin unlocks |
| [Architecture](docs/architecture.md) | Package layout and internals, for contributors |

## License

MIT — see [LICENSE](LICENSE).
