# HCFCore

Kits with armor-based class effects, a live sidebar scoreboard, faction-aware
chat formatting, an equippable tags system, PvP combat-tag timers, a
scheduled reboot system, PvP ability items, and a staff toolkit (vanish,
staff chat, claim-bypass build mode) — built for Paper and integrated with
**FactionsUUID**.

## Requirements

**Required:**
- **Paper 1.21.10+** (the server software)
- **FactionsUUID** (must be installed first)
- **MySQL 5.7+** or **MariaDB** (reachable from your server)

**Optional** (features work without them):
- **Vault** — Enables money costs on kits. Without it, only item/free kits work.
- **WorldGuard** — Enables disabling abilities in specific regions (like spawn). Without it, abilities work everywhere.
- **LuckPerms** — Shows player ranks in chat and enables the Repair ability. Without it, those features are disabled.
- **PlaceholderAPI** — Lets `chat.*` and `scoreboard.lines` templates use `%placeholder%` tokens (from LuckPerms' own expansion, or any other installed one) alongside this plugin's own `{curly}` placeholders. Without it, any `%...%` you put in a template is left as literal text.

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

## Configuration Guide

All settings are in `config.yml`. See the file for detailed comments on each option.

### Quick Setup
After installing, edit `config.yml` with your **MySQL credentials** only:
```yaml
mysql:
  host: your-db-host
  port: 3306
  database: hcfcore
  username: your-username
  password: your-password
```

### Key Settings Explained

**Database** — `mysql.pool-size` (default: 10) controls how many concurrent database connections are allowed. Increase for larger servers.

**Scoreboard** — Customize with `scoreboard.lines`. Use these placeholders:
- `{name}` — player name
- `{rank_prefix}` — player's LuckPerms **group's** display name, wrapped in `[brackets]` by the plugin (blank without LuckPerms, or in the built-in `default` group)
- `{rank}` — same group display name, unwrapped/uncolored
- `{prefix}` — the player's actual LuckPerms **prefix meta** (`/lp user <name> meta setprefix "..."`), with whatever color/brackets you configured in LuckPerms already baked in — use this instead of `{rank_prefix}` if you want per-user prefixes rather than one per group
- `{faction}` — player's faction name
- `{power}` — faction power (current/max)
- `{ftop}` — faction's position on power ranking
- `{fplayers_online}` — online faction members
- `{exp}` — player's XP level
- `{balance}` — player's money (if using Vault economy)

With **PlaceholderAPI** installed, any line can also use its `%percent%`
placeholders (e.g. `%luckperms_prefix%`, or anything from another
installed expansion) mixed in alongside the `{curly}` ones above —
they're expanded as a final pass over the whole resolved line, per
viewing player. Without PlaceholderAPI, a `%...%` token is left as
literal text.

**Chat** — Control the live chat format with `chat.separator`, `chat.faction-format`, `chat.rank-format`. `chat.rank-format` supports the same `{rank}` / `{prefix}` choice as the scoreboard above — `{prefix}` is substituted raw (not escaped) since it's expected to carry its own color/formatting, same treatment as a tag's `display` string. Leave `rank-format` blank if not using LuckPerms. `chat.*` templates get the same PlaceholderAPI `%percent%` support as scoreboard lines.

**PvP Cooldowns** — These override vanilla Minecraft cooldowns:
- `pearl-cooldown-seconds` — ender pearl reuse timer
- `golden-apple-cooldown-seconds` — regular gapple timer
- `enchanted-golden-apple-cooldown-seconds` — enchanted gapple timer

Importantly: **cooldowns survive logout** — players can't escape them by relogging.

**Archer Tag** — The archer class mechanic where arrows mark victims:
- `arrow-damage-bonus-per-stack` — extra damage per mark (0.15 = +15% per stack)
- `faction-melee-bonus-per-stack` — bonus for the archer's faction only
- `duration-seconds` — how long a mark lasts

**Legacy Combat** — Enable 1.8-style PvP with instant attacks. Leave `worlds: []` to apply everywhere, or list specific world names to limit it.

**Kits** — `effect-warmup-seconds` (default: 5) is the delay before class effects activate after equipping a full armor set. This gives players time to react to the visual change.

**Abilities** — `global-cooldown-seconds` is a shared cooldown across ALL ability items (using any one blocks all others for that duration). Disable regions by adding WorldGuard region names to `disabled-regions`.

**Reboot** — Customize shutdown reminders. `default-delay-minutes: 10` means `/reboot` with no argument schedules a 10-minute countdown.

All changes take effect immediately with `/hcfcore reload` — **no restart required** for config, kits, abilities, tags, or messages.

The database, scoreboard, chat, kit, ability, and tag config all take
effect immediately on `/hcfcore reload` — including `tags.yml` and
`kits.yml`, so editing either and reloading updates the live GUIs with no
restart.

Player locale and ability/kit cooldown writes are flushed during shutdown.
If player data cannot be loaded from MySQL, kit and ability claims fail closed
until the player reconnects successfully instead of silently bypassing saved
cooldowns.

## Chat Format

Chat appears as: `[faction] [tag] [rank] name » message`

Each part is optional:
- **[faction]** — faction name (omitted if factionless)
- **[tag]** — equipped cosmetic tag (omitted if none)
- **[rank]** — LuckPerms rank, either the group's display name or the
  player's own prefix meta depending on whether `chat.rank-format` uses
  `{rank}` or `{prefix}` (see **Configuration Guide** above); omitted
  without LuckPerms
- **name » message** — the actual chat message

Players can enable **nickname-match** on tags to recolor their name to match the tag's color.

## Languages

All player-facing text lives in `lang/` folder files, not hardcoded. Four languages ship by default: **en_us**, **es_us**, **pt_br**, **de_de**.

Players can change their language with `/language [code]` — their choice is saved to the database and persists across restarts.

**To add a new language:**
1. Copy `lang/en_us.yml` to `lang/xx_xx.yml` (replace `xx_xx` with your language code)
2. Translate the messages
3. Run `/hcfcore reload` — it becomes available immediately

**Color codes:**
- Use `<red>`, `<green>`, `<blue>`, etc. for colors
- `<success>`, `<deny>`, `<info>`, `<warning>` are semantic colors (don't hardcode red/green)
- Legacy `&` codes like `&4` still work
- Hex codes: `&#FF5555` for custom colors

**Placeholders:**
- `{kit}`, `{player}`, `{seconds}`, `{faction}`, etc. get filled in automatically
- Check `en_us.yml` to see what placeholders each message supports

## Kits

Edit `kits.yml` to create kits. Each kit has:
- **permission** — blank (`''`) = open to everyone, or `hcfcore.kit.name` to restrict
- **cooldown-seconds** — reuse timer (0 = no cooldown)
- **cost** — optional money or item cost (or both)
- **armor** — 4 armor pieces to give
- **contents** — items for the inventory
- **effects** — passive potion effects while wearing full armor set
- **icon** — GUI icon (Material name)
- **purpose** — one-line description in GUI

**To create a kit:**
1. Equip the armor and hold the items you want
2. Run `/kit create mykit [permission] [cooldown] [cost]`
3. Edit `kits.yml` if needed
4. Run `/hcfcore reload`

**Example cost formats:**
- `cost: {money: 50000}` — costs 50k money
- `cost: {item: DIAMOND, item-amount: 32}` — costs 32 diamonds
- `cost: {money: 50000, item: DIAMOND, item-amount: 32}` — costs both

**Class effects** — When a player wears the full armor set, they get passive buffs (e.g., Speed, Strength) after a 5-second delay. The delay gives visual feedback and prevents instant effect switching. Effects are removed immediately if any armor piece comes off.

**Armor matching** — Matching is exact: same material, enchantments, and durability (worn armor counts). A diamond kit won't match enchanted diamond armor.

## Abilities

Fifteen PvP ability items ship pre-configured in `abilities.yml`. Each has:
- **material** / **name** / **lore** — fully customizable appearance
- **cooldown-seconds** — reuse timer (saved to database, persists across restarts)
- **uses** — number of uses before breaking (optional)
- Ability-specific settings (e.g., `forward-multiplier`, `duration-seconds`)

**Ability List:**
- **Pearl Stunner** (melee) — Blocks victim's pearl use
- **Rabbits Feed** (right-click) — Speed V buff
- **Anti-Blockup Bone** (melee) — Block placement denial after N hits
- **Fake Pearl** (right-click) — Looks like ender pearl but no teleport
- **Grappling Hook** (right-click) — Fish hook pull mechanic (8 uses)
- **Leap** (right-click) — Jump forward with velocity
- **Rogue Backstab** (melee from behind) — Extra damage
- **Mage Debuffs** (melee) — Wither/Slowness/Weakness/Poison
- **Portable Bard** (right-click) — Pick a buff for your faction (in gold armor set)
- **Repair** (right-click) — Grant block-breaking permission (needs LuckPerms)
- **Switcher Snowball** (throw) — Swap positions with enemy
- **Time Warp Pearl** (right-click) — Teleport to last pearl throw location

**Key behaviors:**
- Items are only consumed after successful activation
- Cooldowns block usage again until timer expires
- `/cooldowns` shows all active cooldown timers
- **Global cooldown** (default 4s) blocks all abilities while active
- Abilities are disabled in two independent ways, both in `config.yml`
  under `abilities:`:
  - `disabled-regions` — WorldGuard region names (e.g. `spawn`). No effect
    without WorldGuard installed.
  - `disabled-claim-names` — faction **claim** names abilities are blocked
    in (default: `safezone`); abilities work everywhere else, including
    the wilderness and any other faction's claimed land. This is a
    blocklist, not an allowlist — matches the claiming faction's tag
    case-insensitively, so it also covers the system SafeZone/WarZone
    factions (`/f safezone`, `/f warzone`) if you've kept their default
    names.

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

`/tags` opens a 4×9 grid of tag icons (rows 2-5, full width) with a
control row: **filter** (Your/Unowned/All, each with a live count),
**sort** (Alphabetical/Age/Rarity — click to cycle, shift-click to flip
direction; Rarity orders by lifetime owner count, fewest first when
ascending), **search** (opens an anvil to type a query; right-click
clears it — the anvil's result slot is a free, XP-cost-free confirm
button that shows what will be searched for, and closing the anvil
applies whatever is typed exactly once), **prev/next page**, and a
**nickname-match** button (top row, your own head as the icon) that
recolors your name in chat to match your equipped tag, including a
"reversed" gradient-direction option. Its preview shows the actual chat
line it'll produce — your real faction tag, equipped cosmetic tag, and
rank, run through the live `chat.faction-format`/`chat.rank-format`
templates, not a fixed example. There's no close button — leave the GUI
the normal way (Esc / click outside). Clicking an unlocked tag equips it
and announces it in chat (`<tag> EQUIPPED`); clicking your
already-equipped tag unequips it (`<tag> UNEQUIPPED`).

Reloaded along with everything else on `/hcfcore reload`.

## Nametags

Players see nametags above each other's heads showing faction rank and
affiliation, visible to everyone (not just the wearer):

```
[ftop] [FactionName] PlayerName
```

- `[ftop]` — the faction's power-ranking position (`-` if factionless)
- `[FactionName]` — `Neutral` if factionless
- Color is one fixed color for everyone (green for faction members, gray
  for factionless by default) — a Minecraft scoreboard team's prefix
  can't show a different color to different viewers (e.g. red to
  enemies, green to allies), so this isn't ally/enemy-relative.
- Configurable in `config.yml` under `nametags:` — `enabled`,
  `update-interval-ticks`, and `colors.same-faction`/`colors.neutral`
  (any Adventure `NamedTextColor` name).
- Teams live on each **viewer's own scoreboard**, one team per
  (viewer, subject) pair — not a single shared team on the main
  scoreboard. Every player has their own `Scoreboard` object (assigned
  by the sidebar system, which replaces whatever scoreboard they had),
  and a team only renders for whoever's *currently active* scoreboard
  it's actually registered on — a shared main-scoreboard team is only
  visible on the main scoreboard, which nobody stays on once they have
  a sidebar. Nametags are re-populated onto a player's scoreboard on
  join and on every `/hcfcore reload` (both replace it with a fresh
  one), and kept in sync incrementally as factions change in between.
- Teams are keyed by a short hash of the player's UUID (not their name),
  so a Mojang username change can't orphan one. The name is kept to 14
  characters, safely under the classic 16-char vanilla scoreboard-team
  limit — a longer name works fine between modern Paper clients, but
  silently breaks nametags for anyone on an older client version (even
  bridged in via ViaVersion), which is still held to that limit
  regardless of server version.

## Faction Compatibility

**All abilities respect faction relationships:**
- ✅ Abilities can NOT be used on faction members/allies (except faction buffs)
- ✅ Portable Bard buffs work on faction members (Strength, Speed, etc.)
- ✅ Mage spells, Pearl Stunner, Backstab, etc. blocked on teammates
- ✅ Melee combat works normally on everyone
- ✅ Archer tag can't mark faction members
- ✅ Full FactionsUUID 4.4.0+ compatibility

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

## Staff Tools

Session-scoped toggles (nothing persists across a rejoin — same as
combat tags), all gated behind their own `hcfcore.staff.*` permission:

- **`/vanish`** — hides you from anyone without `hcfcore.staff.vanish`.
  Applies immediately to every online viewer and to anyone who joins
  afterward; your quit message is suppressed while vanished so leaving
  doesn't announce your name. Mobs stop targeting you the instant you
  vanish (any mob already mid-chase has its target cleared, and nothing
  can pick you as a new target while vanished), and you can't deal
  damage to anything — mobs or players, melee or projectile — while
  vanished either. Both exist for the same reason: a mob swinging at
  empty air, or a player getting hit/knocked back by nothing, gives
  away that someone invisible is there.
- **`/staffchat`** — toggles a mode where *all* your normal chat goes to
  a staff-only channel (visible to `hcfcore.staff.staffchat`) instead of
  public chat, until you toggle it off again.
- **`/staffbuild`** — bypasses FactionsUUID's claim protection entirely:
  block break/place, containers/doors, buckets, item frames/paintings,
  and entity interaction all work in any claim while it's on.
- **`/staff`** — toggles vanish + staff-build together as one "full staff
  mode" switch, and also grants **godmode** (invulnerability) and
  **flight** for the duration. Treats everything as already "on" only
  when vanish and staff-build both are — so if you'd turned one off
  individually, `/staff` turns everything back on rather than finishing
  the job of turning it off.

### Freeze

`/freeze <player>` (permission `hcfcore.staff.freeze`) locks a player in
place while staff investigate — a suspected cheater doesn't get a
ban's tip-off. While frozen, a player can't move (they can still look
around), break/place blocks, interact, deal or take any damage, drop
items, click their own inventory, or run any command. They're warned in
chat not to log out; **disconnecting while frozen bans them for 3
hours** (`Player.ban(...)`, not a kick — `/freeze` itself never kicks or
bans on its own, only leaving while frozen does), and every other online
staff member with the permission is alerted. `/freeze` again unfreezes.
Freeze state is session-scoped like the other toggles above — it doesn't
survive a server restart.

### Invsee / Endersee

`/endersee <player>` (permission `hcfcore.staff.endersee`) opens the
target's **live** ender chest — Bukkit's `openInventory` on another
player's actual inventory object is a real two-way view with no custom
GUI involved: an edit on either side shows up for both immediately.

`/invsee <player>` (permission `hcfcore.staff.invsee`) opens a custom
GUI showing the target's hotbar, main storage, **and their equipped
armor and offhand item** — there's no vanilla container type that shows
someone else's equipment, so a plain inventory view (like `/endersee`
above) only ever shows the 36 storage/hotbar slots. Unlike `/endersee`,
this isn't a live shared reference: edits are synced back to the target
one tick after each click, and armor slots reject anything that isn't
actually that armor piece (a diamond sword can't end up in the helmet
slot). A change the target makes to their own gear while the menu is
open won't show up until it's reopened.

### WarZone / SafeZone protection

Independent of the ability zone restriction above, block breaking is
blocked outright in the system WarZone and SafeZone factions (`/f
warzone`, `/f safezone`) — not just `BlockBreakEvent`, but
`BlockDamageEvent` (the moment a player starts hitting a block) and
piston push/pull, so there's no partial-break or piston-glitch way to
grief protected terrain. `/staffbuild` bypasses this too.

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

While tagged, both players see an action bar built from a fully
configurable MiniMessage template in `config.yml` under `pvp.actionbar`
— three separate templates for the three cases:

- `vs-player` — tagged against a real online opponent: `{health}`,
  `{opponent}`, `{seconds}`, `{your_cps}`, `{their_cps}`
- `vs-server` — tagged via `/combattag <you> server`: `{seconds}`, `{your_cps}`
- `vs-unknown` — tagged, but the opponent went offline: `{seconds}`, `{your_cps}`

`{seconds}` and `{health}` arrive pre-colored (the countdown fades
green→red as it runs out, health is always red) since those are computed
live and can't be a fixed color in a static template — everything else
in the wording, colors, and layout is yours to rearrange. CPS (clicks per
second) is tracked from arm swings for every online player, not just
tagged ones, so the count is already warm the instant a tag starts.

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

### Staff

| Command | Permission | Notes |
|---|---|---|
| `/staff` | `hcfcore.staff.mode` | Toggles vanish + staff-build + godmode + flight together. |
| `/vanish` | `hcfcore.staff.vanish` | Toggles vanish; this permission also lets you see other vanished staff. |
| `/staffchat` | `hcfcore.staff.staffchat` | Toggles redirecting your chat to the staff-only channel; also needed to read it. |
| `/staffbuild` | `hcfcore.staff.staffbuild` | Toggles bypassing claim protection everywhere. |
| `/freeze <player>` | `hcfcore.staff.freeze` | Toggles freezing a player in place; also needed to see the leave-while-frozen ban alert. |
| `/invsee <player>` | `hcfcore.staff.invsee` | Opens a GUI with the target's storage, armor, and offhand; synced back on each edit (not a live shared view). |
| `/endersee <player>` | `hcfcore.staff.endersee` | Opens the target's live ender chest. |

### Admin / reload

| Command | Permission | Notes |
|---|---|---|
| `/hcfcore reload` | `hcfcore.admin` | Reloads config, messages, kits, abilities, tags, and rebuilds the scoreboard for every online player. |

## Tab-completion

Every command above (`kit`, `kits`, `hcfcore`, `getitem`, `abilities`,
`language`, `cooldowns`, `tags`, `reboot`, `nextreboot`, `uncombat`,
`combatcheck`, `combattag`, `rollback`, `staff`, `vanish`, `staffchat`,
`staffbuild`, `freeze`, `invsee`, `endersee`) registers its own
`TabCompleter` (except the argument-less ones like
`kits`/`abilities`/`tags`/`cooldowns`/`staff`/`vanish`/`staffchat`/`staffbuild`), so
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
- `ChatFormatterListener` registers at `EventPriority.MONITOR`, not
  `HIGHEST`. Paper's `AsyncChatEvent` has exactly one renderer slot — the
  last handler to call `event.renderer(...)` wins outright, nothing
  merges — and FactionsUUID ships its own Paper-native chat formatter
  (enabled by default) that also sets a renderer at `HIGHEST`. At equal
  priority it came down to plugin load order which formatter actually
  showed in chat. `MONITOR` guarantees this always runs last so HCFCore's
  format always wins, regardless of load order.

## Quality Assurance & Recent Improvements

**Production-ready stability fixes:**

- **Thread-safe generation tracking** — `UserManager.nextGeneration()` uses atomic operations to prevent concurrent player data loads from colliding.
- **Atomic shutdown writes** — `KitManager` and `LanguageCommand` loop until all pending async writes complete, preventing data loss during shutdown.
- **Transaction safety** — Death records and cleanup are now atomic, preventing race conditions when multiple players die concurrently.
- **Database pool hardening** — HikariCP configured with minimum idle connections, connection timeouts, and idle/max-lifetime limits to prevent hung connections under load.
- **Rally visual fixes** — Bossbar colors now properly render MiniMessage format codes (`<green>`, `<white>`, etc.), and rally arrows display absolute compass directions instead of relative angles.
- **Message formatting** — Death GUI and rally displays properly deserialize MiniMessage color codes via `MessageFormatter.deserialize()`.
- **Performance optimization** — Message locale list is now cached, eliminating O(n log n) sorting overhead on every tab-complete.

All critical race conditions, data consistency issues, and shutdown data-loss bugs have been resolved. The plugin is fully tested and ready for production deployment.
