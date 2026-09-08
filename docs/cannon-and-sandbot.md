# TNT Cannons & Sand Bots

Two related, still-growing modules for controlled redstone TNT cannons and
automated falling-block filling. Both are a first pass (MVP scope) — see
[Deferred](#deferred) at the end of each section for what's intentionally
not built yet.

## TNT Cannons

Applies only to TNT that ignites **without a player's hand directly
lighting it** — a redstone signal or a dispenser, which in practice means
a built cannon mechanism. A player manually lighting TNT with flint and
steel (or TNT chain-reacting off *that* explosion) is left completely
vanilla, so ordinary base-breaking/defense play is unaffected.

For every redstone/dispenser-triggered TNT chain, the module:

- Caps ignitions per world per tick (`cannon.max-ignitions-per-tick`) and
  per exact block position (`cannon.max-simultaneous-per-location`) —
  excess ignitions are cancelled outright rather than dropped silently; a
  real redstone clock simply retries on its next pulse.
- Clamps the upward velocity any cannon explosion leaves on nearby TNT,
  falling blocks, and players (`cannon.max-y-velocity`), with a tighter
  multiplier while the payload's current Y sits inside
  `cannon.height-band` (default 256–320) — and a hard, config-independent
  ceiling at the world's actual build-height limit.
- Suppresses block damage and fire spread for the first
  `cannon.launch-window-ticks` after a sequence's first ignition (the
  barrel/launch structure), so a cannon doesn't blow itself up every
  shot.
- Treats anything after that window as a payload "landing": real,
  weaponized explosion damage applies there, but only after re-checking
  `cannon.disabled-regions`/`cannon.disabled-claim-names` — the same
  blocklist model [abilities](kits-and-abilities.md) already use —
  **independently of the launch point**, so firing from an allowed
  location can't be used to bypass protection on someone else's claim.

`/cannon toggle` is a global on/off switch for when something needs to
stop firing immediately; `/cannon reload` picks up `cannon.*` config
changes without a restart. Both need `vertex.cannon.admin`.

Stocking a cannon's dispensers is a separate command — see
[`/tntfill`](factions-integration.md#faction-bank) in Factions
Integration, which fills every dispenser in range inside your claim from
either the faction's native TNT bank or your own inventory.

### Deferred

Player-payload riding (fall-damage handling, launch range limits), full
staff tooling (audit log, CoreProtect integration, blacklist/stats/tp
commands, real-time alerts), a concurrent-entity cap independent of the
per-tick limit, and a dedicated TNT/falling-block duplication-exploit
pass. None of these are wired up yet.

## Sand Bots

`/sandbot give <player>` (`vertex.sandbot.give`) hands out a Sand Bot
item. Placed on top of andesite, sandstone, red sandstone, or any color
of concrete — and only inside the placing player's own faction's claim —
it cancels the block placement and spawns a Citizens NPC in its place
instead (needs [Citizens](integrations.md) installed).

Every `sandbot.tick-interval-ticks`, the bot scans a flat
`sandbot.radius-blocks` grid (2 = 5×5, capped at 5) at its own floor
level for blocks matching whatever it was placed on. For each match, it:

1. Charges the faction — FactionsUUID's native `/f money` first, falling
   back to Vertex's own `/fbank` (`FactionBankManager`) — at the
   [Shop](shop.md)'s price for the matching falling-block material
   (`GRAVEL`/`SAND`/`RED_SAND`/matching `*_CONCRETE_POWDER`, all shipped
   with `dynamic-pricing: false` so bulk conversion can't spike the
   price).
2. Clears the trigger block and spawns a falling-block entity of that
   material in its place — normal vanilla gravity carries it down to
   wherever it lands.

A pass that finds no more matching blocks in range, or a charge that
fails, despawns the bot. `/sandbot stop` stops your own; staff with
`vertex.sandbot.admin` can stop anyone's with `/sandbot stop <player>`.

Two geometry notes, since they were judgment calls rather than something
that could be verified without live testing: each matching column is
converted exactly once (not repeatedly refilled at the same spot), and
the grid is checked at one fixed Y — uneven terrain under the radius just
won't have matching blocks there and gets skipped.

### Deferred

Using falling blocks as rate-limited vertical scaffolding (as opposed to
the payload-conversion use above), and the "hammer"/rev-ratio/full-nuke
detonation mechanic a filled shaft is presumably meant to feed into —
neither is built yet.
