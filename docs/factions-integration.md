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
| `/f leave`, `/f kick <player>`, `/f promote <player>`, `/f demote <player>`, `/f leader <player>` | Role-gated (`Leader` and `Admin` controls most role changes) | Manage membership and roles. Leaders must transfer leadership before leaving when `prevent-leader-leave` is enabled (the default); otherwise leadership passes automatically. |
| `/f claim [radius]`, `/f unclaim`, `/f unclaimall`, `/f autoclaim` | Open to members by claim-role checks in `/f permissions` | Manage chunk claims. Radius is bounded by configuration. |
| `/f who [faction or player]`, `/f list` | Open to all players | Inspect factions and members. |
| `/f ally`, `/f neutral`, `/f enemy <faction>` | Relation actions follow faction role permission model | Manage the three supported relations. Ally and Enemy-to-Neutral require request/acceptance; Enemy is unilateral and either faction can unally to Neutral. |
| `/f open`, `/f close`, `/f description <text>`, `/f rename <tag>` | Role-gated (`Member`-level settings depend on your faction role) | Manage faction settings. |
| `/f home`, `/f sethome`, `/f warp [name]`, `/f setwarp <name>`, `/f delwarp <name>` | Open to faction members, role checks still apply | Native faction homes and warps. |
| `/f bank <deposit\|withdraw> <amount> <money\|experience\|tnt>` | Role-gated by `/f permissions` | The only typed faction-bank transaction path; the GUI is also available through `/f bank`. |
| `/tntunfill [radius] [bank]` | `vertex.tntunfill.use` plus the faction's **Use TNT Fill** role action | Returns dispenser TNT in your own claims directly to the faction TNT bank. With no radius, it uses the configured default (maximum by default); `bank` is accepted only as an optional explicit destination, and inventory is never accepted. |
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
action; allies can place or break blocks only when
`factions.protection.allies-can-build` is enabled and the claim's faction also
allows it for allies in `/f permissions`. When the server-wide switch is off,
the ally Place Blocks / Break Blocks rows in `/f permissions` still show
their saved state but turn gray with a hint that they're currently inactive.
Friendly-fire and ally-PvP are controlled by `factions.pvp`.

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
just the current chunk; `/f admin unclaimall <Safezone|Warzone>` releases
every chunk that system faction owns in a single DB statement (not a
per-chunk loop), for redoing a SafeZone/WarZone claim from scratch.
System-faction tags, no-PvP tags, and the per-tick batch budget are
configurable under `factions.system-claims`.

A combat-tagged player can neither walk/teleport into a configured SafeZone
nor be nudged there by the border-push anti-exploit below -- both rules are
gated on combat, so an untagged player near a SafeZone edge is never
affected by either.

## Storage and migration

All native faction tables use the configured Vertex database backend. Core
membership, leadership transfer, claim changes, and faction deletion are
committed before cache changes or claim-bound item listeners run. The faction
identity table does not keep a second money balance: all money bank actions
use the durable `faction_banks` ledger through `/f bank`.

### TNT withdrawal delivery

A TNT withdrawal commits the bank debit and a player-owned inbox entitlement
in the same database transaction. The inbox supplies the items when inventory
space and the current server-transfer state permit. Disconnecting or filling
the inventory does not require a capacity-sensitive faction-bank refund, and
items are not dropped on the ground. A failed deposit returns TNT directly only
to the same ready player session; otherwise it attempts durable inbox admission.
Physical deposit checkpoints and the shared inventory acknowledgement crash
window remain open in [issues.md](../issues.md); this is not full crash-proofing.

### TNT dispenser unfill

`/tntunfill [radius]` finds only loaded dispensers in the caller's own faction
claims. It removes only as much TNT as the current TNT Bank upgrade can still
hold, then records the bank credit. While the durable bank write is pending,
the affected dispenser inventories, dispensing, hopper transfers, breakage,
movement, and explosions are temporarily locked. A failed write restores the
same TNT to the same locked dispensers; the command never uses a player
inventory as a destination.
Existing FactionsUUID data is intentionally not auto-imported: its storage
layout and server-specific custom fields vary, and an unsafe import could
misassign land or balances. Back up its data before removing that plugin. A
one-time importer should only be run after its exact database/files and the
desired mapping are reviewed.
