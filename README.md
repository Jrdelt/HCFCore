<div align="center">

# Vertex

**A self-contained HCF gameplay layer for Paper with native Vertex factions.**

[Player Guide](docs/player-guide.md) · [Documentation](docs/README.md) · [Installation](docs/installation.md) · [Commands](docs/commands-and-permissions.md) · [Configuration](docs/configuration.md)

</div>

## What Vertex adds

- **Kits and PvP:** six armor classes, ability items, combat tags, Legacy
  Combat, Archer Tags, optional killable Villager Ghost Players, pearl restrictions,
  and persistent item cooldowns.
- **Factions:** rally points, a role-permission GUI, per-faction upgrades,
  a shared money/XP/TNT bank and faction chat,
  faction KOTHs, reward Outposts, and claimed-spawner-value F Top / PvP
  Top leaderboards.
- **Claims & land:** permanent, connectable Base Claims and real-time-
  expiring Raid Claims, global Grace and weekly scheduled Faction Shields,
  claim-aware TNT/explosion rules, 4-type Chunk
  Busters for clearing land, and reusable claim-boundary-respecting
  Source Buckets.
- **Automation:** stackable spawners that work in daylight, mob stacking,
  upgradeable Chunk Collectors, and progressive `.schem` Blueprint builds.
- **Economy:** a live-price shop, a buy-it-now Auction House, secure player
  trading, money/experience/GC/item Coinflips, Sell/TNT Wands that empty a
  chest or Chunk Collector through the same market one item at a time, and
  a self-hosted GC (Gift Card/Credit) currency with player-created,
  one-time withdrawal codes and staff-issued redeem codes.
- **Custom Enchantments:** a Rune-tiered enchantment system (rolling,
  Lucky Gems, level-replacement rules) with its own application GUI,
  independent of the vanilla enchanting table.
- **Mining worlds:** two permanent worlds with server-controlled ore
  generation and regeneration, 24/7 capturable Mine KOTHs whose holder earns
  a growing ore bonus, and rotating Hot Zones that favour the rarer ores.
- **Haven & Riftlands:** durable PvE/PvPvE farming zones with guided entry
  routes, locally-budgeted custom mobs, independent progression, a recurring
  Mob Kill Event, editable loot pools, and Riftlands session-loot extraction.
- **Physical entry portals:** staff-built portal volumes with verified,
  server-controlled flights into placed mines, Haven, and Riftlands.
- **Boosters:** one service that answers what bonus a player actually has and
  why, so every screen quoting a number quotes the same number.
- **Server tools:** cosmetic tags, four languages, vanish/staff tools,
  inventory inspection with stale-view anti-dupe, automatic duplicate-item
  detection, death rollback, scheduled reboots, tiered custom-model-data
  Backpacks, and an opt-in performance-monitoring framework.

Feature messages, labels, and lore are configured in the language files.
See the [documentation index](docs/README.md) for exact behavior,
configuration, permissions, and operational limits.

## Requirements

| Dependency | Required for |
| --- | --- |
| Paper 1.21.10+ (built against 1.21.11) | Vertex itself |
| Vault | money costs, the money bank, and upgrade purchases |
| FastAsyncWorldEdit + DecentHolograms | Blueprint base building |
| FancyNPCs | optional Sand Bot displays |

WorldGuard, LuckPerms, PlaceholderAPI, EssentialsX, and FancyNPCs are optional
integrations. MySQL/MariaDB is optional for one standalone server but required
when `network.enabled: true`; standalone mode uses SQLite. Exact integration
behavior is listed in [Integrations](docs/integrations.md).

## Install

```bash
cd Vertex
./mvnw clean package
```

1. Put `Vertex/target/vertex-1.0.0.jar` in `plugins/`.
2. Start the server once. Vertex creates `plugins/Vertex/`, its local SQLite
   database, and configuration files. With both Blueprint dependencies
   installed, it also creates the Blueprint `schematics/` folder.
3. Configure it, then run `/vertex reload`. Use a full restart after changing
   jars, dependencies, or storage backend.

## Guides

| Guide | Use it for |
| --- | --- |
| [Native Factions](docs/factions-integration.md) | Claims, roles, permissions, chat, rallies, upgrades, banks, and native faction data |
| [Velocity Shards](docs/network-shards.md) | Shared MySQL state, shard health, handoffs, queues, recovery, Spawn/RTP/warps, and deployment limits |
| [Faction Leaderboards](docs/faction-leaderboards.md) | Claimed-spawner-value F Top and PvP Top |
| [Base and Raid Claims](docs/base-and-raid-claims.md) | Permanent Base Claims vs. real-time-expiring Raid Claims, and TNT/explosion rules |
| [Grace and Faction Shield](docs/faction-shield.md) | Global Grace plus weekly Base-only Shield schedules, persistence, and overrides |
| [Chunk Busters](docs/chunk-busters.md) | The 4 destructive area-clear items, batched processing, and current restart limitation |
| [Source Buckets](docs/source-buckets.md) | Reusable, claim-boundary-respecting configured-block items |
| [Custom Enchantments](docs/custom-enchantments.md) | Rune tiers, rolling, Lucky Gems, and the enchant application GUI |
| [GC (Gift Card / Credit)](docs/gc-currency.md) | The self-hosted third currency: wallet GUI, one-time withdrawal codes, redeem codes |
| [Dupe investigation](docs/dupe-investigation.md) | The automatic duplicate-item scanner and staff case workflow |
| [Spawners & Collectors](docs/spawners-and-collectors.md) | Spawners, daylight/lava behavior, mob stacks, and collectors |
| [Blueprints](docs/blueprints.md) | Schematic placement, builds, repairs, cooldowns, and recovery |
| [Backpacks](docs/backpacks.md) | Hidden auto-storage, drop bonuses, filters, leveling, and custom models |
| [Player Trading](docs/trading.md) | Secure item-for-item trades with escrow and recovery |
| [Coinflips](docs/coinflips.md) | 50/50 money, experience, GC, and item wagers, self-ban, and staff audit log |
| [Personal settings](docs/settings.md) | Per-player optional announcement preferences |
| [Shop](docs/shop.md) | Categorized, dynamic-price item trading |
| [Auction House](docs/auctionhouse.md) | Buy-it-now listings for any item, claim stashes, and staff audit log |
| [Wands](docs/wands.md) | Sell Wands and TNT Wands: container selling through the live market, Gunpowder banking |
| [Mining Worlds](docs/mines.md) | Stonewake and Bloodvein: ore generation, regeneration, Mine KOTHs, Hot Zones |
| [Haven & Riftlands](docs/haven-riftlands.md) | Guided zone entry, custom mobs, event scoring, loot sessions, Tickets |
| [Physical Entry Portals](docs/portals.md) | Build portal volumes and verified arrival routes into mines or zones |
| [Boosters](docs/boosters.md) | How every bonus stacks into one effective figure, and `/boosters` |
| [GUI framework](docs/gui-framework.md) | Configuring new menus: sizes, slots, materials, lore, sounds |
| [KOTH & Outposts](docs/koth-and-outposts.md) | Faction captures, schedules, navigation, holograms, rewards, and Outpost XP boosts |
| [Performance](docs/performance.md) | The OFF/BASIC/DETAILED monitoring framework and `/vertex performance` |
| [Commands & permissions](docs/commands-and-permissions.md) | Every command and permission node |
| [Configuration](docs/configuration.md) | `config.yml` and reload behavior |
| [Full documentation](docs/README.md) | All remaining features and contributor notes |

## License

MIT — see [LICENSE](LICENSE).
