# Physical Entry Portals

`/portal` lets staff build a visible portal anywhere and connect it to a
safe, server-controlled flight into Stonewake, Bloodvein, any other placed
mine, Haven, or Riftlands. A player cannot steer the flight or use a
client-side teleport to choose a different destination.

## Player behavior

Walk into a configured portal volume to enter it. The player is carried along
one valid route and is given Slow Falling when the route ends. Left- or
right-clicking during the flight releases the player early with the same Slow
Falling effect, which ends when they reach the ground.

Players who are combat-tagged cannot enter. Riftlands entry creates the same
unsecured loot session as `/riftlands`; Haven remains PvP-safe under its
normal zone rules. Portal activation has a short server-side cooldown to
avoid repeated route starts from movement packets.

## Staff setup

All setup uses `vertex.portals.admin`.

| Command | Permission | What it does |
|---|---|---|
| `/portal wand` | `vertex.portals.admin` | Gives a portal selector to mark first and second corners (or route points). |
| `/portal create <portal-id> <haven\|riftlands\|mine-id>` | `vertex.portals.admin` | Creates a portal volume and starts a destination selection flow. |
| `/haven portal create <portal-id>` / `/riftlands portal create <portal-id>` | `vertex.portals.admin` | Creates a zone-specific portal directly from the current location. |
| `/portal list` | `vertex.portals.admin` | Lists configured portals and their current status. |
| `/portal delete <portal-id>` | `vertex.portals.admin` | Removes a portal and its route bindings. |
| `/portal route create <haven\|riftlands\|mine-id> <route-id> [speed]` | `vertex.portals.admin` | Creates a dedicated portal route entry for a destination. |
| `/portal route list [target]` | `vertex.portals.admin` | Shows routes globally or for a target portal/mine. |
| `/portal route preview <route-id>` | `vertex.portals.admin` | Simulates the exact route path before publishing. |
| `/portal route delete <route-id>` | `vertex.portals.admin` | Deletes a single configured route. |

Player entry through a finished portal requires `vertex.portals.use` (defaulting to everyone).

The zone-specific forms preselect Haven/Riftlands. `/portal create`, either
zone-specific form, or `/portal route create` gives the **Portal Selector**
(Blaze Rod). For a portal volume, left-click its first block and right-click
its second block. For a route, left-click each route point; right-click
removes the most recently added point. Sneak and click the air to save either
selection.

Routes need at least two points. Every point and every 0.5-block segment is
validated inside the selected destination before saving. This prevents a
Haven/Riftlands route from crossing an unsafe boundary and prevents a mine
route from travelling through ordinary terrain. Use `/portal route preview`
to test the exact flight before opening the portal to players.

The destination must already be placed: create Haven/Riftlands regions with
their zone commands, and place a mine with `/mines wand <mine>`, first.

Haven and Riftlands physical portals also use a valid route created with
`/haven route create ...` or `/riftlands route create ...` when no dedicated
`/portal route create ...` route exists for that destination. This keeps the
normal zone entry route and a physical portal from requiring duplicate points.
Mine portals require their own `/portal route create` route.

The Portal Selector is a PDC-tagged Blaze Rod. Left-click a block for the
first portal corner and right-click a block for the second. For a route,
left-click each point and right-click to remove the newest point. Hold Shift
and click either air action to save. The selector listener intentionally
accepts already-cancelled air events so Essentials or another item listener
cannot prevent the save action.

## Configuration and persistence

`config.yml` has two global safe defaults:

- `portals.flight.speed` — default route speed in blocks per second; a route
  can override it when created.
- `portals.activation-cooldown-seconds` — guards a source portal against
  repeated starts and repeated error messages.

Portal volumes and route point data are stored in the configured Vertex SQL
backend (`entry_portals` and `entry_portal_routes`), so they survive normal
restarts. A flight in progress ends safely on shutdown; the player remains at
the last server-confirmed location rather than receiving any item or economy
transaction.
