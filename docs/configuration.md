# Configuration

Everything below lives in `config.yml` unless stated otherwise. All of it
reloads live with `/vertex reload` (permission `vertex.admin`) — no
restart required. This page covers the server-wide settings; the large
feature-specific files (`kits.yml`, `abilities.yml`, `tags.yml`,
`spawners.yml`, `collectors.yml`, `blueprints.yml`) each have their own
doc page linked from [the index](README.md).

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

Only the exact value `mysql` selects MySQL. Anything else — `local`,
a blank value, a typo, or the key being missing entirely — falls back to
local, so a bad value can't stop the server from booting.

Both backends store the same data with the same schema. To move an
existing server from one to the other, use `/vertex storage <local|mysql>`
— it copies every row across, updates this setting for you, and takes
effect on the next restart. See
[Installation](installation.md#switching-backends-in-game).

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

All gameplay-loop reads/writes (kit claims, ability cooldowns, spawner
and collector state, blueprint progress) go through the connection pool
asynchronously on either backend. Only the one-time startup schema check
touches the main thread.

## Scoreboard

```yaml
scoreboard:
  update-interval-ticks: 15
  title: '<blue><bold>ꜰᴀᴄᴛɪᴏɴꜱ<reset>   <gray>{date}'
  date-format: 'MM/dd'
  lines:
    - '     <gray>ꜱᴇᴀꜱᴏɴ I'
    - '%luckperms_prefix%<gray>{name}'
    - ...
```

Available placeholders in `title` and every entry of `lines`:

| Placeholder | Value |
|---|---|
| `{date}` | Current date, formatted with `date-format` |
| `{name}` | Player name (EssentialsX nickname if set, otherwise username) |
| `{rank_prefix}` | LuckPerms group display name, wrapped in `[brackets]` |
| `{rank}` | Same group display name, unwrapped/uncolored |
| `{prefix}` | The player's own LuckPerms prefix meta, raw (not escaped) |
| `{faction}` | Player's faction name |
| `{power}` | Faction power, current/max |
| `{ftop}` | Faction's rank on the power leaderboard |
| `{fplayers_online}` | Online members of the player's faction |
| `{exp}` | Player's XP level |
| `{balance}` | Player's Vault balance |

With **PlaceholderAPI** installed, any line can also mix in its
`%percent%` placeholders (LuckPerms' own expansion, or any other
installed one) alongside the `{curly}` ones above — they're expanded as a
final pass over the whole resolved line, per viewing player. Without
PlaceholderAPI, a `%...%` token is left as literal text.

`update-interval-ticks` controls both the title and every line's refresh
rate; a line is only re-sent to a player if its resolved text actually
changed since the last tick.

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

`rank-format` accepts `{rank}`, `{prefix}` (see the scoreboard table
above for the difference), or a PlaceholderAPI token. `{prefix}` is
substituted raw, the same treatment a tag's `display` string gets, since
it's expected to carry its own color/formatting already.

Every `chat.*` template gets the same PlaceholderAPI `%percent%` support
described for the scoreboard.

See [Factions Integration](factions-integration.md) for how tags and
faction relations plug into this.

## PvP core settings

```yaml
pvp:
  combat-tag-seconds: 30
  post-kill-combat-seconds: 5
  pearl-cooldown-seconds: 12
  golden-apple-cooldown-seconds: 8
  enchanted-golden-apple-cooldown-seconds: 120
  no-pearl-regions: [spawn]
  no-pearl-claim-names: [safezone]
  pearl-velocity-multiplier: 1.35
  disable-hunger-worlds: [world]
  logout-penalty: true
  actionbar-update-interval-ticks: 2
  blocked-commands-in-combat: [ ... ]
  actionbar: { vs-server: ..., vs-player: ..., vs-unknown: ... }
  legacy-combat: { ... }
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
- **`legacy-combat`** — the full 1.8-style PvP overhaul; see
  [PvP & Combat](pvp-and-combat.md#legacy-combat-18-pvp-style).

## Factions

```yaml
factions:
  prevent-leader-leave: true
  command-aliases: [f, factions, t]
```

- **`prevent-leader-leave`** — when true, a faction leader's `/f leave`
  (or any alias in `command-aliases`) is cancelled with an explanatory
  message instead of going through. This stops a leader from
  accidentally (or exploit-ably) leaving their own faction without
  transferring leadership first.
- **`command-aliases`** — every alias your server actually uses for
  FactionsUUID's command. This list is also what the `/f rally` command
  and the faction permissions GUI (`/f permissions` / `/f perms`) and
  faction-upgrades GUI (`/f upgrades`) listen
  on to route themselves ahead of FactionsUUID's own command handling —
  add any custom alias here too or those won't be reachable from it.

See [Factions Integration](factions-integration.md) for rallies, the
permissions GUI, and nametag/scoreboard details.

## Faction upgrades

```yaml
faction-upgrades:
  enabled: true
  leader-only: false
  upgrades:
    claim-damage:
      enabled: true
      levels:
        1: {price: 50000.0, bonus: 5.0}
        2: {price: 87500.0, bonus: 10.0}
        3: {price: 153125.0, bonus: 15.0}
```

`enabled` is the global off switch. `leader-only` lets members browse the
menu while preventing them from spending faction resources; set it to
`false` if any member should be able to buy levels. Every individual entry
has the same controls:

- `enabled` hides its gameplay effect and prevents new purchases without
  erasing its saved level.
- `levels` is an ordered map of levels (up to 100). Each entry has its own
  exact Vault `price` and total `bonus`; a price of zero makes that level
  free and no multiplier is applied.
- `bonus` is a percentage for every item except `warps`. Crop Growth is a
  per-growth-stage chance; Fly Boost applies only to players already
  flying. Warps gives FactionsUUID its native warp upgrade level, so
  configure the actual count/values in FactionsUUID's own upgrades
  configuration.

All non-warp effects apply only in the upgraded faction's claimed land.
Their GUI labels, lore, and purchase/failure messages are configurable in
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

## Nametags

```yaml
nametags:
  enabled: true
  update-interval-ticks: 15
  colors:
    same-faction: 'green'
    ally: 'light_purple'
    enemy: 'red'
    neutral: 'red'
```

Colors accept any Adventure `NamedTextColor` name. Full mechanics
(per-viewer coloring, team registration, name-length safety for older
clients) are in [Factions Integration](factions-integration.md#nametags).

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

```yaml
rally:
  permission-gui:
    title: '<gold>Faction Permissions'
    roles:
      mod: {slot: 2, material: DIAMOND_SWORD, name: '<aqua>Moderator', ...}
      member: {slot: 4, material: GOLDEN_SWORD, name: '<yellow>Member', ...}
      recruit: {slot: 6, material: WOODEN_SWORD, name: '<gray>Recruit', ...}
    actions:
      vertex-rally-set: {name: 'Set Rally'}
      vertex-rally-clear: {name: 'Clear Rally'}
```

Controls the title, per-role slot/icon/name in the top row, and the label
for Vertex's custom permission actions shown alongside FactionsUUID's own
native permission list. Every permission is always a green stained-glass
pane when allowed and a red stained-glass pane when denied; this is not
overridable, so allowed actions are never mistaken for blocked ones. The
GUI renders all its visible text in small caps. Full behavior
in [Factions Integration](factions-integration.md#rally-permission-gui).
