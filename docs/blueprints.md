# Automated Blueprint Base Builder

Places a full `.schem` structure automatically, gradually, inside a
faction's own claimed land — think "instant base" without an admin
having to paste a schematic by hand.

## Requirements

**Requires both FastAsyncWorldEdit and DecentHolograms.** If either is
missing, the feature is simply never wired up — logged once at startup,
no errors — since it can't function without them: FAWE is the only way
Vertex can load and paste a `.schem` file, and DecentHolograms is the
required progress display above the build. See
[Integrations](integrations.md).

## How a build starts

1. `/blueprint give <player> <template>` (`vertex.blueprint.give`) hands
   out a Blueprint item — a specially marked Beacon. Templates and their
   `.schem` file are defined in `blueprints.yml`; the schematic file
   itself lives under `<plugin data folder>/schematics/`, a folder
   created automatically at startup.
2. Placing the item opens an **Enable Blueprint** confirmation GUI. The
   item is not consumed and no block is placed until the player clicks
   **Enable**.
3. On confirm, the entire schematic's bounding box must fit **100%**
   inside the placing player's own faction's claimed land (checked
   synchronously against every touched chunk) — otherwise the attempt is
   refused outright and nothing is consumed.

## While a build is active

- The beacon is completely unbreakable and unmovable (explosions and
  pistons included) until the build finishes or is cancelled.
- A DecentHolograms display above it shows the structure's name, current
  progress, and `[Faction] player name`.
- The structure is placed progressively in horizontal rows from the
  bottom up, a small number of blocks per server tick, targeting
  `build-time-seconds` total. `max-blocks-per-tick` caps how much can be
  placed in a single tick to protect TPS — an unusually large schematic
  may take longer than `build-time-seconds` as a result, rather than
  causing a lag spike.
- Progress and the hologram checkpoint every `batch-interval-ticks`.
- The hologram exists only while the build is active. It is removed when
  the build completes, is cancelled, or aborts, so mining the completed
  beacon can never leave an orphaned display behind.
- Every `claim-recheck-interval-ticks`, the build's claim status is
  re-verified. If the land is no longer 100% the owning faction's
  (overclaimed, voluntarily unclaimed, etc.), the build **aborts
  immediately** — blocks placed so far are left as-is, and the beacon is
  **not** refunded. This is the same outcome as an explicit cancel via
  the beacon's own progress GUI (right-click it mid-build).

## Persistence across restarts

An active build's world/location, template, owner, owning **faction id**,
and current progress index are saved to the database at each checkpoint.
The stable faction id means a faction rename cannot transfer or abort an
active build. On restart, the same
`.schem` file is re-loaded (its deterministic iteration order reproduces
the identical block list) and the build resumes from the saved index —
after first re-validating claim ownership, since land can change hands
while the server is down.

## Cooldown

`cooldown-seconds` (default 3600 = 1 hour) is a per-player cooldown
between placements. Staff can clear an **online** player's active
cooldown with `/blueprint cooldown remove <player>`
(`vertex.blueprint.cooldown.remove`).

## Configuration reference (`blueprints.yml`)

| Key | Purpose |
|---|---|
| `enabled` | Master on/off switch |
| `schematics-folder` | Folder (under the plugin data folder) holding `.schem` files |
| `cooldown-seconds` | Per-player cooldown between placements |
| `build-time-seconds` | Target total build duration |
| `batch-interval-ticks` | How often progress/hologram checkpoints save |
| `max-blocks-per-tick` | Safety cap on blocks placed per tick (protects TPS) |
| `claim-recheck-interval-ticks` | How often claim ownership is re-verified mid-build |
| `templates` | Each placeable Blueprint: `schematic` (file name) and `display-name` |

After changing this file, run `/vertex reload` or restart the server.

## Staging checklist

Before enabling a new Blueprint template on production, test a normal
build, a faction rename during a build, claim loss during a build,
restart/resume, a missing or corrupt `.schem`, and a temporary database
outage while the build row is being created.

### Older schematics

Vertex accepts legacy generic block ids for beds, signs, skulls, and
banners so an old `.schem` still builds on current Paper. Their exact old
color/wood variant cannot be recovered from those retired ids, so Vertex
uses a valid default and logs a one-time warning. Re-save the schematic
with your current FastAsyncWorldEdit installation for an exact conversion.
