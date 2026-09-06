# Architecture

For contributors and anyone extending the plugin. This covers internal
structure and conventions that aren't obvious from reading a single file
in isolation.

## Package layout

All code lives under `me.vertex.core`, one package per feature area:

| Package | Contains |
|---|---|
| `ability` | Every ability item's listener/manager, cooldowns, `/abilities`, `/getitem`, `/cooldowns` |
| `blueprint` | The automated base builder |
| `chat` | Chat format renderer |
| `collector` | Chunk Collector |
| `economy` | Vault wrapper |
| `essentials` | EssentialsX wrapper |
| `faction` | Rally system, faction permission matrix, persistent upgrades, and faction bank |
| `factions` | FactionsUUID wrapper and faction-command interception |
| `kit` | Kit classes, GUI, armor-effect tracking |
| `lang` | Message loading/formatting, `/language` |
| `listener` | Cross-cutting listeners (combat, player connection) |
| `luckperms` | LuckPerms wrapper |
| `nametag` | Per-viewer nametag teams |
| `placeholderapi` | PlaceholderAPI expansion hook |
| `pvp` | Combat tag, Legacy Combat, Archer Tag, vanilla item cooldowns |
| `reboot` | Scheduled shutdown |
| `scoreboard` | Sidebar scoreboard |
| `spawner` | Spawner shop, stacking, mob stacking |
| `staff` | Vanish, staff chat/build, freeze, invsee/endersee, death rollback |
| `storage` | Database layer: dialect selection, connection pool, and the shared `Storage` implementation |
| `tag` | Cosmetic tags |
| `user` | Per-player data cache |
| `worldguard` | WorldGuard wrapper |

`VertexPlugin` is the single entry point — it owns every manager's
lifecycle and wires listeners/commands together in `onEnable()`.

## Design conventions

- **All player-facing text uses Adventure `Component`s**, not legacy
  color codes, except a couple of plain-string fallbacks for
  console-only messages. Item display names/lore always run through an
  explicit `TextDecoration.ITALIC, false` — Minecraft renders those
  italic by default when unset, which otherwise silently affects any raw
  `Component.text(...)` used in a GUI.
- **Gameplay database work is asynchronous.** Schema setup and the one-time
  cache loads run during startup; recurring gameplay mutations use HikariCP
  away from the server thread. HikariCP and both JDBC drivers (SQLite and
  MySQL) are shaded and relocated into `me.vertex.core.libs.*` to avoid
  classpath collisions with other plugins bundling their own copies.
- **Two backends, one implementation.** `Database` reads `storage.type`
  and connects to either a local SQLite file (the default) or a MySQL
  server, exposing which one it picked as a `Database.Dialect`.
  `SqlStorage` and the five feature-specific stores
  (`SpawnerStorage`, `ChunkCollectorStorage`, `BlueprintStorage`,
  `FactionUpgradeStorage`, `FactionBankStorage`) then
  select only the statements that genuinely differ between the two --
  the upserts (`ON DUPLICATE KEY UPDATE` vs `ON CONFLICT ... DO UPDATE`)
  and the auto-incrementing id columns. Everything else is shared
  verbatim, because SQLite accepts MySQL's column type names through its
  own type-affinity rules. Adding a query means writing dialect-specific
  SQL only if it uses one of those two constructs.
- **GUIs** (`/kits`, `/tags`, `/abilities`, kit preview, faction upgrades,
  spawner/collector menus) use a static-nested `Holder implements InventoryHolder` per menu
  to identify their own inventories in click listeners, rather than
  comparing title strings. The tags GUI additionally rebuilds itself from
  an immutable `TagMenuState` (sort/filter/page/search) on every click
  rather than mutating anything in place.
- **`GradientColor`** (in `tag`) is a small pure-function utility:
  reversing a MiniMessage gradient's stop order, and extracting/stripping
  a tag's leading color (MiniMessage or legacy `&`/`&#RRGGBB`) from its
  `display` string, since tags embed color directly rather than storing
  it separately.
- **`ArmorClass`** matches armor by **material only**, deliberately
  looser than `KitManager`'s exact-set match used for passive kit
  effects — see [Kits & Abilities](kits-and-abilities.md#class-detection)
  for why both exist.
- **`ArcherTagManager`** stores faction **ids**, not `Faction` objects or
  player lookups, taking them as plain ints from the caller — keeps the
  stacking/expiry logic unit-testable with no Factions plugin running.
  `FactionsHook` centralizes normal faction/claim/relation reads; the
  faction package uses the narrow native APIs needed for permissions, TNT,
  Warp levels, and third-party faction commands.
  `FactionsHook.NO_FACTION` is the factionless sentinel and never matches
  anything, so a mark from a factionless archer grants no melee bonus
  rather than arming every factionless player.
- **Short-lived, not-worth-persisting cooldowns** (`VanillaCooldownManager`'s
  pearl/gapple timers) live in memory, keyed by UUID. Their quit handlers
  only drop entries that have already expired — clearing live ones would
  turn a relog into a free cooldown reset.
- **`CombatManager.SERVER_UUID`** (`new UUID(0, 0)`) is a reserved
  sentinel opponent id used only by `/combattag ... server` — never a
  real player's UUID, so it can't collide.
- **Kit class effects** are driven by a single every-tick pass
  (`KitManager.checkArmorEffects`) comparing each online player's worn
  armor against every kit's exact armor set. It only checks effect
  *presence*, not amplifier, when topping up an already-active kit's
  effects — see [Kits & Abilities](kits-and-abilities.md#passive-effects-warmup-and-armor-matching).
- **`LuckPermsHook.getPrimaryGroupDisplayName`** returns `null` (not the
  raw group id) for LuckPerms' built-in `default` group when it has no
  configured display name, so chat doesn't print the literal word
  `default` as everyone's rank.
- **`EconomyHook` and `WorldGuardHook`** (Vault and WorldGuard are both
  softdepends) share one shape with the FactionsUUID-specific
  `FactionsHook`: a static, stateless wrapper that checks the target
  plugin is actually enabled before touching any of its classes, so
  Vertex runs fine without them installed.
- **`ChatFormatterListener`** registers at `EventPriority.MONITOR`, not
  `HIGHEST`. Paper's `AsyncChatEvent` has exactly one renderer slot — the
  last handler to call `event.renderer(...)` wins outright, nothing
  merges — and FactionsUUID ships its own Paper-native chat formatter
  (enabled by default) that also sets a renderer at `HIGHEST`. At equal
  priority, which formatter wins would come down to plugin load order.
  `MONITOR` guarantees Vertex's format always wins regardless.

## Persistence & shutdown

- Player locale and ability/kit cooldown writes are flushed during
  shutdown. If player data can't be loaded from the database, kit and ability
  claims fail closed until the player reconnects successfully, instead
  of silently bypassing saved cooldowns.
- `KitManager` and `LanguageCommand` loop until all pending async writes
  complete on shutdown, rather than letting the JVM exit mid-write.
- Same-location collector/spawner database writes are ordered per block
  location, so a rapid place/break or stack-size change can't leave a
  stale row behind.
- Blueprint schematic bounds/blocks are parsed once and cached after the
  first successful read; ongoing progress/removal writes run
  asynchronously and are flushed before shutdown, while the actual world
  edits and faction checks stay on the main thread as their APIs require.

- Ability cooldown writes are serialized per player/ability; tag saves are
  serialized on their dedicated I/O executor before reload or shutdown.
  Blueprint ownership uses a stable FactionsUUID id rather than a mutable
  faction tag.
- Faction-upgrade writes are serialized per faction and flushed during
  shutdown. A disband queues its delete behind every prior write, so a
  delayed upgrade save cannot recreate rows for a disbanded faction.
- Faction-bank money/XP mutations are serialized per faction too. Cached
  balances change only after their database write succeeds, so failed
  transactions cannot silently change the durable balance.

## Testing

The unit tests run against [MockBukkit](https://github.com/MockBukkit/MockBukkit)
— no real Paper server needed. The included workflow can run this same
suite and a packaging build for every pull request and push to `main` once
it is uploaded to GitHub:

```bash
./mvnw clean test
```

Coverage focuses on manager classes with real logic (`KitManager`,
`AbilityManager`, `TagManager`, `SpawnerManager`, `CombatManager`,
`ArcherTagManager`, `MessageFormatter`, `GradientColor`, etc.) rather than
thin Bukkit-event glue. When adding a feature with any non-trivial logic,
add a test alongside it in the matching package under `src/test/java`.
