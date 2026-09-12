# Mine Owner Setup

This guide sets up the configured mining worlds, ore generation, mine KOTHs,
and optional physical entry portals. The shipped mine IDs are `stonewake` and
`bloodvein`.

Mine setup requires `vertex.mines.admin` for mine selection and
`vertex.portals.admin` for physical portals.

## 1. Prepare the mine worlds

Create or import the worlds with your normal world-management tool. Vertex
does not create or import worlds.

The mine definitions, ore tables, drop materials, PvP mode, and regeneration
settings are in `plugins/Vertex/mines.yml`.

## 2. Select the Stonewake or Bloodvein region

For Stonewake:

```text
/mines create stonewake
```

For Bloodvein:

```text
/mines create bloodvein
```

With the Mine Selector:

1. Left-click the first corner of the mine.
2. Right-click the opposite corner.
3. Hold Shift and click air to save.

The world is recorded from the selection. Both corners must be in the mine
world. Confirm the result with:

```text
/mines list
```

Faction claiming does not define a mine. Only the Mine Selector places the
mine region.

## 3. Fill the mine

After selecting the region, seed the configured ore table:

```text
/mines fill stonewake
/mines fill bloodvein
```

The fill runs in batches and displays progress. It only replaces configured
base blocks, so walls and structure are protected. If the server restarts
during the initial fill, run the fill command again.

## 4. Configure a mine KOTH

Stonewake KOTH:

```text
/mines create stonewake koth
```

Bloodvein KOTH:

```text
/mines create bloodvein koth
```

Use the same Mine Selector flow: left-click the first corner, right-click the
second, then Shift-click air to save. The KOTH area must be inside its mine.
Review the KOTH timing, control, hologram, and booster settings in
`mines.yml`, then run `/vertex reload` after config edits.

## 5. Create a physical mine portal

Create the portal volume for Stonewake:

```text
/mines portal create stonewake stonewake_gate
```

Create the portal volume for Bloodvein:

```text
/mines portal create bloodvein bloodvein_gate
```

Use the Portal Selector to left-click the first portal corner, right-click the
second, and Shift-click air to save.

## 6. Create the mine flight route

Stonewake:

```text
/mines spawnpoints create stonewake stonewake_arrival
```

Bloodvein:

```text
/mines spawnpoints create bloodvein bloodvein_arrival
```

With the Portal Selector:

- Left-click each waypoint in order.
- Right-click to remove the newest waypoint.
- Shift + air-click to save.

One point is enough for a simple drop-in. Add points at turns or height
changes; every point and interpolated flight path must remain inside the
corresponding mine region. Mine portals require their own route; they cannot
use a Haven or Riftlands route.

Verify the result:

```text
/mines portal list
/mines spawnpoints list
/mines spawnpoints preview stonewake_arrival
/mines spawnpoints preview bloodvein_arrival
```

## 7. Test mining behavior

Inside Stonewake, test coal, iron, and redstone. Inside Bloodvein, test gold,
lapis, diamond, emerald, and netherite entries configured in `mines.yml`.

Confirm that:

- Stone and configured ores can be mined.
- Unconfigured structure blocks cannot be mined.
- Ore drops are resource items, not ore blocks.
- Silk Touch does not return the ore block.
- Booster bonuses affect the configured drop quantity.
- Mined blocks regenerate after the configured delay.
- Players cannot place blocks inside the mine region.

## Common problems

- Mine does not appear in `/mines list`: the corners were not saved with
  Shift + air-click, or the wrong mine ID was used.
- `/mines fill` does nothing: verify the mine is defined and the selected
  region contains the configured base blocks.
- Portal says unknown destination: use the exact IDs `stonewake` or
  `bloodvein`, and confirm the mine has been placed first.
- Route will not save: add at least one point inside the mine, not in the
  portal room or outside the mine region.
- Portal does not start: check `/mines portal list`, `/mines spawnpoints list`,
  `vertex.portals.use`, and combat status.
