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
```

- `scoreboard.lines` supports `{online}`, `{faction}`, `{faction_role}` and
  legacy `&` color codes. There's no live-edit command yet — edit the file
  and run `/hcfcore reload`.
- `pvp.actionbar-update-interval-ticks` controls how often the combat
  action bar (name/timer/health/CPS) refreshes — 4 ticks (~5/sec) by
  default for a PvP-responsive feel.
- `pvp.logout-penalty`: if true, disconnecting while tagged is treated as a
  combat logout (see `PlayerConnectionListener`).

The database, scoreboard lines, and kit cooldown default all take effect
immediately on `/hcfcore reload`.

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

## Commands & Permissions

### Kits

| Command | Permission | Notes |
|---|---|---|
| `/kit <name>` | kit's own permission (default `hcfcore.kit.<name>`) | Applies the kit to your inventory, respecting its cooldown and cost. |
| `/kit save <name> [permission] [cooldownSeconds] [cost] [costItem[:amount]]` | `hcfcore.kit.save` | Saves your current inventory as a kit. `costItem` is a Material name, e.g. `DIAMOND:2`; amount defaults to 1. |
| `/kit delete <name>` | `hcfcore.kit.delete` | Deletes a kit. |
| `/kits` | *(none — open to all players)* | Opens a GUI of every kit you can see. **Left-click** claims it, **right-click** previews its contents read-only. |

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

Every command above (`kit`, `kits`, `hcfcore`, `uncombat`, `combatcheck`,
`combattag`) registers its own `TabCompleter`, so suggestions should appear
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
