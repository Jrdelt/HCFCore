# Native Factions

Vertex owns factions directly. It does not require, load, or call
FactionsUUID. Factions, members, roles, land, relations, permissions, homes,
warps, money, player map/chat preferences, and system-faction claims are kept
in the configured Vertex database.

## Core commands

The core `/f` command list is role-aware. Most actions are allowed or blocked
through `/f permissions`; only command-level visibility is shown below.

| Command | Permission / Audience | Purpose |
|---|---|---|
| `/f create <tag>` | Follows faction-state rules; open command surface is always available | Create a faction. Tags follow `factions.tag-pattern`. |
| `/f invite <player>` / `/f join <faction>` | Role-/state-gated (see `/f permissions` and invite flow) | Invite and join factions. Invites expire after the configured time. |
| `/f leave`, `/f kick <player>`, `/f promote <player>`, `/f demote <player>`, `/f leader <player>` | Role-gated (`Leader` and `Admin` controls most role changes) | Manage membership and roles. Leaders must transfer leadership before leaving when `prevent-leader-leave` is enabled. |
| `/f claim [radius]`, `/f unclaim`, `/f unclaimall`, `/f autoclaim` | Open to members by claim-role checks in `/f permissions` | Manage chunk claims. Radius is bounded by configuration. |
| `/f who [faction or player]`, `/f list` | Open to all players | Inspect factions and members. |
| `/f ally`, `/f neutral`, `/f enemy <faction>` | Relation actions follow faction role permission model | Manage the three supported relations. Ally and Enemy-to-Neutral require request/acceptance; Enemy is unilateral and either faction can unally to Neutral. |
| `/f open`, `/f close`, `/f description <text>`, `/f rename <tag>` | Role-gated (`Member`-level settings depend on your faction role) | Manage faction settings. |
| `/f home`, `/f sethome`, `/f warp [name]`, `/f setwarp <name>`, `/f delwarp <name>` | Open to faction members, role checks still apply | Native faction homes and warps. |
| `/f money [deposit\|withdraw <amount>]` | Role-gated by `/f permissions` and same bank rules as `/f bank` | View or use the same durable Vertex faction-bank money balance as `/f bank`, through Vault. |
| `/f chat [faction\|ally\|public]` or `/f c [f\|a\|p]`, `/f map [on\|off]` | Open to all players with faction settings | Toggle faction chat, ally chat, and map display. Both preferences persist per player; ally chat includes your faction and mutually allied factions. Map width/height are configured independently, and a green crosshair marks the player's current chunk. Hover a claimed chunk to see its faction and whether it is a Base Claim or Raid Claim; Raid Claims also show their remaining lifetime. |

`/f` and `/f help` show a paginated clickable help menu. The previous and
next controls run `/f help <page>` and the command list is localized through
`en_us.yml`.

`/f` also hosts the Vertex feature branches: `bank`, `upgrades`, `perms`,
`rally`, `shield`, `top`, and `baseclaim`. Their existing commands and GUIs
remain available under the native root.

## Claims and protection

Vertex validates build/break, container access, doors/switches, bucket fill and
empty, and direct/projectile player PvP on the server. Factionless players
cannot build or use protected blocks in claims. Members need the relevant rank
action; allies can build only when
`factions.protection.allies-can-build` is enabled. Friendly-fire and ally-PvP
are controlled by `factions.pvp`.

Claims are stored by world and chunk. Base Claims, Raid Claims, Chunk
Collectors, Spawners, Sand Bots, Chunk Busters, Source Buckets, faction
upgrades, shields, and leaderboards all read the same native claim ownership.
`factions.claims` can require connected land, price each claimed chunk in
power, and enable power-ratio overclaims. All are off by default except the
configured ratio, which is used only if overclaims are enabled.

## Roles and permissions

The six roles are Recruit, Member, Mod, Admin, Co-Leader, and Leader. Leaders
always have access. `/f perms` opens the persistent permission editor for the
other role buckets and the dedicated Allies page. Leaders, Co-Leaders, and
Admins can edit only the role scopes allowed by the rank hierarchy. It controls
core actions such as building, containers, doors/switches,
territory, invitations, relationships, homes/warps, upgrades, rallies,
spawners, collectors, faction banks, and Chunk Busters. Defaults are in
`factions.permissions.defaults`; a leader’s changes are stored in SQL and
survive config reloads/restarts.

## Power and system factions

Faction power starts and caps per member. Death loss and online regeneration
are configurable in `factions.power`. Power is native Vertex state and is
shown by the normal faction placeholders/`/f who`; it is separate from F Top.

Staff with `vertex.factions.admin` can claim the current chunk for the
configured SafeZone or WarZone with `/f claim <Safezone|Warzone>`. Adding a
radius creates a square: radius `1` claims `3x3` chunks, radius `2` claims
`5x5`, and so on. The area is validated and saved atomically, then its live
claim updates are applied in bounded tick batches. `/f admin unclaim` removes
the current claim. System-faction tags, no-PvP tags, and the per-tick batch
budget are configurable under `factions.system-claims`.

## Storage and migration

All native faction tables use the configured Vertex database backend. Core
membership, leadership transfer, claim changes, and faction deletion are
committed before cache changes or claim-bound item listeners run. `/f money`
uses the existing durable `faction_banks` balance; the faction identity table
does not keep a second money balance.
Existing FactionsUUID data is intentionally not auto-imported: its storage
layout and server-specific custom fields vary, and an unsafe import could
misassign land or balances. Back up its data before removing that plugin. A
one-time importer should only be run after its exact database/files and the
desired mapping are reviewed.
