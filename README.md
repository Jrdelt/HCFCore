# HCFCore

Kits with armor-based class effects, a live sidebar scoreboard, faction-aware
chat formatting, an equippable tags system, PvP combat-tag timers, a
scheduled reboot system, and PvP ability items — built for Paper and
integrated with **FactionsUUID**.

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
- **LuckPerms** — optional; used for the `repair` ability (grants an
  EssentialsX-style permission node on a timer) and for showing a
  player's primary group as a rank in chat. Softdepended. Without it,
  `repair` tells the player it's unavailable, and chat simply omits the
  rank bracket. Even with LuckPerms installed, a player still in the
  built-in `default` group (i.e. never promoted, or promoted to a group
  with no custom display name) shows no rank bracket either — only a
  group with an actual display name renders one.

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
  allow-public-key-retrieval: true
  use-ssl: false

scoreboard:
  update-interval-ticks: 20
  title: '<blue><bold>ꜰᴀᴄᴛɪᴏɴꜱ<reset>   <gray>{date}'
  date-format: 'MM/dd'
  lines:
    - '     <gray>Season I'
    - '   <gray>{rank_prefix}{name}'
    - '<gray>• <red>Experience: <white>{exp}'
    - '<gray>• <red>Balance: <white>{balance}'
    - ''
    - '<white>{faction} <gray>[<yellow>#{ftop}<gray>]'
    - '<gray> <red>• Power: <white>{power}'
    - '<gray> <red>• Online: <white>{fplayers_online}'
    - ''
    - '<gray>hub.mc-vertex.com'

chat:
  separator: ' <gray>» </gray>'
  faction-format: '<gray>[{faction}]</gray> '
  rank-format: '<gray>[{rank}]</gray> '
  name-format: '{name} '

pvp:
  combat-tag-seconds: 30
  pearl-cooldown-seconds: 12
  golden-apple-cooldown-seconds: 8
  enchanted-golden-apple-cooldown-seconds: 120
  logout-penalty: true
  actionbar-update-interval-ticks: 2
  archer-tag:
    duration-seconds: 10
    max-stacks: 4
    arrow-damage-bonus-per-stack: 0.15
    faction-melee-bonus-per-stack: 0.05
  legacy-combat:
    enabled: true
    worlds: []
    attack-speed: 1024.0
    disable-sweeping-attacks: true

factions:
  prevent-leader-leave: true
  command-aliases:
    - f
    - factions

kits:
  default-cooldown-seconds: 30
  effect-warmup-seconds: 5
  max-cooldown-seconds: 86400
  max-money-cost: 1000000000.0
  max-cost-item-amount: 64

abilities:
  global-cooldown-seconds: 4
  max-getitem-amount: 64
  disabled-regions:
    - spawn

reboot:
  default-delay-minutes: 10
  reminder-minutes:
    - 10
    - 5
    - 1
```

- `scoreboard.title` and every entry in `scoreboard.lines` accept
  `{date}` (formatted per `scoreboard.date-format`) plus `{name}`,
  `{rank}` / `{rank_prefix}` (LuckPerms primary group; `rank_prefix` adds
  brackets and a trailing space only when a rank exists), `{exp}` (XP level), `{balance}` (Vault balance, formatted, `0` without
  an economy plugin), `{online}`, `{faction}`, `{faction_role}`,
  `{ftop}` (the player's faction's power ranking), `{power}`
  (`current/max`, comma-formatted), `{fplayers_online}`, and `{repair}`
  (fed live by the Repair ability's countdown). The title re-resolves
  every tick and only pushes a packet when it actually changes, so
  `{date}` rolls over at midnight with no restart needed.
- `chat.*` controls the live chat renderer (see **Chat format** below) —
  separate from `lang/*.yml`'s `general.prefix`, which is the branded
  prefix shown before *system* messages (command feedback, errors), not
  regular player chat.
- `pvp.actionbar-update-interval-ticks` controls how often the combat
  action bar (name/timer/health/CPS) refreshes — 2 ticks (10/sec) by
  default for a PvP-responsive feel.
- `pvp.pearl-cooldown-seconds` /
  `pvp.golden-apple-cooldown-seconds` /
  `pvp.enchanted-golden-apple-cooldown-seconds`: HCF-style cooldowns on the
  vanilla items, enforced by the plugin rather than by Minecraft's own
  client cooldown — the two apple types stay independent even though
  vanilla groups them together. Using one while it's still cooling down is
  cancelled with a chat message, and the remaining time shows up in
  `/cooldowns`. Tracked in memory and deliberately *not* cleared on
  disconnect, so relogging can't reset a pearl or gapple timer mid-fight.
  A pearl right-click that opens a block (chest, door, anvil) instead of
  throwing is ignored by both halves of this — it neither starts the
  cooldown nor gets blocked by one.
- `pvp.archer-tag.*`: the archer class mechanic (see **Archer tag**
  below). `duration-seconds` is how long a mark lasts and is refreshed by
  every new arrow, `max-stacks` caps how deep focus fire can go, and the
  two `*-bonus-per-stack` values are fractions (`0.15` = +15% per stack)
  applied to arrow damage and to the archer's faction's melee damage
  respectively.
- `pvp.logout-penalty`: if true, disconnecting while tagged is treated as a
  combat logout (see `PlayerConnectionListener`).
- `pvp.legacy-combat`: enables the 1.8-style attack-speed behavior. Empty
  `worlds` applies it everywhere; `disable-sweeping-attacks` removes modern
  sweeping damage.
- `factions.prevent-leader-leave`: prevents faction leaders from using
  `/f leave` or `/factions leave`; `/f disband` remains the explicit disband
  command. Aliases are configurable in `factions.command-aliases`.
- `kits.effect-warmup-seconds`: delay between putting on a kit's full
  armor set and its class effects (see **Kits**) actually applying.
- `abilities.global-cooldown-seconds` is a shared cooldown across *all*
  ability items — using any one of them starts it, blocking every other
  ability until it expires, independent of each item's own cooldown.
- `abilities.disabled-regions` is a list of WorldGuard region ids (not
  world names) where ability items refuse to activate — checked at the
  player's location, across any world, so a region called `spawn` blocks
  abilities inside it wherever it's defined.
- `reboot.default-delay-minutes` / `reminder-minutes`: default countdown
  length for `/reboot` (with no argument) and which minute marks get a
  broadcast reminder.
- `language.default` is the locale (see below) every player gets until
  they run `/language` to pick their own.

The database, scoreboard, chat, kit, ability, and tag config all take
effect immediately on `/hcfcore reload` — including `tags.yml` and
`kits.yml`, so editing either and reloading updates the live GUIs with no
restart.

Player locale and ability/kit cooldown writes are flushed during shutdown.
If player data cannot be loaded from MySQL, kit and ability claims fail closed
until the player reconnects successfully instead of silently bypassing saved
cooldowns.

## Chat format

Every chat message renders as:

```
[faction] [tag] [rank] name » message
```

- `[faction]` — the player's FactionsUUID tag, via `chat.faction-format`;
  omitted entirely for a factionless player.
- `[tag]` — the player's currently equipped tag (see **Tags** below);
  omitted if they have none equipped. Its color comes from the tag's own
  `display` in `tags.yml`, not a single uniform color for every tag.
- `[rank]` — the player's LuckPerms primary group display name, via
  `chat.rank-format`; omitted without LuckPerms, or if the group has no
  configured display name (including the built-in `default` group every
  player starts in — see **Requirements**).
- The name itself normally uses `chat.name-format`, but if the player has
  **nickname-match** enabled on their equipped tag (see **Tags**), it's
  recolored to match that tag's color/gradient instead.

## Language (`lang/*.yml`)

Every player-facing message — every command reply, error, GUI label, and
the combat action bar — lives in locale files under `lang/`, not hardcoded
in Java. Four ship out of the box: `en_us` (the reference locale; every
key is guaranteed to exist here), `es_us`, `pt_br`, and `de_de` (translated
without native-speaker review — treat them as a solid starting point, not
a final proofread).

```yaml
kit:
  applied: '<success>Applied kit {kit}.'
  cooldown: '<deny>You can use this kit again in {seconds}s.'
```

- Each message is a key → a MiniMessage-formatted template string.
  `<deny>`, `<success>`, `<info>`, and `<warning>` are semantic aliases for
  red, green, gray, and yellow. Legacy `&` color codes remain supported,
  including legacy hex (`&#RRGGBB`). `{placeholders}` like
  `{kit}`/`{seconds}`/`{player}` get substituted per-message; check
  `en_us.yml` for exactly which ones a given key accepts.
- `general.prefix` is the branded prefix (`ᴠᴇʀᴛᴇx ➛` by default) shown
  before every *system* message — command feedback, errors, confirmations
  — sourced from this key rather than hardcoded, so it's themeable and
  translatable per locale like everything else. It's unrelated to live
  chat, which has its own look controlled by `chat.*` in `config.yml`.
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
cost, armor/contents, and optional class effects:

```yaml
kits:
  diamond:
    permission: ''
    cooldown-seconds: 30
    icon: DIAMOND_CHESTPLATE
    purpose: '<gray>All-round tank.'
    armor:
      - material: DIAMOND_HELMET
        amount: 1
      - material: DIAMOND_CHESTPLATE
        amount: 1
    contents:
      - material: DIAMOND_SWORD
        amount: 1
        enchantments:
          UNBREAKING: 3
      - material: SPLASH_POTION
        amount: 1
        potion-effect: SPEED
        potion-duration-ticks: 3600
    effects:
      - type: SPEED
        amplifier: 0
      - type: ABSORPTION
        amplifier: 0
```

- `permission` — required to claim the kit; **blank (`''`) means open to
  everyone**, with no permission check at all. The six shipped base kits
  (`archer`, `miner`, `bard`, `diamond`, `rogue`, `mage`) ship this way;
  their `-donator` variants keep a real permission node
  (`hcfcore.kit.<name>.donator`).
- `cooldown-seconds` — time before the kit can be claimed again; `0` means
  no cooldown. Bypassed by `hcfcore.kit.bypasscooldown`.
- `cost` — optional; omit entirely for a free kit (none of the shipped
  kits have one). `cost.money` charges via Vault; `cost.item` +
  `cost.item-amount` charges that many of a Material from the player's
  inventory. Either, both, or neither can be set — both are checked and
  charged as an atomic pair, only deducting if the player can afford
  everything. Bypassed by `hcfcore.kit.bypasscost`.
- `icon` — optional Material name overriding the `/kits` GUI icon. The
  default is the kit's first non-air armor piece, else its first content
  item, which doesn't always pick the recognizable piece — the shipped
  `mage`/`mage-donator` kits set `CHAINMAIL_LEGGINGS` so they don't show
  the same gold helmet as `bard`. An unknown material falls back to the
  default rather than erroring.
- `purpose` — optional one-line role blurb (MiniMessage or legacy `&`)
  shown in the `/kits` GUI lore under the claimable/unclaimable line.
  Omit it and nothing is shown. Both `icon` and `purpose` are preserved
  by `/kit create`'s rewrite of `kits.yml`.
- `armor` / `contents` — each entry is a plain map: `material` (or
  `type`), `amount` (or `count`), an optional `enchantments` map
  (`ENCHANTMENT_NAME: level`), an optional `potion-effect` +
  `potion-duration-ticks` + `potion-amplifier` (for splash potions), and
  an optional `ability` id tying the item to one of the ability listeners
  in `abilities.yml`. The easiest way to populate these is `/kit save`,
  then hand-edit `permission`/`cooldown-seconds`/`cost` afterward. Use
  `/kit create`; the older `/kit save` alias remains supported.
- `effects` — optional list of passive potion effects (`type` +
  `amplifier`) granted while the player is wearing the kit's *exact* full
  armor set (all four pieces, matched by material/enchants/etc., with
  durability damage ignored so a worn set still counts), and removed the
  moment any piece comes off. Applying is delayed by
  `kits.effect-warmup-seconds` after the armor set first goes on, with a
  chat message; an unrelated potion effect from PvP that happens to
  override the same effect type doesn't retrigger that warmup or message
  — the class effect just silently resumes once the external one wears
  off, as long as the armor itself was never removed.
- Claiming a kit never replaces worn armor. If armor is already equipped, the
  kit armor is placed in storage inventory, and the claim is rejected with a
  localized full-inventory message when it cannot fit. A kit cost is charged
  only after all capacity and economy checks succeed.

Reloaded along with everything else on `/hcfcore reload`.

## Abilities (`abilities.yml`)

Fifteen PvP ability items ship pre-registered (the four `mage-*` debuffs
share one row below), each with real gameplay behavior:

| Ability | Trigger | What it does |
|---|---|---|
| `pearl-stunner` | Melee hit | Stops the victim from using pearls for `stun-seconds`. |
| `rabbits-feed` | Right-click | Grants yourself Speed V for `speed-duration-seconds`. |
| `anti-blockup-bone` | Melee hit | After `hits-required` hits, the victim can't place blocks for `deny-seconds`. |
| `fake-pearl` | Right-click | Throws a real `EnderPearl` (identical arc/sound) but cancels the teleport it would trigger on landing. |
| `grappling-hook` | Right-click (twice) | First click casts a `FishHook`; a second click while it's out pulls you toward it, scaled by `forward-multiplier`/`y-multiplier`. |
| `leap` | Right-click | Sets your velocity forward and slightly upward, scaled by `forward-multiplier`/`y-multiplier`, plus a short buff (`effect-type`/`effect-amplifier`/`effect-duration-seconds`). |
| `rogue-backstab` | Melee hit, from behind | Deals bonus `damage` to an enemy struck from behind; consumed on use. |
| `mage-wither` / `mage-slowness` / `mage-weakness` / `mage-poison` | Melee hit | Applies the named debuff (`effect-type`/`effect-amplifier`/`effect-duration-seconds`) to whoever you hit. |
| `portable-bard` | Right-click | Opens a GUI to pick Strength/Speed/Resistance/Regeneration/Jump Boost; applies it, for `buff-seconds`, to you and every online member of your faction. In the full gold bard set the item cooldown is `bard-cooldown-seconds` (6s) instead of `cooldown-seconds`, and putting the gold set on shortens an outstanding out-of-class cooldown down to that same wait, so a player who used the item in another kit isn't locked out of the bard kit they just geared into. Each individual buff also has its own `buff-cooldown-seconds` (7s) cooldown, so buffs can be rotated but not repeated back to back. |
| `repair` | Right-click | Grants `permission-node` (default `essentials.fix`) via LuckPerms for `duration-seconds`, with a live countdown on your scoreboard. Requires LuckPerms installed. |
| `switcher-snowball` | Throw + hit | Swaps positions with whichever enemy (not a faction member) it hits. |
| `time-warp-pearl` | Right-click | Teleports you back to wherever you last threw a *real* ender pearl from. |

```yaml
abilities:
  grappling-hook:
    material: FISHING_ROD
    name: '<light_purple>Grappling Hook'
    lore:
      - '<gray>Hook a block or player and'
      - '<gray>reel yourself toward it.'
    cooldown-seconds: 20
    uses: 8
    forward-multiplier: 1.6
    y-multiplier: 0.8
```

- `material` / `name` / `lore` — fully configurable per ability; `name`
  and each `lore` line accept MiniMessage tags and legacy `&` color codes.
- Ability items are consumed only after successful activation. The grappling
  hook has 8 uses by default and breaks after its eighth successful pull;
  `rogue-backstab` is consumed on its single use.
- `cooldown-seconds` — per-item cooldown, persisted to MySQL (its own
  `ability_cooldowns` table, separate from kit cooldowns) so it survives a
  restart. `/cooldowns` lists a player's own active kit, ability, global,
  and vanilla-item cooldowns.
- Everything else under an ability's section (`forward-multiplier`,
  `hits-required`, `buff-seconds`, `duration-seconds`, `permission-node`,
  `effect-type`/`effect-amplifier`/`effect-duration-seconds`, etc.) is
  that ability's own extra config, documented in the table above. All
  per-ability tuning lives here rather than in `config.yml`, which only
  carries the server-wide `global-cooldown-seconds`,
  `max-getitem-amount`, and `disabled-regions`.
- `switcher-snowball` and `portable-bard` use **FactionsUUID** to tell
  faction members apart from enemies — see `FactionsHook`.
- `repair` degrades gracefully without LuckPerms installed: it tells the
  player it's unavailable and doesn't consume a cooldown, the same way a
  kit's money cost behaves without Vault.

Reloaded along with everything else on `/hcfcore reload`.

## Tags (`tags.yml`)

An equippable, cosmetic tags system with a paginated `/tags` GUI:

```yaml
tags:
  legend:
    display: "<gradient:#facc15:#f97316>Legend"
    permission: hcfcore.tag.legend
    created-at: 1725235200000
    owners: 0
    lore:
      - '<gray>A name known by all.'
players: {}
```

- `display` — the tag's name **and** its color/gradient in one MiniMessage
  (or legacy `&`) string; there's no separate color field. Sorting,
  searching, and nickname-matching all resolve the leading color/gradient
  tag out of `display` automatically.
- `permission` — blank means unlocked for everyone, same convention as
  kits; otherwise required to unlock/equip the tag.
- `created-at` — epoch millis; shown in the GUI as `MM/yy`.
- `owners` — a lifetime counter of how many times players have equipped
  this tag (not a live "currently equipped" count — unequipping doesn't
  roll it back).
- `lore` — optional extra flavor lines shown under the equipped/unequipped
  status line, above the auto-generated Created/Owners lines.
- `material` / `custom-model-data` are still read and round-tripped by
  `TagManager` but currently unused by the GUI — every tag renders as a
  plain `NAME_TAG`, including locked ones (there's no separate locked
  icon anymore either).
- `players` — per-player state (equipped tag id, nickname-match on/off,
  nickname-match reversed), keyed by UUID; managed entirely by the plugin,
  not meant for hand-editing.

`/tags` opens a 4×7 grid of tag icons (kept off the inventory's outer
edge) with a control row: **filter** (Your/Unowned/All, each with a live
count), **sort** (Alphabetical/Age — click to cycle, shift-click to flip
direction), **search** (opens an anvil to type a query; right-click
clears it — the anvil's result slot is a free, XP-cost-free confirm
button that shows what will be searched for, and closing the anvil
applies whatever is typed exactly once), **prev/next page**, and a **nickname-match** preview (top
middle) that recolors your name in chat to match your equipped tag,
including a "reversed" gradient-direction option. There's no close
button — leave the GUI the normal way (Esc / click outside). Clicking an
unlocked tag equips it and announces it in chat (`<tag> EQUIPPED`);
clicking your already-equipped tag unequips it (`<tag> UNEQUIPPED`).

Reloaded along with everything else on `/hcfcore reload`.

## Archer tag

A player wearing the full **leather** set (the `archer` kit or its donator
variant — matched by `ArmorClass.isArcher`, same material-only rule the
bard uses) marks whoever their arrows hit:

- Each arrow hit adds a stack, up to `pvp.archer-tag.max-stacks`, and
  refreshes the mark's `duration-seconds`. Stacks are shared across every
  archer shooting that player, so two archers focusing one target stack
  twice as fast.
- **Arrows** landing on a marked player deal
  `arrow-damage-bonus-per-stack` extra damage per stack. The arrow that
  *opens* the mark deals normal damage — the bonus only applies from the
  next arrow on.
- **Melee** hits get `faction-melee-bonus-per-stack` per stack, but only
  for members of a faction whose own archer put a mark on that player.
  A rival faction, or a bystander, deals normal melee damage no matter
  how deep the stack is. A factionless archer's mark gives *nobody* the
  melee bonus (including themselves) — there's no faction to grant it to.
- Both the archer and the target get a chat message on every hit, naming
  the other player, the stack's current arrow and melee percentages, and
  the seconds left (`archer.tag-applied` / `archer.tag-received` in
  `lang/*.yml`).
- Archers can't mark their own faction members, and the mark clears on
  death. It deliberately survives a disconnect, so relogging isn't a way
  to shed it mid-fight.

## Reboot scheduling

`/reboot [minutes]` starts a countdown (default `reboot.default-delay-minutes`)
to a full server shutdown, broadcasting reminders at
`reboot.reminder-minutes` marks; `/reboot cancel` stops it. `/nextreboot`
shows any player the currently scheduled countdown, if one is running.

## Commands & Permissions

### Kits

| Command | Permission | Notes |
|---|---|---|
| `/kit <name>` | kit's own permission (blank for the six base kits — open to everyone; the `-donator` variants require theirs) | Applies the kit to your inventory, respecting its cooldown and cost. |
| `/kit create <name> [permission] [cooldownSeconds] [cost] [costItem[:amount]]` | `hcfcore.kit.create` | Saves your current inventory as a kit. `costItem` is a Material name, e.g. `DIAMOND:2`; amount defaults to 1. The older `/kit save` alias remains supported (`hcfcore.kit.save`). |
| `/kit delete <name>` | `hcfcore.kit.delete` | Deletes a kit. |
| `/kits` | *(none — open to all players)* | Opens a fixed 4-row GUI: non-donor kits fill row 2, each one's `-donator` variant sits directly below it in row 3 (same column), both kept off the outer columns. Seven columns per page, with labeled arrow buttons in the top corners when there are more; a donor kit with no matching base kit still gets its own column. **Left-click** claims it, **right-click** previews its contents read-only. |

### Tags

| Command | Permission | Notes |
|---|---|---|
| `/tags` | *(none — open to all players)* | Opens the tags GUI (see **Tags** above). |

### Abilities

| Command | Permission | Notes |
|---|---|---|
| `/getitem <username> <ability> [amount]` | `hcfcore.ability.give` | Gives a player ability items directly, ignoring cooldowns. |
| `/abilities` | *(none — open to all players)* | Opens a GUI listing every ability's name/lore. A viewer with `hcfcore.ability.give` who clicks one receives a copy; everyone else's click just closes/does nothing. |
| `/cooldowns` | *(none — open to all players)* | Shows your own active cooldowns: kits, ability items, the shared global ability cooldown, and the vanilla pearl/golden-apple/enchanted-golden-apple timers. |

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
`Combat: ❤ {health}   {opponent} ({time}s) You ({yourCps}cps) Them ({theirCps}cps)`
— the opponent's hearts lead, in red, spaced off from their name (the
opponent segment just reads `Server`, with no health or "Them" CPS, when
tagged via `/combattag <you> server`). The timer gradients from green to
red as it runs down. CPS (clicks per second) is tracked from arm
swings for every online player, not just tagged ones, so the count is
already warm the instant a tag starts.

### Reboot

| Command | Permission | Notes |
|---|---|---|
| `/reboot [minutes]` | `hcfcore.reboot.start` | Starts a shutdown countdown. |
| `/reboot cancel` | `hcfcore.reboot.start` | Cancels an in-progress countdown. |
| `/nextreboot` | *(none — open to all players)* | Shows the currently scheduled countdown, if any. |

### Staff / Rollback

| Command | Permission | Notes |
|---|---|---|
| `/rollback <player>` | `hcfcore.staff.rollback` | Opens a death history GUI for a player. Shows the last 20 deaths with timestamps, death causes, and killer info. **Left-click** a death to restore all items to your inventory; **right-click** to view the death's contents in detail. |

The death history system automatically captures:
- **Inventory items** from the moment of death
- **Armor pieces** (helmet, chestplate, leggings, boots)
- **Off-hand items**
- **Death timestamp** (formatted as MM/dd HH:mm:ss)
- **Death cause** (damage type)
- **Killer name** (if killed by a player; otherwise shows "Environment")

Each player's death history persists to MySQL and stores the last 20 deaths automatically. Older deaths are purged, so the database footprint stays constant per player.

**Death restoration workflow:**
1. Staff member runs `/rollback <player>` to open the death list
2. Each death shows its number, timestamp, cause, and killer
3. **Left-click** to restore all items to the staff member's inventory (overflow items drop to the ground)
4. **Right-click** to view exactly what items/armor/offhand the player had at that death
5. The "Back" button in the contents view returns to the death list

### Admin / reload

| Command | Permission | Notes |
|---|---|---|
| `/hcfcore reload` | `hcfcore.admin` | Reloads config, messages, kits, abilities, tags, and rebuilds the scoreboard for every online player. |

## Tab-completion

Every command above (`kit`, `kits`, `hcfcore`, `getitem`, `abilities`,
`language`, `cooldowns`, `tags`, `reboot`, `nextreboot`, `uncombat`,
`combatcheck`, `combattag`, `rollback`) registers its own `TabCompleter` (except the
argument-less ones like `kits`/`abilities`/`tags`/`cooldowns`), so
suggestions should appear as soon as the freshly built jar is running on
the server. If they don't show up in-game:

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
  messages). Item display names/lore always run through an explicit
  `TextDecoration.ITALIC, false` — Minecraft renders those italic by
  default when unset, which otherwise silently affects any raw
  `Component.text(...)` used in a GUI.
- All MySQL access goes through HikariCP off the main thread.
- HikariCP and the MySQL driver are shaded and relocated into
  `me.hcfcore.core.libs.*` to avoid classpath collisions with other plugins.
- GUIs (`/kits`, `/tags`, `/abilities`, kit preview) use a static-nested
  `Holder implements InventoryHolder` per menu to identify their own
  inventories in click listeners, rather than comparing title strings.
  The tags GUI additionally rebuilds itself from an immutable
  `TagMenuState` (sort/filter/page/search) on every click rather than
  mutating anything in place.
- `GradientColor` (in the `tag` package) is a small pure-function utility:
  reversing a MiniMessage gradient's stop order, and extracting/stripping
  a tag's leading color (MiniMessage or legacy `&`/`&#RRGGBB`) from its
  `display` string, since tags embed their color directly rather than
  storing it separately.
- Class detection for gameplay rules that key off "what class is this
  player" (currently Portable Bard's short cooldown) goes through
  `ArmorClass`, which matches armor **material** only — worn durability,
  donator enchantments, and repairs must not drop a player out of their
  class mid-fight. That's deliberately looser than the exact-set match
  `KitManager` uses to decide whether a kit's passive `effects` apply, and
  strict enough to keep the mage set (gold helmet + gold boots over
  chainmail) from reading as a bard.
- `ArcherTagManager` stores faction **ids**, not `Faction` objects or
  player lookups, and takes them as plain ints from the caller. That keeps
  the stacking/expiry logic unit-testable with no Factions plugin running,
  and confines the FactionsUUID API to `ArcherTagListener`.
  `FactionsHook.NO_FACTION` is the factionless sentinel and never matches
  anything, so a mark from a factionless archer grants no melee bonus
  rather than arming every factionless player.
- Short-lived cooldowns that would be pointless to persist —
  `VanillaCooldownManager`'s pearl/gapple timers, Portable Bard's per-buff
  timers in `AbilityManager` — live in memory, keyed by UUID. Their quit
  handlers only drop entries that have already expired; clearing live ones
  would turn a relog into a cooldown reset.
- `CombatManager.SERVER_UUID` (`new UUID(0, 0)`) is a reserved sentinel
  opponent id used only by `/combattag ... server` — never a real player's
  UUID, so it can't collide.
- Kit class effects are driven by a single every-tick pass
  (`KitManager.checkArmorEffects`) that compares each online player's worn
  armor against every kit's exact armor set. It deliberately only checks
  effect *presence*, not amplifier, when topping up an already-active
  kit's effects — an external potion (PvP, milk bucket, etc.) sharing an
  effect type with the kit legitimately overrides it without the kit
  effect having "fallen off", and re-triggering the warmup/message for
  that would misfire mid-fight.
- `LuckPermsHook.getPrimaryGroupDisplayName` returns `null` (not the raw
  group id) for LuckPerms' built-in `default` group when it has no
  configured display name, so chat doesn't print the literal word
  `default` as everyone's rank.
- `EconomyHook` and `WorldGuardHook` (Vault and WorldGuard are both
  softdepends) share one shape with the FactionsUUID-specific
  `FactionsHook`: a static, stateless wrapper that checks the target
  plugin is actually enabled before touching any of its classes, so
  HCFCore runs fine without them installed.
