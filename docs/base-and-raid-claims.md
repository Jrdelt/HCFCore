# Base Claims and Raid Claims

Vertex adds two claim types layered on top of FactionsUUID's own claim
system — FactionsUUID itself still owns claiming/unclaiming; Vertex only
tracks *which kind* each of a faction's claims is, and reacts to
FactionsUUID's own claim events to keep that in sync.

- **Base Claim** — a faction's designated safe region. Permanent unless
  explicitly removed.
- **Raid Claim** — any faction claim that is *not* part of a Base Claim.
  Each one expires on its own real-world timer.

Neither type exists without FactionsUUID's `dev.kitteh:factions` claim —
Vertex never claims or unclaims land on its own outside of Raid Claim
expiration.

## Base Claims

- The **first** Base Claim is free: any Leader or Co-Leader runs `/f
  baseclaim` while standing in one of their faction's own (non-Base)
  claims to anchor it there.
- Up to **3** Base Claims total per faction. Slots #2 and #3 are
  purchased with `/f baseclaim buy`, paid from the *purchasing member's*
  personal balance (any member can buy a slot, not just leadership) —
  priced by `claims.yml`'s `base-claim.slot-2-price` /
  `slot-3-price`.
- Only a Leader or Co-Leader can create or remove a Base Claim; any
  member can view one (`vertex.baseclaim.view`).
- Removing a Base Claim requires confirming in a GUI (green confirm /
  red cancel / closing the inventory without clicking either both count
  as cancel — see `gui/baseclaim.yml`). Removal is blocked while:
  - the faction's Shield is active (stubbed as `false` for now —
    `BaseClaimManager.removeAnchor` has a one-line TODO-free comment
    marking where Phase 2 wires in the real check), or
  - any chunk in the region still contains a tracked spawner.
- Removing a Base Claim converts every one of its chunks back into plain
  (Raid Claim) claims.

### Connected Base Claim regions

Any same-faction claim **physically adjacent** (sharing an edge, not just
a corner) to a chunk already in a Base Claim's region joins that region
automatically the moment it's claimed — `ClaimEventListener` calls
`BaseClaimManager.tryConnect` from FactionsUUID's `LandClaimEvent`. A
multi-chunk region is treated as a single Base Claim; the original anchor
chunk is always preserved as the region's reference point.

Another faction's claims never join a region, no matter how they sit
geometrically — adjacency is always faction-scoped.

Each region has its own **independent** cap
(`claims.yml`'s `base-claim.max-chunks-per-region`, default 2000) — this
is *per region*, not summed across a faction's up to 3 Base Claims. Once
a region is full, further adjacent claims stay Raid Claims (with their
own expiration timer), and the claiming player gets a chat warning
(`baseclaim.region-full`) that the connection failed because the region
is full.

If a chunk that's part of a region is later **unclaimed**, the
relationship is *not* destroyed — `base_claim_region_chunks` never prunes
a row on unclaim. Reclaiming that same chunk later is recognized as
rejoining the region immediately, without re-running the adjacency
search and without being newly subject to the cap (it was already
counted once).

## Raid Claims

Every faction claim that isn't part of a Base Claim region is a Raid
Claim. Each Raid Claim **chunk** has its own independent expiration
timestamp, stored as an absolute epoch millisecond deadline
(`raid_claim_expirations`), not a countdown — the same
"persist the deadline, catch up on restart" idiom `FTopManager` and
`HotZoneManager` use elsewhere in this plugin.

- Default duration: 7 hours (`claims.yml`'s
  `raid-claim.duration-seconds`), counted in **real elapsed time** —
  server downtime counts too.
- On startup, `RaidClaimManager.recoverState()` immediately unclaims any
  chunk that expired while the server was off, and every other tracked
  chunk resumes counting down from its original deadline, not a fresh
  timer.
- A lightweight periodic sweep (`raid-claim.sweep-interval-seconds`,
  default 30s) only re-checks *currently tracked* chunks — it never scans
  the world.
- Player-facing text: `raidclaim.claimed` ("Raid claimed — expires in
  {time}"), fully configurable in `lang/en_us.yml`.
- **Raid Claim creation and automatic expiration/unclaiming are never
  logged anywhere** — this is intentional per spec, not an oversight.

## Data model

New tables (`me.vertex.core.claims.ClaimStorage`), added to
`StorageMigrator` so a `/vertex storage` dialect switch carries them over:

- `base_claims` — one row per unlocked-and-anchored Base Claim slot
  (`faction_id`, `slot_index`, anchor world/x/z, `created_at`).
- `base_claim_region_chunks` — every chunk ever admitted into a region,
  keyed by `(world, chunk_x, chunk_z)`. Never pruned on unclaim — see
  "Connected Base Claim regions" above for why.
- `base_claim_slot_purchases` — which of slots #2/#3 a faction has paid
  for, independent of whether that slot has been anchored yet.
- `raid_claim_expirations` — one row per currently-tracked Raid Claim
  chunk, keyed by `(world, chunk_x, chunk_z)`.

This is one table more than the two sketched in the original brief
(`base_claims` / `raid_claim_expirations`); `base_claim_region_chunks`
and `base_claim_slot_purchases` were added because the spec's "preserve
the relationship across an unclaim" and "purchasable immediately, before
ever being anchored" requirements both need durable state beyond a
single anchor row per slot.

## Integration points for later phases

- `BaseClaimManager.isBaseClaim(Location)` /
  `isPartOfBaseClaimRegion(Location)` — the query API Shield, TNT rules,
  Chunk Busters, and Source Buckets will call.
- `BaseClaimManager.removeAnchor` — the exact line Phase 2's Shield-active
  check replaces.

## Commands & permissions

| Command | Permission | Notes |
|---|---|---|
| `/f baseclaim` | `vertex.baseclaim.view` | Opens the info/removal GUI if standing on an existing Base Claim; otherwise attempts to create one. |
| `/f baseclaim` (create) | `vertex.baseclaim.create` | Leader/Co-Leader only. |
| Remove confirm (GUI) | `vertex.baseclaim.remove` | Leader/Co-Leader only. |
| `/f baseclaim buy` | `vertex.baseclaim.purchaseslot` | Any member; charges their personal balance. |

All four default to `true` except the implicit leadership check enforced
in code (`FactionsHook.isLeader`), which applies regardless of
permissions.

## Configuration

- `claims.yml` — region cap, Raid Claim duration/sweep interval, slot
  prices.
- `gui/baseclaim.yml` — the info/removal GUI, following the same
  `MenuLayout`-driven format as every other Vertex GUI (see
  [GUI Framework](gui-framework.md)).
- `lang/en_us.yml`'s `baseclaim:` / `raidclaim:` sections.
