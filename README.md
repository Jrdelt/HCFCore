<div align="center">

# Vertex

**An HCF gameplay layer for Paper, built on FactionsUUID.**

[Documentation](docs/README.md) · [Installation](docs/installation.md) · [Commands](docs/commands-and-permissions.md) · [Configuration](docs/configuration.md)

</div>

## What Vertex adds

- **Kits and PvP:** six armor classes, ability items, combat tags, Legacy
  Combat, Archer Tags, optional killable Villager Ghost Players, pearl restrictions,
  and persistent item cooldowns.
- **Factions:** rally points, a role-permission GUI, per-faction upgrades,
  a shared money/XP/TNT bank, faction chat, relation-aware nametags,
  faction KOTHs, and reward Outposts.
- **Automation:** stackable spawners that work in daylight, mob stacking,
  upgradeable Chunk Collectors, and progressive `.schem` Blueprint builds.
- **Server tools:** cosmetic tags, four languages, vanish/staff tools,
  inventory inspection, death rollback, scheduled reboots, tiered
  custom-model-data Backpacks, and money/experience/item Coinflips.

Feature messages, labels, and lore are configured in the language files.
See the [documentation index](docs/README.md) for exact behavior,
configuration, permissions, and operational limits.

## Requirements

| Dependency | Required for |
| --- | --- |
| Paper 1.21.10+ (built against 1.21.11) | Vertex itself |
| FactionsUUID 4.4+ | Vertex itself and all faction features |
| Vault | money costs, the money bank, and upgrade purchases |
| FastAsyncWorldEdit + DecentHolograms | Blueprint base building |
| FancyNPCs | optional Sand Bot displays |

WorldGuard, LuckPerms, PlaceholderAPI, EssentialsX,
MySQL/MariaDB is optional. Its exact effects are
listed in [Integrations](docs/integrations.md).

## Install

```bash
cd Vertex
./mvnw clean package
```

1. Put `Vertex/target/vertex-1.0.0.jar` and FactionsUUID in `plugins/`.
2. Start the server once. Vertex creates `plugins/Vertex/`, its local SQLite
   database, and configuration files. With both Blueprint dependencies
   installed, it also creates the Blueprint `schematics/` folder.
3. Configure it, then run `/vertex reload`. Use a full restart after changing
   jars, dependencies, or storage backend.

## Guides

| Guide | Use it for |
| --- | --- |
| [Factions](docs/factions-integration.md) | Permissions, rallies, upgrades, bank, and FactionsUUID interaction |
| [Spawners & Collectors](docs/spawners-and-collectors.md) | Spawners, daylight/lava behavior, mob stacks, and collectors |
| [Blueprints](docs/blueprints.md) | Schematic placement, builds, repairs, cooldowns, and recovery |
| [Backpacks](docs/backpacks.md) | Hidden auto-storage, drop bonuses, filters, leveling, and custom models |
| [Player Trading](docs/trading.md) | Secure item, money, and XP trades with escrow and recovery |
| [Coinflips](docs/coinflips.md) | 50/50 money, experience, and item wagers, self-ban, and staff audit log |
| [Shop](docs/shop.md) | Categorized, dynamic-price item trading |
| [Auction House](docs/auctionhouse.md) | Buy-it-now listings for any item, claim stashes, and staff audit log |
| [KOTH & Outposts](docs/koth-and-outposts.md) | Faction captures, schedules, navigation, holograms, rewards, and Outpost XP boosts |
| [Commands & permissions](docs/commands-and-permissions.md) | Every command and permission node |
| [Configuration](docs/configuration.md) | `config.yml` and reload behavior |
| [Full documentation](docs/README.md) | All remaining features and contributor notes |

## License

MIT — see [LICENSE](LICENSE).
