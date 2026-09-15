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
| `/sandbot debug` | `vertex.sandbot.debug` | Toggles chat diagnostics for every Bot you own -- funding, placement, and pause decisions -- until toggled off. Stays on across reconnects; only a server restart clears it. |

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
It waits while that entity remains in the source cell, but releases the
source immediately if gravity carries the entity out of that cell or a slime
block/piston moves it sideways. This keeps both deep drops and moving cannons
fed without deleting the earlier falling block. It
continues until the column has stacked back up to the underside of the anchor.
Each block is charged at the matching Shop buy price directly from the native
Vertex faction bank. To avoid database latency starving a moving cannon, the
Bot safely prepays a short configurable number of placement passes
(`sandbot.prepaid-buffer-passes`) and uses that reserve while its next bank
write runs. Any unused reserve is returned to the faction bank when the Bot is
paused, despawned, or the server stops. The reserve is also saved with the Bot
about once a second, so after a crash it resumes on the same Bot instead of
being lost; the bank is never charged twice for the same reserve, though up
to a second of blocks placed right before the crash may briefly show as still
funded after the restart, since that spending hadn't been saved yet. If the
bank cannot fund the next placement, the Bot is paused and remains deployed;
it does not despawn. While an active Bot is trying to
place and the bank is below `sandbot.low-bank-warning-threshold`, online
faction members receive a rate-limited warning.

Right-click the Bot to pause/resume it or despawn it. `/sandbot stop` stops
your own active Bots; `vertex.sandbot.admin` can stop another player's Bot.
Placed Bots are saved in the plugin data file `sandbots.yml`, including their
owner, faction, world, position, paused/active state, and unused prepaid
reserve, and are recreated after a reboot. If a saved Bot's world is missing,
its reserve is returned to the faction bank; if its faction no longer exists,
the amount is logged instead. Deliberately despawning a Bot removes its saved record.
