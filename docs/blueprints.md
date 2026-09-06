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
2. Placing the item puts down its marked Beacon preview and immediately
   opens an **Enable Blueprint** confirmation GUI. **Cancel** (or breaking
   the unactivated preview Beacon) removes the preview and returns the
   Blueprint item. Right-clicking the preview reopens the confirmation.
3. On confirm, the entire schematic's bounding box must fit **100%**
   inside the placing player's own faction's claimed land (checked
   synchronously against every touched chunk) — otherwise the attempt is
   refused outright and nothing is consumed.

## While a build is active

- The beacon cannot be mined or moved by pistons while active. If an
  explosion destroys an active anchor, Vertex immediately removes the
  active record and hologram and leaves only the blocks already placed;
  the Blueprint is not refunded. A completed Beacon remains protected and
  opens the repair/status GUI for its faction.
- A DecentHolograms display above it shows the structure's name, current
  progress, and `[Faction] player name`.
- The structure is placed progressively in horizontal rows from the
  bottom up, a small number of blocks per server tick, targeting
  `build-time-seconds` total. `max-blocks-per-tick` caps how much can be
  placed in a single tick to protect TPS — an unusually large schematic
  may take longer than `build-time-seconds` as a result, rather than
  causing a lag spike.
- Progress and the hologram checkpoint every `batch-interval-ticks`.
- Right-clicking the active beacon shows blocks placed and an estimated
  **time left**. The estimate uses the real per-tick block cap, so a very
  large schematic correctly shows a longer duration than a small one.
- When finished, the hologram changes to a completed status display. It
  reports missing blocks and offers repairs from the Beacon's GUI. Preview
  and active-build holograms are removed on cancellation/abort, including
  explosions; a completed hologram is also removed if its anchor disappears
  through an external change. Existing completed anchors are rediscovered as
  chunks load after a restart.
- Every `claim-recheck-interval-ticks`, the build's claim status is
  re-verified. If the land is no longer 100% the owning faction's
  (overclaimed, voluntarily unclaimed, etc.), the build **aborts
  immediately** — blocks placed so far are left as-is, and the Beacon is
  removed. A Blueprint item is returned only for a same-session cancel before
  the first block is placed; a restored build and repairs never return an
  item. This is the same outcome as an explicit cancel via the beacon's own
  progress GUI (right-click it mid-build).

## Persistence across restarts

An active build's world/location, template, owner, owning **faction id**,
and current progress index are saved to the database at each checkpoint.
The stable faction id means a faction rename cannot transfer or abort an
active build. Vertex also creates a private snapshot under
`plugins/Vertex/blueprint-snapshots/` before marking a build active. On a
restart it restores from that snapshot as **paused**, rather than continuing
automatically. A member of the owning faction must right-click the matching
Beacon and click **Resume Build** in its GUI; Vertex validates the active
Beacon and the complete claimed footprint again before it continues. This
also uses the snapshot rather than a potentially edited template file.
Snapshot files are removed when their build finishes or is cancelled.

## Cooldown

`cooldown-seconds` (default 3600 = 1 hour) is a per-player cooldown
between placements. It is stored in the database, survives restarts, and
staff can clear an online **or offline** player's active cooldown with
`/blueprint cooldown remove <player>`
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
| `max-schematic-bytes` | Largest `.schem` file Vertex will load (default 32 MiB) |
| `max-schematic-blocks` | Maximum non-air blocks accepted from one schematic |
| `max-schematic-dimension` | Maximum width, height, or depth of one schematic |
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
