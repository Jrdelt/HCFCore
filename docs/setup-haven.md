# Haven Owner Setup

This is the complete first-time setup for Haven. It assumes the owner or
administrator has `vertex.zones.admin` and `vertex.portals.admin`.

## 1. Prepare the world

Create or import the Haven world with your normal world-management tool. Vertex
does not create worlds.

Make sure the world is loaded before selecting the region. Use one consistent
region name such as `haven_main`.

## 2. Create the Haven region

Run:

```text
/haven create haven_main
```

Vertex gives you a Zone Selector.

1. Go to the first corner of the entire playable Haven area.
2. Left-click the block.
3. Go to the opposite corner.
4. Right-click the block.
5. Hold Shift and left- or right-click air to save.

The region is stored in the Vertex database. Use `/haven list` to confirm it
exists. Both corners must be in the same world.

## 3. Create the player flight route

The route is tied to the region name from step 2:

```text
/haven route create haven_main haven_arrival
```

The command gives another Zone Selector. Click route waypoints in the order
players should fly:

- Left-click a block to add a waypoint.
- Right-click to remove the newest waypoint.
- Hold Shift and click air to validate and save.

Use at least two waypoints. You do not need to click every block; Vertex
interpolates the path between waypoints. Every waypoint and path segment must
remain inside `haven_main`.

Preview it with:

```text
/haven route preview haven_arrival
```

## 4. Create an optional physical portal

To create the portal players walk into:

```text
/haven portal create haven_gate
```

Use the Portal Selector it gives you:

1. Left-click the first corner of the portal volume.
2. Right-click the opposite corner.
3. Hold Shift and click air to save.

The portal uses the Haven route from step 3 automatically. For a separate
route only for this portal:

```text
/portal route create haven haven_portal_route
```

Click its waypoints inside Haven and Shift-click air to save.

Check the saved portal and routes:

```text
/portal list
/portal route list haven
/portal route preview haven_portal_route
```

## 5. Configure the loot pool

Open the editable loot pool:

```text
/haven lootpool
```

Place reward items in the top 45 slots. Right-click increases an item's chance
by 1%; Shift-right-click decreases it by 1%. Close the GUI to save.

## 6. Configure mobs and economy settings

Edit the live file `plugins/Vertex/haven.yml`. Important sections include
`mob-spawning`, `mobs`, `loot-pool`, `entry`, `exit`, and `routes`.

Apply changes with:

```text
/vertex reload
```

## 7. Test as a player

1. Give a test player `vertex.zones.use` and `vertex.portals.use`.
2. Run `/haven` and confirm the GUI shows the loot pool and activity counts.
3. Enter through the GUI and confirm movement cancels the countdown.
4. Walk into the physical portal and confirm the flight begins.
5. Confirm the player reaches the last waypoint and receives Slow Falling.
6. Confirm Haven PvP rules and mob spawning.

## Common problems

- `no valid entry route`: the route was not saved, has fewer than two points,
  or points were outside the region.
- Portal says unavailable: verify `/portal list`, the player permission, and
  that a portal or zone route exists.
- Selector does nothing: use the main hand, click actual blocks for points,
  and hold Shift while clicking air to save.
- Mob count is zero: confirm players are inside the selected region and the
  live `haven.yml` mob definitions are enabled.
