# Sand Bots

`/sandbot give <player>` gives a Sand Bot item. It may be placed on any
block inside the placing player's own faction claim. FancyNPCs is required
for the Bot display and its right-click control panel.

The Bot scans the configured flat radius at the block level directly below
it. These blocks are anchors and remain in place:

- Andesite fills its column with gravel.
- Sandstone fills its column with sand.
- Red sandstone fills its column with red sand.
- Any coloured concrete fills its column with matching concrete powder.

For each anchor, the Bot creates one falling block directly underneath it.
After that block lands, it creates the next one. This continues until the
column has stacked back up to the underside of the anchor. Each block is
charged at the matching Shop buy price, using FactionsUUID faction money
first and Vertex `/f bank` money as a fallback.

Right-click the Bot to pause/resume it or despawn it. `/sandbot stop` stops
your own active Bots; `vertex.sandbot.admin` can stop another player's Bot.
