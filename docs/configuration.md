# Configuration

Everything below lives in `config.yml` unless stated otherwise. Gameplay
settings reload with `/vertex reload` (permission `vertex.admin`); changing
the storage backend still requires a restart. This page covers the
server-wide settings; the large feature-specific files (`kits.yml`,
`abilities.yml`, `tags.yml`, `spawners.yml`, `collectors.yml`,
`blueprints.yml`) each have their own doc page linked from [the index](README.md).

## Localization

```yaml
language:
  default: en_us
```

The default language assigned to a brand-new player. See
[Localization](localization.md) for the full list of shipped languages
and how players change theirs.

## Storage

```yaml
storage:
  type: local
```

Picks where data is saved. Two values matter:

- **`local`** (the default) — a single SQLite file at
  `plugins/Vertex/vertex.db`. No database server, no credentials, no
  setup. Right for any single server.
- **`mysql`** — a MySQL/MariaDB server, configured in the `mysql` section
  below. Use it when several servers need to share the same data, or when
  you already run MySQL and want everything in one place.

Only `mysql` selects MySQL (trimmed and case-insensitive). Other values select
local storage in standalone mode. With `network.enabled: true`, that local
selection is rejected at startup instead of silently creating a divergent shard.

Both backends store the same data with the same schema. To move an
existing server from one to the other, use `/vertex storage <local|mysql>`
— it copies every row across, updates this setting for you, and takes
effect on the next restart. The final `storage.type` change is written to a
temporary sibling file and atomically replaced, so a crash cannot leave a
partially written `config.yml`. See
[Installation](installation.md#switching-backends-in-game).

### Network deployment

Use `network.enabled: false` for one independent server, or `true` for multiple
Vertex backends sharing the same MySQL database. Each network backend needs a
unique, stable `network.shard-id` matching its Velocity name; the default
`standalone` identity is rejected in network mode.

`/vertex reload` refreshes gameplay/timing settings but retains the running
network mode, shard ID and database dialect, warning when the file requests a
different deployment. Such changes require a planned restart and, for existing
data, an explicit ownership/storage migration. Do not point an independent
standalone server at a live shard database. Read the
[ownership limitations and all-shards rollout](network-shards.md) before use.

## Large GC transaction audit

```yaml
transaction-audit:
  enabled: true
  minimum-gc: 50
  log-directory: coinflips
  notify-permission: vertex.transaction.audit
  ip-permission: vertex.transaction.audit.ip
```

This optional audit records successful GC withdrawals, redemptions, GC
coinflip settlements, and GC Auction House sales whose amount is **strictly
greater than** `minimum-gc`. It appends a weekly UTC log beneath
`plugins/Vertex/<log-directory>/` and alerts online holders of
`notify-permission`. The file records the available IP addresses; the
in-game alert includes them only for holders of `ip-permission`. Treat both
permissions as staff-only and restrict the log directory accordingly.

## Grace and faction Shield (`factions.yml`)

```yaml
grace:
  maximum-duration-seconds: 2592000
shield:
  base-duration-seconds: 28800 # 8 hours
  maximum-duration-seconds: 43200 # 12 hours
  cooldown-seconds: 86400
  new-faction-delay-seconds: 0
  combat-protection-enabled: false
  schedule:
    maximum-hours-per-day: 8
    edit-lock-seconds: 86400
    activation-delay-seconds: 86400
    time-zone: America/Los_Angeles
```

Grace is enabled with `/fa grace on <duration>`. Shield normally follows
the faction's weekly Base-only schedule; the optional `/f shield activate`
path uses persisted real-time deadlines and the configured duration upgrade.
The default Shield duration is 8 hours. Its four explicit upgrade levels add
1, 2, 3, or 4 hours for a 9–12 hour total. Daily limits, schedule edit locks,
and activation delays are configurable. See
[Grace and Faction Shield](faction-shield.md).

## Database (MySQL only)

Ignored entirely unless `storage.type` is `mysql`.

```yaml
mysql:
  host: localhost
  port: 3306
  database: vertex
  username: root
  password: ''
  pool-size: 10
  allow-public-key-retrieval: true
  use-ssl: false
```

- `pool-size` — concurrent HikariCP connections. Raise it for a larger
  player count; the default of 10 is generous for most single-server
  setups. (The local backend always uses a single connection, since
  SQLite serializes writes at the file level anyway.)
- `allow-public-key-retrieval` — needed for MySQL's
  `caching_sha2_password` auth plugin when not using SSL.
- `use-ssl` — enable if your database requires an SSL connection.

Create the database itself before first use (`CREATE DATABASE vertex;`);
Vertex creates its own tables inside it, but not the database.

The connection pool is shared by the storage modules. Many gameplay writes and
the shared event/leaderboard refreshes run asynchronously, while Bukkit inventory
and world changes stay on the server thread. Do not assume every legacy path is
asynchronous or atomic: the [audit backlog](../issues.md) and
[performance suggestions](../optimizations.md) describe remaining work.

## Chat formatting

```yaml
chat:
  separator: ' <gray>»</gray>'
  faction-format: '&8[<gray>{faction}</gray>&8] '
  rank-format: '%luckperms_prefix%'
  name-format: '{name} '
```

Final chat line: `[faction] [tag] [rank] name » message`. Each bracketed
part is independently optional — a faction-less player has no
`[faction]`, a player with no cosmetic tag equipped has no `[tag]`, and
`rank-format` left blank drops the rank entirely.

`rank-format` accepts `{rank}` (the player's LuckPerms group display
name, unwrapped/uncolored), `{prefix}` (the player's own LuckPerms
prefix meta, raw and not escaped), or a PlaceholderAPI token. `{prefix}`
is substituted raw, the same treatment a tag's `display` string gets,
since it's expected to carry its own color/formatting already.

With **PlaceholderAPI** installed, every `chat.*` template can also mix
in its `%percent%` placeholders (LuckPerms' own expansion, or any other
installed one) alongside the `{curly}` ones above — they're expanded as a
final pass over the whole resolved line, per viewing player. Without
PlaceholderAPI, a `%...%` token is left as literal text.

See [Factions Integration](factions-integration.md) for how tags and
faction relations plug into this.

## Physical entry portals

```yaml
portals:
  flight:
    speed: 0.8
  activation-cooldown-seconds: 3
```

`speed` is the default number of route blocks travelled per second. Staff may
override it for an individual mine route when running `/mines spawnpoints create`.
`activation-cooldown-seconds` is a server-side guard against repeated starts
and chat spam when somebody remains inside a portal trigger.

Portal triggers and routes themselves are not YAML: they are durable records
in the selected Vertex database. See [Physical Entry Portals](portals.md)
for setup and permissions.

## PvP core settings

```yaml
pvp:
  combat-tag-seconds: 30
  post-kill-combat-seconds: 5
  loot-protection:
    enabled: true
    seconds: 20
  pearl-cooldown-seconds: 12
  golden-apple-cooldown-seconds: 8
  enchanted-golden-apple-cooldown-seconds: 120
  no-pearl-regions: [spawn]
  no-pearl-claim-names: [safezone]
  pearl-velocity-multiplier: 1.35
  disable-hunger-worlds: [world]
  logout-penalty: true
  ghost-players:
    enabled: false
    combat-tagged-only: true
    allowed-worlds: []
    despawn-after-seconds: 300
  actionbar-update-interval-ticks: 2
  blocked-commands-in-combat: [ ... ]
  actionbar: { vs-server: ..., vs-player: ..., vs-unknown: ... }
  legacy-combat: { ... }
```

```yaml
item-restrictions:
  disabled-items: [SHIELD]
  disabled-crafting-results: [SHIELD]
```

Full combat-tag mechanics, item cooldown behavior, the no-pearl zone
rules, and the entire Legacy Combat (1.8 PvP) sub-system are covered in
depth in [PvP & Combat](pvp-and-combat.md) — this section is just the
config shape. A quick summary of what lives here:

- **Combat tag** duration and the shortened post-kill duration.
- **Item cooldowns** for ender pearls and both golden apple tiers —
  these override vanilla's own cooldowns and survive logout.
- **No-pearl zones**, checked at both the throw and the landing.
- **`blocked-commands-in-combat`** — see the annotated example already in
  `config.yml` for the full shipped list (inventory/kit commands,
  teleport/escape commands, economy/trade commands, cheat-adjacent
  commands, and faction-exploit commands like `f leave`/`f kick` while
  tagged).
- **`actionbar`** — three MiniMessage templates for the three ways a
  player can be tagged (against a real opponent, against "the server",
  or against an opponent who went offline).
- **`ghost-players`** — native Villager combat-log ghosts; see [PvP & Combat](pvp-and-combat.md#ghost-players-villagers)
  for the inventory-safety behavior and every setting.
- **`legacy-combat`** — the full 1.8-style PvP overhaul; see
  [PvP & Combat](pvp-and-combat.md#legacy-combat-18-pvp-style).
- **`item-restrictions`** — Bukkit material lists. `disabled-items` blocks
  using an item and moving it into the offhand, while
  `disabled-crafting-results` blocks its normal crafting recipe. The shipped
  default blocks shields only; the offhand itself remains available for
  Backpacks and every other allowed item.

## Factions

```yaml
factions:
  tag-pattern: '[A-Za-z0-9_]{3,16}'
  prevent-leader-leave: true
  open-join-enabled: true
  invite-expiry-seconds: 300
  limits: {members: 30, claims: 250, claim-radius: 5, warps: 5}
  claims: {require-connected: false, power-per-chunk: 0.0, allow-overclaim: false,
    overclaim-power-ratio: 1.0}
  map: {width: 41, height: 20} # Fills expanded chat; limits are 41x21.
  power: {starting-per-member: 10.0, max-per-member: 10.0, death-loss: 2.0,
    regeneration-per-interval: 0.1, regeneration-interval-seconds: 60}
  pvp: {friendly-fire: false, allies-can-pvp: false}
  protection: {allies-can-build: false}
  system-claims: {safezone-tag: SafeZone, warzone-tag: WarZone, no-pvp-tags: [SafeZone], max-chunks-per-tick: 64}
```

- **`prevent-leader-leave`** — when true, a faction leader's `/f leave`
  (or any alias in `command-aliases`) is cancelled with an explanatory
  message instead of going through. This stops a leader from
  accidentally (or exploit-ably) leaving their own faction without
  transferring leadership first.
- **`tag-pattern`** — Java regular expression accepted by `/f create` and
  `/f rename`; an invalid expression safely uses the default.
- **`open-join-enabled`**, **`invite-expiry-seconds`**, **`limits`**,
  **`claims`**,
  **`map`**, **`power`**, **`pvp`**, **`protection`**, and
  **`system-claims`** configure native faction behavior. Changes reload
  safely. The rank-action defaults live in `factions.permissions.defaults`;
  leaders can override them per faction in `/f permissions`.
- **`map.width`** and **`map.height`** set the exact chat-map dimensions.
  Width is safety-capped at 41 chunks and height at 21. Existing configs
  that only contain the old `map.radius` key retain the equivalent square
  size until the new keys are added. `/vertex reload` refreshes these values
  immediately, so the next `/f map` uses the new dimensions without a restart.
- **`system-claims.max-chunks-per-tick`** limits main-thread cache/event work
  after an atomic `/f claim <Safezone|Warzone> [radius]` save. Radius claims
  are squares: radius `1` is `3x3` chunks.
- **`command-aliases`** is retained for existing Vertex subcommand listeners;
  Bukkit aliases themselves are declared in `plugin.yml`.

See [Factions Integration](factions-integration.md) for rallies, the
permissions GUI and upgrades/bank behavior.

## Faction upgrades

```yaml
faction-upgrades:
  enabled: true
  upgrades:
    claim-damage:
      enabled: true
      levels:
        1: {price: 50000.0, bonus: 5.0}
        2: {price: 87500.0, bonus: 10.0}
        3: {price: 153125.0, bonus: 15.0}
```

`enabled` is the global off switch. Only the faction Leader or Co-Leader may
open and purchase from this menu; the decision is revalidated against the
durable faction role during the database update. Every individual entry has
the same controls:

- `enabled` hides its gameplay effect and prevents new purchases without
  erasing its saved level.
- `levels` is an ordered map of levels (up to 100). Each entry has its own
  exact Vault `price` and total `bonus`; a price of zero makes that level
  free and no multiplier is applied.
- Most `bonus` values are percentages. Crop Growth is a per-growth-stage
  chance; Fly Boost applies only to players already flying. `warps` adds
  native Vertex warp slots to `factions.limits.warps`; `tnt-bank` specifies
  an absolute TNT capacity; `shield-duration` is an additional duration in
  seconds; and `base-claim-slots` is the total number of unlocked slots.

The owner/scope matters: Damage, Claim Protection, Armor Wear, Fall
Protection, and Fly Boost require a faction member to be standing in their
own claim. Spawner Rate and Crop Growth apply to the managed block/crop in
the upgraded claim even when no member is nearby. Mob XP requires a member
to kill a mob in that faction's claim. The complete native behavior is in
[Native Factions](factions-integration.md).

GUI labels, lore, and purchase/failure messages are configurable in
`lang/en_us.yml` under `faction-upgrades` (and can be translated per locale).

## Kits & abilities (global settings)

```yaml
kits:
  default-cooldown-seconds: 30
  effect-warmup-seconds: 5
  max-cooldown-seconds: 86400
  max-money-cost: 1000000000.0
  max-cost-item-amount: 64

abilities:
  global-cooldown-seconds: 4
  max-getitem-amount: 64
  bard-share-radius-blocks: 30
  disabled-regions: [spawn]
  disabled-claim-names: [safezone]
```

These are validation caps and shared timers, not the kit/ability
definitions themselves — those live in `kits.yml` and `abilities.yml`,
covered in [Kits & Abilities](kits-and-abilities.md).

## Reboot scheduling

```yaml
reboot:
  default-delay-minutes: 10
  reminder-minutes: [10, 5, 1]
```

`/reboot [minutes]` (permission `vertex.reboot.start`) starts a shutdown
countdown, defaulting to `default-delay-minutes` if no argument is given,
and broadcasts a reminder at each mark in `reminder-minutes`. `/reboot
cancel` stops an in-progress countdown; `/nextreboot` lets any player
check whether one is currently scheduled.

## Rally permission GUI

The role icon materials and slots remain under `rally.permission-gui` in
`config.yml`. All visible title, role, status, hint, and action text is under
`faction-permissions` in `lang/en_us.yml`, alongside the rest of Vertex's GUI
copy. Every permission is always a green stained-glass
pane when allowed and a red stained-glass pane when denied; this is not
overridable, so allowed actions are never mistaken for blocked ones. The
shared GUI renderer converts static wording to small caps while preserving
placeholder values. Full behavior
in [Factions Integration](factions-integration.md#rally-permission-gui).
