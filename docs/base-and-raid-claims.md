# Base Claims and Raid Claims

Vertex owns faction claims and layers two claim types on that native claim
system. Claiming/unclaiming is performed through `/f`, and Vertex events keep
Base Claims and Raid Claims synchronized.

- **Base Claim** — a faction's designated safe region. Permanent unless
  explicitly removed.
- **Raid Claim** — any faction claim that is *not* part of a Base Claim.
  Each one expires on its own real-world timer.

Both types are native Vertex claims. Raid Claim expiration safely removes the
stored native claim; no external faction plugin is involved.

## Base Claims

- The **first** Base Claim is free: any Leader or Co-Leader runs `/f
  baseclaim` while standing in one of their faction's own (non-Base)
  claims to anchor it there.
- Up to **3** Base Claims total per faction. Slot 1 is always unlocked;
  slots #2 and #3 are unlocked by the explicit levels of the existing
  `base-claim-slots` faction upgrade. Only a Leader or Co-Leader can purchase
  faction upgrades.
- Only a Leader or Co-Leader can create or remove a Base Claim; any
  member can view one (`vertex.baseclaim.view`).
- Removing a Base Claim requires two GUI confirmations after the initial
  left-click. Red cancel or closing either inventory cancels the operation.
  Removal is blocked while:
  - the faction's Shield is active (now wired to the real check —
    `BaseClaimManager.setShieldActiveQuery` is set by `VertexPlugin` to
    `ShieldManager::isShieldActive` once both managers exist; see
    [Faction Shield](faction-shield.md)), or
  - any chunk in the region still contains a tracked spawner.
- Removing a Base Claim converts every one of its chunks back into plain
  (Raid Claim) claims.

### Connected Base Claim regions

Any same-faction claim **physically adjacent** (sharing an edge, not just
a corner) to a chunk already in a Base Claim's region joins that region
automatically the moment it's claimed — `ClaimEventListener` calls
`BaseClaimManager.tryConnect` from Vertex's native claim event. A
multi-chunk region is treated as a single Base Claim; the original anchor
chunk is always preserved as the region's reference point.

Another faction's claims never join a region, no matter how they sit
geometrically — adjacency is always faction-scoped.

Each region has its own **independent** cap
(`factions.yml`'s `base-claim.max-chunks-per-region`, default 2000) — this
is *per region*, not summed across a faction's up to 3 Base Claims. Once
a region is full, further adjacent claims stay Raid Claims (with their
own expiration timer), and the claiming player gets a chat warning
(`baseclaim.region-full`) that the connection failed because the region
is full.

If unclaiming a chunk would disconnect part of a Base region, Vertex shows a
warning/confirmation GUI. Confirming unclaims the selected chunk, keeps the
anchor-connected component as Base land, and atomically converts each
disconnected still-owned chunk to a Raid Claim with a fresh configured timer.
Closing or cancelling the GUI leaves the region unchanged.

## Raid Claims

Every faction claim that isn't part of a Base Claim region is a Raid
Claim. Each Raid Claim **chunk** has its own independent expiration
timestamp, stored as an absolute epoch millisecond deadline
(`raid_claim_expirations`), not a countdown — the same
"persist the deadline, catch up on restart" idiom `FTopManager` and
`HotZoneManager` use elsewhere in this plugin.

- Default duration: 7 hours (`factions.yml`'s
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

## TNT / explosion / Wither rules

Explosion protection is controlled by [Grace and Faction Shield](faction-shield.md):

- Active Grace protects blocks and living entities in every faction claim.
- An active Shield protects blocks and living entities only in that faction's
  Base Claim chunks. Raid Claims remain vulnerable.
- Base Claims are not permanently explosion-proof. When neither Grace nor
  the owning faction's Shield is active, explosion behavior is normal.
- Wilderness is not protected by either system.
- Protection removes only protected locations from an explosion's block
  list; one explosion crossing a claim border can therefore protect inside
  blocks while still damaging outside blocks.
- **Withers are disabled server-wide.** `WitherPreventionListener`
  cancels `CreatureSpawnEvent` whenever the spawned type is `WITHER`,
  regardless of `SpawnReason` — this covers the vanilla
  soul-sand-and-three-skulls construction (`SpawnReason.BUILD_WITHER`,
  the primary way players make one) exactly the same as a spawn egg,
  spawner, or command. No world or claim scoping; no exceptions.
- **No admin bypass is wired in for the block-damage rule.** This
  codebase's one existing "admin bypasses claim protection" pattern,
  `StaffBuildListener`, un-cancels already-cancelled events for players in
  staff-build mode — but every event it covers carries a `Player` to
  check permissions against. `EntityExplodeEvent`/`BlockExplodeEvent`
  carry no igniting player in vanilla Bukkit, so there was nothing to
  hook a bypass onto without inventing new state (tracking who lit each
  TNT) that the spec never asked for. If a staff bypass for this specific
  rule is wanted later, it needs that new tracking built first.
- **No player-facing messages are emitted for explosion suppression.** Both
  suppression rules are structurally silent — there is no player
  reference available at the moment block damage is stripped or a Wither
  spawn is cancelled, so there is no clean way to attribute either event
  to a specific player's screen. This mirrors how `FallDamageImmunityListener`
  and vanilla's own blocked-spawn cases give no chat feedback either.

## Data model

New tables (`me.vertex.core.claims.ClaimStorage`), added to
`StorageMigrator` so a `/vertex storage` dialect switch carries them over:

- `base_claims` — one row per unlocked-and-anchored Base Claim slot
  (`faction_id`, `slot_index`, anchor world/x/z, `created_at`).
- `base_claim_region_chunks` — every chunk ever admitted into a region,
  keyed by `(world, chunk_x, chunk_z)`. Never pruned on unclaim — see
  "Connected Base Claim regions" above for why.
- `base_claim_slot_purchases` — legacy compatibility rows from builds that
  sold slots directly. New unlocks come from `faction_upgrade_levels`.
- `raid_claim_expirations` — one row per currently-tracked Raid Claim
  chunk, keyed by `(world, chunk_x, chunk_z)`.

`base_claim_region_chunks` is required because each Base is a connected
multi-chunk region rather than only an anchor. The legacy slot-purchase table
is read during upgrades so existing servers do not relock slots players had
already bought; it is not written by current gameplay.

## Internal integration APIs

- `BaseClaimManager.isBaseClaim(Location)` /
  `isPartOfBaseClaimRegion(Location)` — used by Shield, TNT rules,
  Chunk Busters, and Source Buckets.
- `BaseClaimManager.removeAnchor` — enforces the current Shield-active and
  spawner checks before conversion.

## Commands & permissions

| Command | Permission | Notes |
|---|---|---|
| `/f baseclaim` | `vertex.baseclaim.view` | Opens the info/removal GUI if standing on an existing Base Claim; otherwise attempts to create one. |
| `/f baseclaim` (create) | `vertex.baseclaim.create` | Leader/Co-Leader only. |
| Remove confirm (GUI) | `vertex.baseclaim.remove` | Leader/Co-Leader only. |
Slots #2/#3 are purchased from `/f upgrades`; there is no `/f baseclaim buy`
command or separate slot-purchase permission.

## Configuration

- `factions.yml` — Base Claim region cap, Raid Claim duration/sweep interval,
  and explicit `faction-upgrades.upgrades.base-claim-slots` prices and
  unlocked-slot values.
- `gui/baseclaim.yml` — the info/removal GUI, following the same
  `MenuLayout`-driven format as every other Vertex GUI (see
  [GUI Framework](gui-framework.md)).
- `lang/en_us.yml`'s `baseclaim:` / `raidclaim:` sections.
