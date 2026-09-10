# Haven & Riftlands

Vertex supplies two named, database-backed farming-zone types:

- **Haven** is PvE only. Player-versus-player damage is cancelled, including projectile damage during guided entry.
- **Riftlands** is PvPvE. Normal Vertex combat tags apply, including during guided entry. Riftlands rewards begin an unsecured session until the player successfully exits.

## Player commands

| Command | Purpose |
| --- | --- |
| `/haven` | Open Haven’s confirmation screen. |
| `/riftlands` | Open Riftlands’ confirmation screen. |
| `/zones` | View both progression tracks, event standings, and active booster details. |
| `/haven lootpool` | View Haven rewards. |
| `/riftlands lootpool` | View Riftlands rewards. |

Entry uses an Emerald Block confirmation followed by a configurable stationary countdown. Movement, damage, changing world, teleportation, disconnecting, or becoming combat tagged cancels it. A valid route then carries the player server-side; clicking either mouse button, reaching the end, or being hit in Riftlands releases them with Slow Falling until ground contact.

`/spawn` channels for 10 seconds in Haven and 20 seconds in Riftlands by default. Combat cancels/blocks the exit channel. A successful Riftlands exit secures that session’s tagged loot.

## Riftlands session loot and Ticket

Zone rewards are directed to the equipped Backpack without applying its ordinary drop multiplier again. Riftlands rewards carry an exact session marker; they cannot be placed in another container or dropped to avoid the death rule. On a Riftlands death, only those marked rewards are dropped. A player killer receives the existing Vertex loot protection; PvE/environment deaths leave the session loot public immediately.

The non-stackable **Riftlands Ticket** is a PDC-identified item. Right-click it while combat-tagged inside Riftlands to search the current arena for safe ground far from all legal hostiles and away from entry routes. The ticket is consumed and combat cleared only after a successful teleport.

## Staff setup

All setup requires `vertex.zones.admin`.

```text
/haven region create <name>
/riftlands region create <name>
/haven region list|delete <name>|wand
/riftlands region list|delete <name>|wand
/haven route create <region> <name>
/riftlands route create <region> <name>
/haven route list|preview|delete <name>
/riftlands route list|preview|delete <name>
/haven lootpool
/riftlands lootpool
/riftlands ticket give <player> <amount>
/haven admin event start|stop|seasonreset|inspect <player>
```

The shared Zone Selector is a Blaze Rod: left-click sets the first corner (or adds a route point), right-click sets the second corner (or removes the newest route point), and sneak-air click saves. Regions may never overlap. Every route point and every interpolated 0.5-block segment must remain in its selected region.

The staff loot-pool GUI preserves real items and their metadata. Right-click an item changes its base chance by +1%; Shift-right-click changes it by -1%; closing saves atomically as a replacement pool. Players receive a read-only version.

## Configuration and persistence

`haven.yml` and `riftlands.yml` hold entry presentation, channels, death cooldowns, routes speed, local mob budgets, mob definitions, milestones, loot rules, and Riftlands Ticket settings. Definitions (regions/routes), exact loot stacks, player progression/cooldowns/sessions, event scores, cycle anchor, and offline flight landings are stored in the configured Vertex SQL backend.

Mob Drop Amplification grants whole independent extra loot-pool rolls; it never changes a listed item’s base chance. It combines the current zone milestone, current Top-3 event boost, equipped Backpack bonus, and future compatible shared sources. Items strictly below the configured rare threshold cannot be awarded twice from one mob death.

The combined Mob Kill Event uses an epoch-stable persisted cycle (default 120 minutes) and active window (default five minutes), so restarts neither extend boosters nor restart an event. Haven kills score 1.0 point and Riftlands kills score 1.5 by default; ties resolve by the earliest time at which a player reached that score.

## Placeholders

Use `%vertex_zone%`, `%vertex_haven_kills%`, `%vertex_riftlands_kills%`, `%vertex_haven_progress%`, `%vertex_riftlands_progress%`, `%vertex_zone_amplification%`, `%vertex_zone_event_active%`, `%vertex_zone_event_score%`, `%vertex_zone_event_rank%`, `%vertex_zone_event_remaining%`, `%vertex_zone_event_top_1_name%` through `_3_name`, `%vertex_zone_event_top_1_score%` through `_3_score`, `%vertex_zone_winner_boost%`, `%vertex_zone_backpack_amplification%`, `%vertex_haven_death_cooldown%`, and `%vertex_riftlands_death_cooldown%`.
