<div align="center">

# Vertex

**An HCF gameplay layer for Paper, built on FactionsUUID.**

[Player Guide](docs/player-guide.md) · [Documentation](docs/README.md) · [Installation](docs/installation.md) · [Commands](docs/commands-and-permissions.md) · [Configuration](docs/configuration.md)

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
- **Economy:** a live-price shop, a buy-it-now Auction House, secure player
  trading, money/experience/item Coinflips, and Sell/TNT Wands that empty a
  chest or Chunk Collector through the same market one item at a time.
- **Mining worlds:** two permanent worlds with server-controlled ore
  generation and regeneration, 24/7 capturable Mine KOTHs whose holder earns
  a growing ore bonus, and rotating Hot Zones that favour the rarer ores.
- **Boosters:** one service that answers what bonus a player actually has and
  why, so every screen quoting a number quotes the same number.
- **Server tools:** cosmetic tags, four languages, vanish/staff tools,
  inventory inspection with stale-view anti-dupe, death rollback, scheduled
  reboots, and tiered custom-model-data Backpacks.

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
| [Personal settings](docs/settings.md) | Per-player optional announcement preferences |
| [Shop](docs/shop.md) | Categorized, dynamic-price item trading |
| [Auction House](docs/auctionhouse.md) | Buy-it-now listings for any item, claim stashes, and staff audit log |
| [Wands](docs/wands.md) | Sell Wands and TNT Wands: container selling through the live market, Gunpowder banking |
| [Mining Worlds](docs/mines.md) | Stonewake and Bloodvein: ore generation, regeneration, Mine KOTHs, Hot Zones |
| [Boosters](docs/boosters.md) | How every bonus stacks into one effective figure, and `/boosters` |
| [GUI framework](docs/gui-framework.md) | Configuring new menus: sizes, slots, materials, lore, sounds |
| [KOTH & Outposts](docs/koth-and-outposts.md) | Faction captures, schedules, navigation, holograms, rewards, and Outpost XP boosts |
| [Commands & permissions](docs/commands-and-permissions.md) | Every command and permission node |
| [Configuration](docs/configuration.md) | `config.yml` and reload behavior |
| [Full documentation](docs/README.md) | All remaining features and contributor notes |

## License

MIT — see [LICENSE](LICENSE).
