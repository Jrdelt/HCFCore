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

## Faction upgrades

`/f upgrades` (or `/f upgrade`, including every configured faction-command
alias) opens a persistent per-faction upgrade GUI. Any faction member can
inspect it; by default, only the faction leader can purchase levels. Every
cost is withdrawn from the leader through Vault and every upgrade level is
saved in Vertex's database, so it survives restarts and faction renames.

All values are configurable in `faction-upgrades.upgrades` in `config.yml`:

- **Damage in Claims** increases damage dealt by members standing in their
  own claim.
- **Claim Protection**, **Armor Wear**, and **Fall Protection** reduce the
  corresponding damage/durability loss while a member is in their claim.
- **Fly Boost** increases the speed of members who are already flying in
  their claim. It deliberately does not grant flight itself, so it stays
  compatible with the server's FactionsUUID flight rules and other flight
  plugins.
- **Faction Warps** sets the faction's native FactionsUUID `WARPS` upgrade
  level, so its normal `/f warp` commands and the limit configured by
  FactionsUUID continue to own warp creation and teleportation. Existing
  native warp levels are adopted on first menu view and are never lowered.
- **Spawner Rate** retunes Vertex spawners in the faction's claim at once,
  including the manual Iron Golem fallback. The configured normal spawner
  limits remain the baseline and scale with the earned rate.
- **Crop Growth** gives each crop growth stage an additional configurable
  chance to advance one more stage.
- **Mob Experience** increases XP dropped when a faction member kills a
  mob in that faction's claim.

Set an individual `enabled: false` or set `faction-upgrades.enabled: false`
to take it out of service without deleting saved levels. A faction disband
cleans up its Vertex upgrade rows automatically. See
[Configuration](configuration.md#faction-upgrades) for the complete setup.

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
