# KOTH & Outposts

Vertex provides faction-aware **KOTH** and **Outpost** captures through one
shared capture engine. This keeps zone rules, contesting, schedules,
directions, holograms, and rewards identical between both event types.

KOTH is normally configured to issue one-time reward commands. An Outpost can
run reward commands too, and additionally grants its winning faction a timed
XP multiplier that applies only while faction members are inside their own
claimed land.

## Player flow

When an event starts, everyone in the same world gets a direction BossBar in
the same style as the faction-rally BossBar, pointing to the closest active
KOTH (KOTH takes priority if an Outpost is also active). An active faction
rally takes precedence, so its members keep rally navigation instead.

`/koth focus [name]` or `/outpost focus [name]` is available to every player.
It replaces only that player's rally direction with a BossBar and compass
pointing at the selected active event. Run `/koth focus off` or `/outpost
focus off` to restore rally navigation. Focus is personal; it never changes a
faction rally for anyone else.

Faction members capture together; a player without a faction may also capture
as a solo neutral claimant. Separate factionless players are competing sides,
not teammates, so they cannot combine capture speed. Vanished staff and
spectators never count. A controlling side must have at least one eligible
player inside the selected cuboid:

- One uncontested faction makes progress toward 100%.
- Extra members of that same faction speed it up using the configured
  `additional-member-speed` rate.
- Two or more factions inside freeze the current percentage exactly where it
  is.
- If nobody is inside, progress resets to 0%.
- If a different faction becomes the only one inside, it begins from 0%.

The DecentHolograms display updates once per second with event name, progress,
time remaining, and the capturing faction. A new uncontested controller causes
a configurable global chat message; reward commands run once only after the
bar reaches 100%. The reward recipient is the currently-controlling player
with the most uncontested contribution time; a first-to-arrive player wins a
tie. Vertex also writes the winner, faction, contribution time, and a failed
reward-command warning to the server log for auditing.

Events automatically expire after 45 minutes by default. This prevents an
unclaimed event from blocking future schedules; change it globally or per
event with `max-duration-seconds`.

## Staff setup

Requires `vertex.koth.admin` or `vertex.outpost.admin` (both default to OP).

1. Run `/koth create <id>` or `/outpost create <id>`. IDs use lowercase
   letters, numbers, `_`, and `-` and must be unique across both event types.
2. Vertex gives you a selection Blaze Rod. Left-click a block for the first
   corner and right-click a block for the second.
3. Sneak and click air with the rod to save the cuboid. The initial event
   entry, center-X/Z hologram position two blocks above the selection's
   lowest block, and safe defaults are written to
   `plugins/Vertex/capture-events.yml`.
4. Edit its display name, `schedule-times`, capture pace, hologram position,
   timeout, ability rule, commands, and (for Outposts) XP booster values. Run
   `/vertex reload`.
5. Test manually with `/koth start <id>` or `/outpost start <id>`. Use `stop`,
`delete`, and `list` with the same command family as needed.

`/koth wand` and `/outpost wand` give a replacement selection rod.
`/koth cancel` and `/outpost cancel` discard the caller's unfinished
selection.

`/koth validate` and `/outpost validate` check the relevant YAML entries in
game. Each staff-tagged message identifies the exact configuration path that
needs attention, such as `events.central_koth.schedule-times`. This catches
invalid IDs, types, worlds, coordinates, volume, schedules, timeouts, and
Outpost XP-booster values before an event is started.

Only one KOTH and one Outpost can run at the same time. A scheduled event that
would overlap another event of its own type is skipped rather than replacing
the live event.

## `capture-events.yml`

The file is created on the first boot with commented examples. Its main
settings are:

| Path | Meaning |
|---|---|
| `enabled` | Master switch for staff starts and schedules. |
| `schedule-time-zone` | Java time-zone used by every `HH:mm` schedule. |
| `defaults.capture-seconds` | Base time for one uncontested faction member. |
| `defaults.max-duration-seconds` | Maximum live event duration; defaults to `2700` (45 minutes). |
| `defaults.additional-member-speed` | Added speed fraction per extra member; `0.25` makes two players 1.25× as fast. |
| `neutral-display-name` | Label for a factionless solo claimant in captures, holograms, and reward commands. |
| `selection.maximum-volume` | Maximum inclusive cuboid size a staff selection may save. |
| `hologram.lines` | DecentHolograms text; supports `{type}`, `{name}`, `{progress}`, `{remaining}`, `{faction}`. |
| `events.<id>.hologram` | Explicit `{ x, y, z }` hologram position. New staff selections default to the cuboid centre at X/Z and `minimum.y + 2`; change this only when a custom position is wanted. |
| `events.<id>.schedule-times` | Empty for manual only, or a list such as `['18:00', '21:00']`. |
| `events.<id>.max-duration-seconds` | Overrides the global event timeout. |
| `events.<id>.disable-abilities` | When true, blocks Vertex ability items used inside that active capture cuboid. |
| `events.<id>.rewards.commands` | Console commands after a successful claim. `{player}`, `{faction}`, `{faction_id}`, `{event}`, `{name}`, `{type}` are replaced. |
| `events.<id>.rewards.xp-booster` | Outpost-only faction XP reward: `enabled`, `multiplier`, `duration-seconds`. Active boosts multiply together. |

All player-facing chat, action-bar, BossBar, selection, and command messages
are in `lang/en_us.yml` under `capture`, with matching keys in the bundled
locales. Hologram text is intentionally in `capture-events.yml`, so operators
can edit its layout without touching a language file.

## Persistence and dependencies

Definitions, schedules, and active Outpost XP booster expiry times persist.
Each Outpost booster is stored independently so future shop boosters can stack
multiplicatively. A faction disband removes its remaining boosters.
Live KOTH/Outpost captures intentionally stop on a full server restart or
`/vertex reload`; this prevents the server from quietly finishing an
unattended capture. Players can start the configured event again afterward.
At startup Vertex also removes stale configured capture holograms left by an
unclean shutdown.

DecentHolograms is optional: captures, directions, schedules, and rewards
still work without it, but no capture hologram is created until the dependency
is installed and enabled.
