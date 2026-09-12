# Physical Entry Portals

Physical portals are configured from the destination they lead to. There is
no standalone `/portal` or selector-wand command.

## Player behavior

Walking into a saved portal starts its destination's server-controlled route.
The player travels through the recorded points in order and receives Slow
Falling when the final point is reached. Left- or right-clicking during the
flight drops the player early with Slow Falling. Combat-tagged players cannot
enter, and a short server-side cooldown prevents repeated starts.

## Staff setup

All setup uses `vertex.portals.admin` for portal volumes and the destination
admin permission for routes.

| Destination | Portal volume | Ordered route |
|---|---|---|
| Haven | `/haven portal create <portal>` | `/haven spawnpoints create <region> <route>` |
| Riftlands | `/riftlands portal create <portal>` | `/riftlands spawnpoints create <region> <route>` |
| Mine | `/mines portal create <mine> <portal>` | `/mines spawnpoints create <mine> <route> [speed]` |

The create command automatically gives a tagged Blaze Rod. For a portal
volume, left-click the first corner, right-click the second, then sneak and
air-click to save. For a route, left-click every waypoint in order,
right-click to remove the latest point, then sneak and air-click to save.

Every waypoint and every half-block of the line between waypoints is validated
inside its destination. Put points at each turn or height change you want
players to follow. A route may contain one point for a simple drop-in, but use
two or more points for a guided path.

Useful management commands:

```text
/haven portal list
/haven portal delete <portal>
/haven spawnpoints list|preview|delete <route>

/riftlands portal list
/riftlands portal delete <portal>
/riftlands spawnpoints list|preview|delete <route>

/mines portal list
/mines portal delete <portal>
/mines spawnpoints list|preview|delete <route>
```

Player entry through a finished portal requires `vertex.portals.use`, which is
granted to players by default.

## Configuration and persistence

`config.yml` contains two global defaults:

- `portals.flight.speed` — default route speed in blocks per second. Mine
  routes can override it as their optional final create argument.
- `portals.activation-cooldown-seconds` — prevents repeated portal triggers.

Portal volumes and route points are durable SQL records (`entry_portals` and
`entry_portal_routes`). A restart ends an in-progress flight safely at the
last server-confirmed location; it never creates an item or economy change.
