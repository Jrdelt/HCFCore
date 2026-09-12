# Staff Tools

All toggles here are **session-scoped** — nothing persists across a
rejoin, the same as combat tags — and each is gated behind its own
`vertex.staff.*` permission.

## Vanish, staff chat, staff-build, and combined staff mode

| Command | Permission | Behavior |
|---|---|---|
| `/vanish` | `vertex.staff.vanish` | Hides you from anyone without this permission. Applies immediately to every online viewer and to anyone who joins afterward. Your quit message is suppressed while vanished. Mobs stop targeting you instantly (any mid-chase mob has its target cleared, and nothing can pick you as a target while vanished), and you can't deal damage to anything — mobs or players, melee or projectile — while vanished. Both rules exist for the same reason: a mob swinging at empty air, or a player getting hit by nothing, gives away that someone invisible is there. |
| `/staffchat` | `vertex.staff.staffchat` | Toggles routing *all* your normal chat to a staff-only channel (visible to the same permission) instead of public chat, until toggled off. |
| `/staffbuild` | `vertex.staff.staffbuild` | Bypasses Vertex claim protection entirely — block break/place, containers/doors, buckets, item frames/paintings, and entity interaction all work in any claim while on. |
| `/staff` | `vertex.staff.mode` | Toggles vanish + staff-build together as one switch, and also grants **godmode** and **flight** for the duration. Treats everything as "on" only when vanish and staff-build are *both* already on — so if you'd turned one off individually, `/staff` turns everything back on rather than finishing the job of turning it off. |

## Freeze

`/freeze <player>` (`vertex.staff.freeze`) locks a player in place while
staff investigate, without the tip-off a ban gives a suspected cheater.
While frozen, a player cannot:

- Move (they can still look around)
- Break, place, or interact with blocks
- Deal or take any damage
- Drop items or click their own inventory
- Run any command
- Launch a projectile (pearl, arrow, snowball, egg, fishing hook)
- Eat, drink, or use a bucket
- Pick up items or swap main/off hand

The only thing left is logging out — and they're warned in chat not to.
**Disconnecting while frozen bans them for 3 hours** (a ban, not a kick —
`/freeze` itself never kicks or bans; only leaving while frozen does),
and every other online staff member with the permission is alerted.
`/freeze` again unfreezes. Projectiles are checked twice (once on the
interact event that starts the throw, again on the projectile itself)
since cancelling the interact alone doesn't reliably stop every launch
path.

## Invsee / Endersee

| Command | Permission | Behavior |
|---|---|---|
| `/endersee <player>` | `vertex.staff.endersee` | Opens the target's **live** ender chest — a real two-way Bukkit inventory view, not a custom GUI. An edit on either side shows up for both immediately. |
| `/invsee <player>` | `vertex.staff.invsee` | Opens a custom GUI showing the target's hotbar, main storage, **and their equipped armor and offhand** — there's no vanilla container type that exposes someone else's equipment. Unlike `/endersee`, this is not a live shared reference: edits sync back to the target one tick after each click, and armor slots reject anything that isn't actually that armor piece. A change the target makes to their own gear while the menu is open won't show up until it's reopened. |

Block breaking in WarZone/SafeZone claims is left entirely to
Vertex's native protection — staff-build is the explicit bypass.

## Death rollback (`/rollback`)

`/rollback <player>` (`vertex.staff.rollback`) opens a death-history GUI
showing that player's last 20 deaths, each with timestamp, cause, and
killer (or "Environment").

- **Left-click** a death to restore every item — inventory contents,
  armor, and offhand — to the staff member's own inventory (overflow
  drops to the ground).
- **Right-click** a death to view its exact contents read-only, with a
  **Back** button to return to the list.

Every player's death history persists to the selected SQLite or MySQL
backend automatically; older entries beyond the last 20 are purged, so the database footprint per
player stays constant. A single corrupted row (e.g. a truncated blob)
is skipped and logged rather than hiding every other death behind it, up
to a protective 500-row scan limit while searching for valid history.

## Server administration

| Command | Permission | Behavior |
|---|---|---|
| `/vertex reload` | `vertex.admin` | Reloads config, messages, kits, abilities, and tags. See [Installation](installation.md#reload-vs-restart) for when to use a full restart instead. |
| `/vertex clearmobstacks` | `vertex.admin` | Removes every currently-tracked stacked mob across all loaded chunks — see [Spawners & Mob Stacking](spawners-and-collectors.md#mob-stacking-spawnersyml--mob-stacking). |
| `/vertex storage [local\|mysql] [confirm]` | `vertex.admin` | Shows the active storage backend, or copies all data to the other one and switches to it on the next restart — see [Installation](installation.md#switching-backends-in-game). |
| `/reboot [minutes]` / `/reboot cancel` | `vertex.reboot.start` | Starts or cancels a shutdown countdown — see [Configuration](configuration.md#reboot-scheduling). |
| `/nextreboot` | *(open to all)* | Shows the currently scheduled countdown, if any. |
