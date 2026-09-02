# HCFCore

Kits, a live sidebar scoreboard, and PvP combat-tag timers, built for Paper
and integrated with **FactionsUUID** (used for the scoreboard's faction/role
placeholders).

## Requirements

- **Paper 1.21.10** or newer (matches the API version FactionsUUID itself
  requires — an older 1.21.1 core will fail to load either plugin).
- **FactionsUUID** (`dev.kitteh:factions`) — hard dependency, must be
  installed and enabled first.
- **MySQL** 5.7+ / 8.x reachable from the server.
- **Vault** — optional; only needed if any kit sets a `cost.money` price.
  Softdepended, so it loads first if present. Without it, kits with a
  money cost can't be claimed (item-only or free kits are unaffected).
- **WorldGuard** — optional; only needed to enforce `abilities.disabled-regions`.
  Softdepended. Without it, abilities can be used anywhere.
- **LuckPerms** — optional; only needed for the `repair` ability, which
  grants an EssentialsX-style permission node on a timer. Softdepended.
  Without it, `repair` tells the player it's unavailable.

## Installation

1. Build (or grab) `hcfcore-1.0.0.jar` from `target/`.
2. Drop it into `plugins/` alongside `FactionsUUID.jar`.
3. Start the server once to generate `plugins/HCFCore/config.yml`, then
   stop it.
4. Edit `config.yml` with your real MySQL credentials (see below).
5. Start the server again.

### Building from source

```
./mvnw clean package
```

Produces a single shaded jar at `target/hcfcore-1.0.0.jar` — HikariCP and
the MySQL driver are bundled and relocated, so no separate driver jar is
needed.

**After any change, redeploy this exact jar to the server and restart** (or
`/reload`-equivalent full restart) before testing — a stale jar on the
server is the most common reason a newly added command won't show up or
won't tab-complete.

## Configuration (`config.yml`)

```yaml
language:
  default: en_us

mysql:
  host: localhost
  port: 3306
  database: hcfcore
  username: root
  password: ''
  pool-size: 10

scoreboard:
  update-interval-ticks: 20
  title: '&b&lHCF&f&lCore'
  lines:
    - ''
    - '&7Online: &f{online}'
    - '&7Faction: &f{faction}'
    - '&7Role: &f{faction_role}'
    - ''
    - '&7play.example.com'

pvp:
  combat-tag-seconds: 15
  logout-penalty: true
  actionbar-update-interval-ticks: 4

kits:
  default-cooldown-seconds: 300

abilities:
  global-cooldown-seconds: 3
  disabled-regions:
    - spawn
```

- `scoreboard.lines` supports `{online}`, `{faction}`, `{faction_role}`,
  `{repair}` (blank unless the `repair` ability is active for that player,
  then "Repairing: Xs"), and legacy `&` color codes. There's no live-edit
  command yet — edit the file and run `/hcfcore reload`.
- `pvp.actionbar-update-interval-ticks` controls how often the combat
  action bar (name/timer/health/CPS) refreshes — 4 ticks (~5/sec) by
  default for a PvP-responsive feel.
- `pvp.logout-penalty`: if true, disconnecting while tagged is treated as a
  combat logout (see `PlayerConnectionListener`).
- `abilities.global-cooldown-seconds` is a shared cooldown across *all*
  ability items — using any one of them starts it, blocking every other
  ability until it expires, independent of each item's own cooldown.
- `abilities.disabled-regions` is a list of WorldGuard region ids (not
  world names) where ability items refuse to activate — checked at the
  player's location, across any world, so a region called `spawn` blocks
  abilities inside it wherever it's defined.

- `language.default` is the locale (see below) every player gets until
  they run `/language` to pick their own.

The database, scoreboard lines, and kit cooldown default all take effect
immediately on `/hcfcore reload`.

## Language (`lang/*.yml`)

Every player-facing message — every command reply, error, GUI label, and
the combat action bar — lives in locale files under `lang/`, not hardcoded
in Java. Four ship out of the box: `en_us` (the reference locale; every
key is guaranteed to exist here), `es_us`, `pt_br`, and `de_de` (translated
without native-speaker review — treat them as a solid starting point, not
a final proofread).

```yaml
kit:
  applied: '&aApplied kit {kit}.'
  cooldown: '&cYou can use this kit again in {seconds}s.'
```

- Each message is a key → a legacy-`&`-color-coded template string.
  `{placeholders}` like `{kit}`/`{seconds}`/`{player}` get substituted
  per-message; check `en_us.yml` for exactly which ones a given key
  accepts.
- `/language [code]` lets any player view or change their own language
  (no permission node — it's a personal preference). Their choice
  persists to MySQL (a `user_locale` table) so it survives a restart.
  With no argument, it shows their current locale and everything
  available.
- **Adding a fifth language needs no code change**: copy `en_us.yml` to
  e.g. `lang/it_it.yml` inside the plugin's data folder, translate it, and
  run `/hcfcore reload` (or restart) — it becomes selectable immediately.
  `Messages` reads whatever `.yml` files are actually present in `lang/`,
  not a hardcoded list.
- If a key is missing from a player's chosen locale, it falls back to
  `language.default`'s file; if it's missing from that too, the plugin
  shows `Missing translation: <key>` instead of breaking — that fallback
  string itself is the one hardcoded exception, since it's describing a
  broken translation rather than being plugin content.

## Kits (`kits.yml`)

Each kit is a top-level key with its own permission, cooldown, optional
cost, and contents:

```yaml
kits:
  fighter:
    permission: hcfcore.kit.fighter
    cooldown-seconds: 300
    cost:
      money: 250.0
      item: DIAMOND
      item-amount: 2
    armor:
      - {==: org.bukkit.inventory.ItemStack, type: IRON_HELMET, amount: 1}
      # ...
    contents:
      - {==: org.bukkit.inventory.ItemStack, type: IRON_SWORD, amount: 1}
      # ...
```

- `permission` — required to claim the kit at all; defaults to
  `hcfcore.kit.<name>` if omitted.
- `cooldown-seconds` — time before the kit can be claimed again; `0` means
  no cooldown. Bypassed by `hcfcore.kit.bypasscooldown`.
- `cost` — optional; omit entirely for a free kit. `money` charges via
  Vault (needs an economy plugin installed — see Requirements); `item` +
  `item-amount` charges that many of a Material from the player's
  inventory. Either, both, or neither can be set. Both checked and
  charged as an atomic pair — a kit only ever deducts if the player can
  afford everything it costs. Bypassed by `hcfcore.kit.bypasscost`.
- `armor` / `contents` — standard Bukkit `ItemStack` YAML serialization;
  easiest way to populate these is `/kit save`, then hand-edit
  `permission`/`cooldown-seconds`/`cost` afterward.

Reloaded along with everything else on `/hcfcore reload`.

## Abilities (`abilities.yml`)

Eight PvP ability items ship pre-registered, each with real gameplay
behavior:

| Ability | Trigger | What it does |
|---|---|---|
| `anti-blockup-bone` | Melee hit | After `hits-required` hits (default 3), the victim can't place blocks for `deny-seconds` (default 15). |
| `fake-pearl` | Right-click | Throws a real `EnderPearl` (identical arc/sound) but cancels the teleport it would trigger on landing. |
| `grappling-hook` | Right-click (twice) | First click casts a `FishHook`; a second click while it's out pulls you toward it, scaled by `forward-multiplier`/`y-multiplier`. |
| `leap` | Right-click | Sets your velocity forward and slightly upward, scaled by `forward-multiplier`/`y-multiplier`. |
| `portable-bard` | Right-click | Opens a GUI to pick Strength III / Speed III / Regeneration II; applies it, for `buff-seconds`, to you and every online member of your faction. |
| `repair` | Right-click | Grants `permission-node` (default `essentials.fix`) via LuckPerms for `duration-seconds`, with a live countdown on your scoreboard. Requires LuckPerms installed. |
| `switcher-snowball` | Throw + hit | Swaps positions with whichever enemy (not a faction member) it hits. |
| `time-warp-pearl` | Right-click | Teleports you back to wherever you last threw a *real* ender pearl from. |

```yaml
abilities:
  grappling-hook:
    material: FISHING_ROD
    name: '&fGrappling Hook'
    lore:
      - '&7Hook a block or player and'
      - '&7reel yourself toward it.'
    cooldown-seconds: 20
    forward-multiplier: 1.6
    y-multiplier: 0.8
```

- `material` / `name` / `lore` — fully configurable per ability; `name`
  and each `lore` line accept legacy `&` color codes.
- `cooldown-seconds` — per-item cooldown, persisted to MySQL (its own
  `ability_cooldowns` table, separate from kit cooldowns) so it survives a
  restart.
- Everything else under an ability's section (`forward-multiplier`,
  `hits-required`, `buff-seconds`, `duration-seconds`, `permission-node`,
  etc.) is that ability's own extra config, documented in the table above.
- `switcher-snowball` and `portable-bard` use **FactionsUUID** to tell
  faction members apart from enemies — see `FactionsHook`.
- `repair` degrades gracefully without LuckPerms installed: it tells the
  player it's unavailable and doesn't consume a cooldown, the same way a
  kit's money cost behaves without Vault.

Reloaded along with everything else on `/hcfcore reload`.

## Commands & Permissions

### Kits

| Command | Permission | Notes |
|---|---|---|
| `/kit <name>` | kit's own permission (default `hcfcore.kit.<name>`) | Applies the kit to your inventory, respecting its cooldown and cost. |
| `/kit save <name> [permission] [cooldownSeconds] [cost] [costItem[:amount]]` | `hcfcore.kit.save` | Saves your current inventory as a kit. `costItem` is a Material name, e.g. `DIAMOND:2`; amount defaults to 1. |
| `/kit delete <name>` | `hcfcore.kit.delete` | Deletes a kit. |
| `/kits` | *(none — open to all players)* | Opens a GUI of every kit you can see. **Left-click** claims it, **right-click** previews its contents read-only. |

### Abilities

| Command | Permission | Notes |
|---|---|---|
| `/getitem <username> <ability> [amount]` | `hcfcore.ability.give` | Gives a player ability items directly, ignoring cooldowns. |
| `/abilities` | *(none — open to all players)* | Opens a GUI listing every ability's name/lore. A viewer with `hcfcore.ability.give` who clicks one receives a copy; everyone else's click just closes/does nothing. |

### Language

| Command | Permission | Notes |
|---|---|---|
| `/language [code]` | *(none — open to all players)* | With no args, shows your current locale and everything available. With a code (e.g. `es_us`), switches to it and persists the choice. |

### Combat

| Command | Permission | Notes |
|---|---|---|
| `/uncombat <player>` | `hcfcore.combat.uncombat` | Clears a player's combat tag; notifies both staff and the target. |
| `/combatcheck <player>` | `hcfcore.combat.check` | Reports tagged status, time left, and (if tagged) the opponent's name, health, and ping. |
| `/combattag <player> [opponent\|server]` | `hcfcore.combat.tag` | Testing tool. With no second argument (or `server`), tags the target against a synthetic **"Server"** opponent — lets one admin alone see the action bar without a second player online. With a real opponent name, tags both players against each other. |

While tagged, both players see an action bar:
`⚔ Combat: {opponent} {health}❤  {time}s  You {yourCps}  Them {theirCps} ⚔`
(the opponent segment just reads `Server`, with no health or "Them" CPS,
when tagged via `/combattag <you> server`). The timer and health both
gradient from red to green. CPS (clicks per second) is tracked from arm
swings for every online player, not just tagged ones, so the count is
already warm the instant a tag starts.

### Admin / reload

| Command | Permission | Notes |
|---|---|---|
| `/hcfcore reload` | `hcfcore.admin` | Reloads config, kits, and scoreboard. |

## Tab-completion

Every command above (`kit`, `kits`, `hcfcore`, `getitem`, `abilities`,
`language`, `uncombat`, `combatcheck`, `combattag`) registers its own
`TabCompleter` (except `abilities`, which takes no arguments), so suggestions should appear
as soon as the freshly built jar is running on the server. If they don't
show up in-game:

1. Confirm the server is actually running the jar you just built — check
   `plugins/HCFCore.jar`'s modified date, or run `/hcfcore reload` and watch
   the console for the plugin's own log line to confirm it's the loaded
   instance.
2. Confirm you have the command's permission — `/kit`, `/kits`, and console
   generally show suggestions regardless, but a player missing
   `hcfcore.combat.tag` etc. won't see `/combattag` suggested at all (though
   they can still see subcommand args once they've typed the base command,
   if permitted).
3. Some clients cache command suggestions per-session; rejoin if a command
   was added while you were already connected.

## Architecture notes

- All player-facing text uses Adventure `Component`s, not legacy color
  codes (except a couple of plain-string fallbacks for console-only
  messages).
- All MySQL access goes through HikariCP off the main thread.
- HikariCP and the MySQL driver are shaded and relocated into
  `me.hcfcore.core.libs.*` to avoid classpath collisions with other plugins.
- GUIs (`/kits`, kit preview) use a static-nested
  `Holder implements InventoryHolder` per menu to identify their own
  inventories in click listeners, rather than comparing title strings.
- `CombatManager.SERVER_UUID` (`new UUID(0, 0)`) is a reserved sentinel
  opponent id used only by `/combattag ... server` — never a real player's
  UUID, so it can't collide.
- `AbilityUseListener` (right-click activation) is the seam future
  ability-specific behavior plugs into — it already does the real
  cooldown/global-cooldown/region checks via `AbilityManager` and
  `WorldGuardHook`, then sends a placeholder message. A throw- or
  hit-triggered ability (`switcher-snowball`, `anti-blockup-bone`) will
  need its own listener calling those same `AbilityManager`/`WorldGuardHook`
  methods rather than going through `PlayerInteractEvent`.
- `EconomyHook` and `WorldGuardHook` (Vault and WorldGuard are both
  softdepends) share one shape with the FactionsUUID-specific
  `FactionsHook`: a static, stateless wrapper that checks the target
  plugin is actually enabled before touching any of its classes, so
  HCFCore runs fine without them installed.
