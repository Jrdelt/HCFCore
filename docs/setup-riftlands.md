# Riftlands Owner Setup

This guide sets up the PvPvE Riftlands arena, its mob route, loot pool, and
optional physical portal. It assumes the owner or administrator has
`vertex.zones.admin` and `vertex.portals.admin`.

## 1. Prepare the world

Create or import the Riftlands world with your normal world-management tool.
Vertex controls the selected region but does not create the world.

## 2. Create the Riftlands region

Run:

```text
/riftlands create riftlands_main
```

Use the Zone Selector:

1. Left-click the first corner.
2. Right-click the opposite corner.
3. Hold Shift and click air to save.

Both corners must be in the same world. Verify the saved region with:

```text
/riftlands list
```

The short alias `/rift` can be used for the Riftlands command tree.

## 3. Create the flight route

Tie the route to the region created above:

```text
/riftlands spawnpoints create riftlands_main rift_arrival
```

Use the Zone Selector to click waypoints in flight order:

- Left-click adds a waypoint.
- Right-click removes the newest waypoint.
- Shift + air-click saves the route.

One waypoint is enough for a simple drop-in. Click turns and height changes,
not every block. All points and the interpolated path must remain inside
`riftlands_main`.

Preview it with:

```text
/riftlands spawnpoints preview rift_arrival
```

## 4. Create an optional physical portal

Run:

```text
/riftlands portal create rift_gate
```

With the Portal Selector, left-click the first corner, right-click the second
corner, then Shift-click air to save.

The portal uses `rift_arrival`. Verify the setup:

```text
/riftlands portal list
/riftlands spawnpoints list
/riftlands spawnpoints preview rift_arrival
```

## 5. Configure the loot pool

Open the editable editor:

```text
/riftlands lootpool
```

Place reward items in the top 45 slots. Right-click changes the item chance by
1%; Shift-right-click lowers it by 1%. Close the GUI to save.

Riftlands rewards are tracked as unsecured session loot until the player exits
successfully. Review the ticket and reward settings in `riftlands.yml`.

## 6. Configure mobs and PvP settings

Edit the live file `plugins/Vertex/riftlands.yml`. Important sections include
`mob-spawning`, `mobs`, `loot-pool`, `riftlands-ticket`, `entry`, `exit`, and
`routes`.

Apply changes with:

```text
/vertex reload
```

## 7. Test as a player

1. Give a test player `vertex.zones.use` and `vertex.portals.use`.
2. Enter through `/riftlands` and confirm the countdown.
3. Confirm Riftlands PvP and combat tagging work.
4. Walk into the physical portal and verify the route starts.
5. Click during flight and confirm the player drops with Slow Falling.
6. Kill a configured mob and confirm rewards go to the intended inventory path.
7. Test a death and a successful exit to verify session-loot behavior.

## Common problems

- `route-region-required`: use `/riftlands spawnpoints create riftlands_main <route-name>`.
- Route will not save: add at least one point and keep every point/path
  segment inside the region.
- Portal has no route: check `/riftlands portal list` and `/riftlands spawnpoints list`.
- A player cannot enter: check combat status, zone permissions, and whether
  the player's Vertex data has finished loading.
