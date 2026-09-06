# Factions Integration

Vertex is built directly on top of **FactionsUUID** — it's a hard
dependency, not an optional one. This page covers everything that plugs
into faction identity and relationships: nametags, rallies, the
permissions GUI, and a couple of small protections. Chat formatting and
scoreboard placeholders are covered in
[Configuration](configuration.md#chat-formatting) since they're primarily
config-driven.

## Nametags

Every player sees a nametag above everyone else's head:

```text
[ftop] [FactionName] PlayerName
```

- `[ftop]` — the faction's power-ranking position (`-` if factionless).
- `[FactionName]` — shows `Neutral` if factionless.
- **Color is relative to the viewer, not fixed.** Your own faction
  renders green, an allied faction renders light purple, and everyone
  else — enemies, truce/no-relation factions, and factionless players
  alike — renders red by default (all four colors configurable under
  `nametags.colors`). The same subject genuinely renders differently to
  different viewers simultaneously, because each nametag is its own
  scoreboard team registered on that specific *viewer's* own scoreboard,
  not a single shared team.
- Ally/enemy status comes from FactionsUUID's real `/f ally` / `/f enemy`
  relations. The more hostile of the two factions' one-directional wishes
  wins — a one-sided ally wish alone doesn't count, but a one-sided enemy
  wish does.
- Teams are keyed by a short hash of the player's UUID, not their name,
  so a username change can't orphan one. Team names are kept to 14
  characters — safely under the classic 16-character vanilla scoreboard
  team-name limit, which still applies to any older client bridged in via
  ViaVersion regardless of the server's own version.
- Nametags are rebuilt on join and on every `/vertex reload`, and kept
  in sync incrementally as factions change in between.

Toggle the whole system with `nametags.enabled`; refresh rate is
`nametags.update-interval-ticks`.

## Rally

`/f rally [set|clear]` (alias `/frally`, open to all faction members) —
with no argument, sets a rally point at your current location, visible to
your whole faction for 4 minutes; `clear` removes it early.

Faction members see a green bossbar with live distance and a compass
arrow pointing toward the rally, refreshing every 2 ticks (10×/second).
The arrow points to a true compass bearing (north stays north) rather
than one relative to the viewer's own facing. Rally indicators only
display in the same world the rally was set in, preserve each player's
prior compass target on expiry, and clean up their bossbars on shutdown.

## Rally / Faction Permission GUI

A faction leader opens the complete FactionsUUID permission matrix with
`/f permissions` (or `/f perms`, or any alias configured under
`factions.command-aliases`):

- The top row selects which role's permissions you're editing:
  **Moderator** (applies to both FactionsUUID's Co-Leader and Moderator
  roles), **Member**, or **Recruit**.
- The grid lists every FactionsUUID native permission plus Vertex's **Set
  Rally**, **Clear Rally**, **Add Spawners**, **Remove Spawners**, **Open
  Collectors**, and **Break Collectors** actions. Every permission is a
  green stained-glass pane when allowed for that role and a red pane when
  denied. The state material is deliberately not configurable.
- All visible GUI text (title, roles, action names, status, and click
  instructions) renders in small caps. Color tags in `config.yml` still
  work normally.
- **Left-click allows**, **right-click denies**. Changes save immediately
  to FactionsUUID's own permission system for its native actions, and to
  Vertex's own per-faction storage for the six Vertex-specific actions.
- **Admin is intentionally not selectable.** FactionsUUID always permits
  its own Admin role to perform every native action regardless of any
  configured permission, so there is nothing for this GUI to toggle for
  Admin.

The title, each role's slot/icon/name, and any action's label override
live under `rally.permission-gui` in `config.yml` (see
[Configuration](configuration.md#rally-permission-gui)). Per-faction rally
permission choices are saved under `rally.faction-permissions`.

## Leader-leave protection

`factions.prevent-leader-leave` (default `true`) blocks a faction
leader's `/f leave` — and any alias listed in `factions.command-aliases`
— with an explanatory message instead of letting it through. This
prevents a leader from leaving their own faction (accidentally or as an
exploit) without transferring leadership to someone else first.

## Faction compatibility

Every hostile ability, and Archer Tag, refuses to target faction members
or allies; Portable Bard's buffs are the deliberate exception, designed
to be shared with your own faction. See
[PvP & Combat](pvp-and-combat.md#faction-compatibility) for the full
list. Vertex is built and tested against FactionsUUID 4.4.0+.
