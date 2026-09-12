# Sand Bots

`/sandbot give <player>` gives a Sand Bot item. It may be placed on any
block inside the placing player's own faction claim. FancyNPCs is required
for the Bot display and its right-click control panel.

## Command and permissions

| Command | Permission | What it does |
|---|---|---|
| `/sandbot give <player>` | `vertex.sandbot.give` | Gives a Sand Bot item. |
| `/sandbot stop` | Open to all | Stops your own active Sand Bot(s). |
| `/sandbot stop <player>` | `vertex.sandbot.admin` | Stops another player's active Sand Bot(s). |

The item lore shows the live square placement area. The bundled default is
an **11 × 11** footprint (`sandbot.radius-blocks: 5`); changing that configured
radius changes both the real scan and the area printed on newly given Bots.

The Bot scans the configured flat radius at the block level directly below
it. These blocks are anchors and remain in place:

- Andesite fills its column with gravel.
- Sandstone fills its column with sand.
- Red sandstone fills its column with red sand.
- Any coloured concrete fills its column with matching concrete powder.

For each anchor, the Bot creates one falling block directly underneath it.
After that block lands, it creates the next one. This continues until the
column has stacked back up to the underside of the anchor. Each block is
charged at the matching Shop buy price directly from the native Vertex
faction bank. If the bank cannot pay, the Bot is paused and remains deployed;
it does not despawn. While an active Bot is trying to place and the bank is
below `sandbot.low-bank-warning-threshold`, online faction members receive a
rate-limited warning.

Right-click the Bot to pause/resume it or despawn it. `/sandbot stop` stops
your own active Bots; `vertex.sandbot.admin` can stop another player's Bot.
Placed Bots are saved in the plugin data file `sandbots.yml`, including their
owner, faction, world, position, and paused/active state, and are recreated
after a reboot. Deliberately despawning a Bot removes its saved record.
