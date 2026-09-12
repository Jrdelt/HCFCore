# Haven & Riftlands

Vertex supplies two named, database-backed farming-zone types:

- **Haven** is PvE only. Player-versus-player damage is cancelled, including projectile damage during guided entry.
- **Riftlands** is PvPvE. Normal Vertex combat tags apply, including during guided entry. Riftlands rewards begin an unsecured session until the player successfully exits.

## Player commands

| Command | Permission | Purpose |
|---|---|---|
| `/haven` | `vertex.zones.use` | Open the Haven hub with entry, live activity, and its loot pool. |
| `/riftlands` | `vertex.zones.use` | Open the Riftlands hub with entry, live activity, and its loot pool. |
| `/haven lootpool` | `vertex.zones.use` | View Haven rewards. |
| `/riftlands lootpool` | `vertex.zones.use` | View Riftlands rewards. |

Each zone hub shows the current number of players physically inside its
regions, the number of live Vertex zone mobs, an Enter button, and a Loot Pool
button. The activity item can be clicked to refresh the counts. The read-only,
paginated loot view preserves every custom item and displays its exact base
drop percentage; Back returns to the corresponding zone hub. Admins still use
the explicit `lootpool` subcommand to open the editable version.

Entry uses an Emerald Block confirmation followed by a configurable stationary countdown. Entry permission, player-data readiness, cooldown, combat status, and a valid enabled route are rechecked only when Enter is clicked. Movement, damage, changing world, teleportation, disconnecting, or becoming combat tagged cancels it. A valid route then carries the player server-side; clicking either mouse button, reaching the end, or being hit in Riftlands releases them with Slow Falling until ground contact.

`/spawn` channels for 10 seconds in Haven and 20 seconds in Riftlands by default. Combat cancels/blocks the exit channel. A successful Riftlands exit secures that session’s tagged loot. A death in Haven, Riftlands, a mine, Wilderness, or WarZone bypasses this channel and respawns at the configured shared Spawn.

## Riftlands session loot and Ticket

Zone rewards are directed to the equipped Backpack without applying its ordinary drop multiplier again. Riftlands rewards carry an exact session marker; they cannot be placed in another container or dropped to avoid the death rule. On a Riftlands death, only those marked rewards are dropped. A player killer receives the existing Vertex loot protection; PvE/environment deaths leave the session loot public immediately.

The non-stackable **Riftlands Ticket** is a PDC-identified item. Right-click it while combat-tagged inside Riftlands to search the current arena for safe ground far from all legal hostiles and away from entry routes. The ticket is consumed and combat cleared only after a successful teleport.

## Staff setup

All setup requires `vertex.zones.admin`.

| Command | Permission | What it does |
|---|---|---|
| `/haven create <name>` / `/riftlands create <name>` | `vertex.zones.admin` | Define the active region and metadata for each zone type. |
| `/haven list` / `/riftlands list` | `vertex.zones.admin` | Shows the currently defined zones for review/editing. |
| `/haven portal create\|delete\|list` / `/riftlands portal create\|delete\|list` | `vertex.portals.admin` | Creates or manages a physical portal selection for that zone type. |
| `/haven spawnpoints create [region] <name>` / `/riftlands spawnpoints create [region] <name>` | `vertex.zones.admin` | Builds an explicit ordered entry route. Omit `region` only while standing in a matching region or when exactly one matching region exists. |
| `/haven spawnpoints list\|preview\|delete <name>` / `/riftlands spawnpoints list\|preview\|delete <name>` | `vertex.zones.admin` | Manages or tests existing zone routes. |
| `/haven lootpool` / `/riftlands lootpool` | `vertex.zones.admin` | Opens the editable loot-pool editor version. |
| `/haven koth\|outpost create\|cancel\|delete\|list` / `/riftlands koth\|outpost create\|cancel\|delete\|list` | `vertex.zones.admin` | Creates and manages the zone-local KOTH and Outpost control points. |
| `/haven admin event start\|stop` | `vertex.zones.admin` | Starts or stops the shared Mob Kill Event (configured cycle; default 120 minutes). |
| `/haven admin inspect <player>` | `vertex.zones.admin` | Inspects a player’s zone progression and current amplification. |
| `/haven admin clear-mobs [all]` / `/riftlands admin clear-mobs [all]` | `vertex.zones.admin` | Removes tracked mobs from that zone, or from both zones with `all`. |

The shared Zone Selector is a Blaze Rod: left-click sets the first corner (or adds a route point), right-click sets the second corner (or removes the newest route point), and sneak-air click saves. Portal creation gives its separately tagged Portal Selector automatically, so a region wand cannot accidentally save a portal selection. Regions may never overlap. Every route point and every interpolated 0.5-block segment must remain in its selected region. Ticket issuing and the old season-reset command are not part of the command tree.

`/haven spawnpoints ...` and `/riftlands spawnpoints ...` create routes used by the zone
entry GUI and physical portal entry, so admins configure each flight once.
Stonewake and Bloodvein use their own `/mines spawnpoints` routes. The Portal Selector save action is Shift plus
an air click; it accepts cancelled air events so the save is not swallowed by
another item listener.

The staff loot-pool GUI is always a six-row double chest. Its top 45 slots are
a real in-game editor: admins can place, drag, shift-click, rearrange, or remove
actual item stacks there. Right-click an item changes its base chance by +1%;
Shift-right-click changes it by -1%; closing saves the 45-slot pool atomically.
The editor-only chance lore is removed before rewards are stored, so custom
names, lore, enchantments, and item data stay clean. Players receive a
read-only version.

## Configuration and persistence

`haven.yml` and `riftlands.yml` hold entry presentation, channels, death cooldowns, routes speed, local mob budgets, mob definitions, milestones, loot rules, and Riftlands Ticket settings. Definitions (regions/routes), exact loot stacks, player progression/cooldowns/sessions, event scores, cycle anchor, and offline flight landings are stored in the configured Vertex SQL backend.

Zone mob spawning is configured per zone under `mob-spawning`. The current
defaults use a 5–30 block ring, a distance bias of `2.5` so locations are
weighted toward the player, and a 12-mob fill batch. Haven and Riftlands have
separate local caps and per-player additions; changing these values affects
the local population without scanning the whole world.

Mob Drop Amplification grants whole independent extra loot-pool rolls; it never changes a listed item’s base chance. It combines the current zone milestone, current Top-3 event boost, equipped Backpack bonus, and future compatible shared sources. Items strictly below the configured rare threshold cannot be awarded twice from one mob death.

The combined Mob Kill Event uses an epoch-stable persisted cycle (default 120 minutes) and active window (default five minutes), so restarts neither extend boosters nor restart an event. Haven kills score 1.0 point and Riftlands kills score 1.5 by default; ties resolve by the earliest time at which a player reached that score.

Both zones now use Haven's `kill-event.scoring` values. With shared-MySQL shards,
each recorded kill has a durable replay receipt and adds to the global total;
changing shards cannot overwrite the event score with a smaller local value.
Winners are finalized once from SQL scores, not separately from each shard's
leaderboard. Applied winner boosts are read from that immutable result, so a
stale player save cannot revoke them. Exact score/time ties use player UUID.

The scoreboard and boosts refresh asynchronously every
`kill-event.shared-refresh-seconds` (default 2, bounded 1–60). Finalization waits
`kill-event.settlement-delay-seconds` after the deadline (default 5, bounded 1–60)
for queued writes. Late writes are rejected and logged; a process killed before
an asynchronous score reaches SQL can still lose that uncommitted score. Event
start/stop is shared, and stopping preserves the current event's scores. Keep
event configuration identical across shards. See the deployment limitations and
maintenance rollout in [network persistence](network-shards.md#persistence-scope-and-current-limits).

## Placeholders

Use `%vertex_zone%`, `%vertex_haven_kills%`, `%vertex_riftlands_kills%`, `%vertex_haven_progress%`, `%vertex_riftlands_progress%`, `%vertex_zone_amplification%`, `%vertex_zone_event_active%`, `%vertex_zone_event_score%`, `%vertex_zone_event_rank%`, `%vertex_zone_event_remaining%`, `%vertex_zone_event_top_1_name%` through `_3_name`, `%vertex_zone_event_top_1_score%` through `_3_score`, `%vertex_zone_winner_boost%`, `%vertex_zone_backpack_amplification%`, `%vertex_haven_death_cooldown%`, and `%vertex_riftlands_death_cooldown%`.
