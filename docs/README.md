# Vertex Documentation

Vertex is a Paper plugin that adds a full HCF
gameplay layer with **native Vertex factions**: armor-based kit classes, PvP
ability items, 1.8-style legacy combat, a combat-tag system,
faction-aware chat, rallies, a faction permission matrix,
faction upgrades, a shared faction bank, cosmetic tags, stackable
spawners, Chunk Collectors, a progressive schematic base-builder,
KOTH/Outpost captures, a staff toolkit, a self-hosted GC currency,
automatic duplicate-item detection, Base and Raid Claims with a
global Grace, weekly scheduled Faction Shields, Chunk Busters, claim-aware Source
Buckets, a full Rune-based Custom Enchantment system, and an opt-in
performance-monitoring framework.

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
| [Configuration](configuration.md) | Full walkthrough of `config.yml`: database, chat, factions/upgrades, reboot, and reload semantics |
| [PvP & Combat](pvp-and-combat.md) | Combat tag, action bar, item cooldowns, no-pearl zones, Legacy Combat (1.8 PvP), Archer Tag |
| [Kits & Abilities](kits-and-abilities.md) | Kit classes, class effects, kit costs, the full ability item catalog and their mechanics |
| [Native Factions](factions-integration.md) | Native claims, roles, chat, permissions, rallies, upgrades, banks, and leader-leave protection |
| [Velocity Shards](network-shards.md) | Shared-state topology, shard health, cross-server handoffs, queues, crash recovery, and operator procedures |
| [Faction Leaderboards](faction-leaderboards.md) | `/f top`'s claimed, individually-aged spawner-value F Top, and `/pvptop`'s KOTH/Outpost objective points |
| [Spawners & Collectors](spawners-and-collectors.md) | Spawner shop/economy, daylight and lava behavior, stacking, mob stacks, and Chunk Collectors |
| [Blueprint Base Builder](blueprints.md) | Automated `.schem` base building with FastAsyncWorldEdit + DecentHolograms |
| [Backpacks](backpacks.md) | Tiered offhand auto-storage: drop bonuses, filters, leveling, and custom models |
| [Player Trading](trading.md) | Secure two-player item-for-item escrow trades |
| [Sand Bots](sandbots.md) | Automated falling-block column filling |
| [Coinflips](coinflips.md) | 50/50 money, experience, GC, and item wagers, claim stashes, self-ban, and the staff audit log |
| [GC (Gift Card / Credit)](gc-currency.md) | Vertex's self-hosted third currency: the wallet GUI, one-time withdrawal codes, redeem codes, and staff tools |
| [Dupe investigation](dupe-investigation.md) | The staff-only suspected-duplicate item scanner, case lifecycle, `/dupe` commands, and the shared item-identity utility |
| [Base and Raid Claims](base-and-raid-claims.md) | `/f baseclaim`'s permanent, connectable safe regions vs. Raid Claims' real-time-expiring faction land |
| [Grace and Faction Shield](faction-shield.md) | Global Grace plus weekly Base-only Shield schedules and audited overrides |
| [Chunk Busters](chunk-busters.md) | The 4 destructive area-clear items: batched removal, persisted operation locking, faction role permissions, and the confirmation GUI |
| [Source Buckets](source-buckets.md) | Reusable configured-block items: flow patterns, the claim-boundary rule, per-variant combat/base-claim gates, and charge-after-success economics |
| [Custom Enchantments](custom-enchantments.md) | Direct-inventory Rune rolling and application, the read-only Rune Catalog, universal Lucky Gems, world restrictions, and anvil/smithing persistence |
| [Personal settings](settings.md) | Per-player interaction and announcement preferences |
| [Shop](shop.md) | Categorized, dynamic-price item trading |
| [Boosters](boosters.md) | How every bonus stacks into one effective figure, and `/boosters` |
| [Wands](wands.md) | Sell Wands and TNT Wands: dynamic-priced container selling and Gunpowder banking |
| [Mining Worlds](mines.md) | Stonewake and Bloodvein: region selection, ore generation, and regeneration |
| [Haven & Riftlands](haven-riftlands.md) | PvE/PvPvE zones, guided routes, loot sessions, tickets, and the Mob Kill Event |
| [Physical Entry Portals](portals.md) | Build a portal and connect it to verified flight routes into mines, Haven, or Riftlands |
| [**Haven Setup Checklist**](setup-haven.md) | Owner step-by-step setup for the Haven region, route, loot pool, mobs, and portal |
| [**Riftlands Setup Checklist**](setup-riftlands.md) | Owner step-by-step setup for the Riftlands region, route, loot pool, mobs, and portal |
| [**Mine Setup Checklist**](setup-mines.md) | Owner step-by-step setup for Stonewake, Bloodvein, mine KOTHs, ore fills, and portals |
| [GUI framework](gui-framework.md) | Configuring new menus: sizes, slots, materials, lore, sounds |
| [Auction House](auctionhouse.md) | Buy-it-now listings for any item (money, experience, or GC), claim stashes, expiry, fees, and the staff audit log |
| [KOTH & Outposts](koth-and-outposts.md) | Faction capture rules, directions/focus, schedules, staff selection, holograms, rewards, and Outpost XP boosters |
| [Tags & Cosmetics](tags-and-cosmetics.md) | The `/tags` system: unlocking, equipping, nickname-match, the GUI |
| [Staff Tools](staff-tools.md) | Vanish, staff chat, staff-build, freeze, invsee/endersee, and death rollback |
| [Localization](localization.md) | Supported languages, `/language`, adding a translation, color/placeholder conventions |
| [Commands & Permissions](commands-and-permissions.md) | Every command and permission node in one place |
| [Integrations](integrations.md) | Exactly what each supported plugin unlocks, and what happens without it |
| [Placeholders](placeholders.md) | Every `%vertex_...%` PlaceholderAPI token Vertex provides for other plugins to read |
| [Performance](performance.md) | The OFF/BASIC/DETAILED monitoring framework, `performance.yml`, and `/vertex performance` |
| [Architecture](architecture.md) | Package layout, internal design notes, and testing — for contributors |

## Conventions used throughout

- Config keys are written as dotted paths into `config.yml` (e.g.
  `pvp.combat-tag-seconds`) unless another file is named.
- Commands omit the leading `/` in prose but are shown in full in tables.
- "No effect without X installed" means the feature quietly does nothing
  rather than erroring — Vertex never requires an optional integration to
  boot.
- `/vertex reload` reloads every feature's own config: `config.yml`,
  language files, kits, abilities, tags, spawner and collector tuning,
  Backpack tiers, Blueprint templates, capture-event definitions, Vertex
  upgrade definitions, F Top/PvP Top, Base and Raid Claims, Faction
  Shield, Chunk Busters, Source Buckets, Custom Enchantments, GC, dupe
  detection, the Performance framework, and the rest of the shop/
  auction/trade/booster/mine/portal feature set. It also rechecks all required
  and optional integrations, enabling Blueprints or Ghost Players if
  their dependencies became available after Vertex started. A full
  restart is still required after replacing a jar or switching the
  storage backend.

Found something these docs don't cover, or something that's out of date?
Check [issues.md](../issues.md) at the repo root for known open items, or
open an issue on [GitHub](https://github.com/Jrdelt/HCFCore).
