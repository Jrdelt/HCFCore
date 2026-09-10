# Source Buckets

Source Buckets are purchasable, permanently-reusable custom items that
place water or lava following a configured flow pattern. Unlike Chunk
Busters, a Source Bucket is never consumed — pay the `/shop` price once
and it stays in your inventory forever, chargeable a small fixed fee on
every successful use.

- **Usable by everyone.** No faction permission, no player permission
  node, no `/f permissions` requirement, no cooldown.
- **Never stacks.** Every Source Bucket item has its max stack size
  overridden to 1 (Paper's per-item stack-size API), since a second copy
  of an already-infinite-use item is always redundant.
- **Per-variant configuration.** `sourcebuckets.yml` defines any number of
  variants per liquid — each with its own flow pattern, `base-claim-only`
  flag, combat rule, shop price, and per-use fee. Water and Lava are
  configured in entirely separate sections and never share settings.

## Where they work

| Location | Allowed? |
|---|---|
| A valid faction claim (any faction's — not just your own) | Yes, subject to that variant's `base-claim-only` flag below |
| A Base Claim | Always allowed |
| A Raid Claim | Allowed only if the variant's `base-claim-only` is `false` |
| Wilderness (unclaimed) | **Never** |
| SafeZone / WarZone (`sourcebuckets.yml`'s `disabled-claim-names`) | **Never** |

Unlike Chunk Busters, there is no faction-ownership check at all — a
Source Bucket works in *anyone's* valid claim, per spec ("usable by
everyone, no faction permission"). The `base-claim-only` flag is the only
thing that can narrow which zones a specific variant works in.

## Flow patterns

Two patterns are implemented, both driven by a single `flow-pattern` key
so a future third pattern is one more enum constant and one more branch
in `SourceBucketManager.computeFlowPositions`, not a rewrite:

- **`single-source`** — places exactly one source block at the placement
  point.
- **`downward`** — places a source at the placement point and on each
  valid block straight down from it, up to `max-depth` blocks deep,
  stopping early at the first obstruction or claim-boundary crossing.

**Obstruction rule.** A candidate block stops the flow (without being
placed) when it is neither air nor already the same liquid this variant
places — matching the spec's literal "a non-air, non-liquid-compatible
block" wording exactly. This is a deliberate simplification versus full
vanilla bucket-empty semantics, which also special-cases things like tall
grass, snow layers, or the *opposite* liquid — the spec's own wording
gives only two non-obstructing categories, so that is all this
implements. The obstruction itself is never overwritten or pushed
through.

## The claim-boundary rule (the correctness-critical part of this phase)

Every candidate position in a flow — not just the placement point — must
independently satisfy all of the following, checked in
`SourceBucketManager.computeFlowPositions`:

1. **Never enters unclaimed land.** A position with no claiming faction at
   all stops the flow immediately.
2. **Never crosses a faction boundary, regardless of relationship.** The
   claiming faction id at each candidate position must exactly match the
   faction id at the origin. There is no ally/enemy/neutral check anywhere
   in this logic — the plain id comparison alone is what enforces "always
   stop, no exceptions based on relationship." `SourceBucketManagerTest`
   covers this with two nearly-identical tests (one where the neighboring
   claim would be an ally in production, one where it would be an enemy)
   that assert the exact same outcome either way, since the manager never
   even queries which relationship applies.
3. **Never crosses a SafeZone/WarZone boundary.** Checked explicitly and
   independently of the faction-id comparison above (belt-and-braces —
   the spec calls this out as its own stopping rule, not merely an
   implication of the ally/enemy rule). Uses the identical mechanism Chunk
   Busters and the TNT rules already use: `sourcebuckets.yml`'s own
   `disabled-claim-names` list, matched case-insensitively against the
   claim's tag (`FactionsHook.getClaimFactionTag`) — the same shape as
   `FactionsHook.isDisabledClaim`, not a reinvented mechanism.
4. **Still satisfies `base-claim-only`, at every step.** A variant
   restricted to Base Claims can never drift into an adjoining Raid Claim
   of the *same* faction either — the check re-runs on every candidate
   block, not just the origin.

A chunk boundary is **not** itself a stopping condition — a flow may cross
one as long as every block it touches passes the four checks above,
stopping at the first invalid block rather than at the chunk edge. For the
two patterns implemented today this distinction rarely bites in practice:
a straight-down `downward` flow stays in one `x, z` column, and
FactionsUUID claims apply to a whole chunk regardless of height, so the
claim status literally cannot change as the flow descends. The check still
runs on every step regardless, so a future horizontal flow pattern
inherits correct claim-boundary behavior for free.

No WorldGuard or other external region-protection plugin is consulted
anywhere in this feature — only Vertex's own `FactionsHook` claim/world
rules, per spec.

## Combat

Configured **independently per variant** via `combat-allowed` — there is
deliberately no single global combat rule for every Source Bucket. A
variant with `combat-allowed: false` is blocked outright while
combat-tagged (`CombatManager.isTagged`); one with `combat-allowed: true`
has no combat check at all.

## Economy: validate → place → charge-only-after-success

This is a deliberate departure from this codebase's usual debit-first,
refund-on-failure idiom (see `AuctionManager.list`) — safe here only
because a Source Bucket use has no async gap between checking and
placing. The exact order, in `SourceBucketManager.use`:

1. **Zone/combat validation** — fails fast with no world read at all.
2. **Flow computation** (`computeFlowPositions`, read-only) — an empty
   result means the origin itself is obstructed, i.e. a *failed
   placement*; nothing is charged, and no block is touched.
3. **Balance validation** (`Economy.has`) — only reached once placement is
   already known to be possible, per "validate placement, validate
   balance" in that order. Skipped entirely for a variant with
   `per-use-fee: 0` — a free bucket needs no economy provider at all.
4. **The actual placement.**
5. **The charge** (`Economy.withdrawPlayer`) — only after every block
   above was actually placed.

Failed placement and insufficient funds are reported with two distinct
messages (`sourcebucket.failed-placement` / `sourcebucket.insufficient-funds`)
so a player can tell "something was in the way" apart from "you can't
afford this." A successful use shows the amount actually charged
(`sourcebucket.used`).

## Item identity

Tagged with a single plain type-marker PDC key (`source_bucket_variant` →
the variant's id, e.g. `water:downward`) — the same idiom `WandManager`
uses for its `wand_tier` key, **not** an instance ID via `TrackedItemIds`.
Source Buckets are not in the anti-dupe spec's initial target list
(Sell/TNT Wands, Blueprints, custom armour), and they carry no per-item
mutable state duplication could desync — every copy of the same variant
behaves identically forever, unlike a Wand's remaining-uses counter. A
plain type marker is sufficient for the listener to recognize "this is a
Source Bucket of variant X."

If a variant is later removed from `sourcebuckets.yml` entirely (as
opposed to merely disabled), an already-owned item simply stops being
recognized — the same limitation `WandManager.tierOf` already has for a
removed tier, since there is genuinely no config left to describe it.

## Triggering a use: `PlayerInteractEvent`, not `PlayerBucketEmptyEvent`

`PlayerBucketEmptyEvent` only fires for an actual vanilla bucket
`Material` (`WATER_BUCKET`/`LAVA_BUCKET`/etc.), and its default handling —
consuming the bucket into an empty one and placing a single source block —
would have to be cancelled and completely re-implemented anyway to get a
non-consumed, multi-block, claim-aware flow. `sourcebuckets.yml`'s
`material` is also configurable per variant exactly like
`ChunkBusterType.material` (it need not literally be a bucket item), so an
event that only fires for two specific vanilla materials would not even
cover every configured variant.

`PlayerInteractEvent` fires for any item, is cancelled the same way
`ChunkBusterListener` already cancels it for a matching custom item (which
prevents vanilla's own bucket-use logic — and therefore
`PlayerBucketEmptyEvent` — from ever running), and gives full control over
targeting and placement. This mirrors the most recent precedent in this
codebase for "right-click a custom PDC-tagged item to trigger an action"
exactly.

**Target block.** The flow's origin is
`clickedBlock.getRelative(blockFace)` — the block adjacent to the clicked
face, standard "place a new block against this face" semantics — rather
than the clicked block itself. This is simpler than replicating vanilla
bucket-empty's "overwrite the clicked block when it's itself
replaceable/waterlogged, otherwise use the relative face" nuance, and this
phase's correctness-critical requirements are entirely about claim
boundaries and charge ordering, not placement-target fidelity to vanilla
buckets.

There is no confirmation GUI, unlike Chunk Busters — placing water/lava is
not the kind of irreversible, no-drops-ever destructive action the spec
requires confirming, so a use happens the instant you right-click.

## Acquisition

Source Buckets are `/shop`-purchasable, following the exact precedent
Chunk Busters set: a Source Bucket is a custom item with a config-driven
price, not a plain vanilla `Material`, so it cannot become a literal
`ShopEntry` row.

- Per-variant price, material, name, and lore live in
  `sourcebuckets.yml` — **not** `shop.yml`.
- A "Buy Source Buckets" button (`ShopMenu.SLOT_BUY_SOURCE_BUCKETS`, slot
  8) sits in the **Miscellaneous** category's control row
  (`ShopMenu.SOURCE_BUCKETS_HOST_CATEGORY = "miscellaneous"`) — chosen
  because Miscellaneous already sells a plain vanilla `BUCKET`, so a
  reusable water/lava Source Bucket fits thematically without needing a
  10th category slot in the single-row `/shop` picker.
- Clicking it opens `SourceBucketShopMenu`, a small catalog of every
  *enabled* variant (both liquids together); buying withdraws the
  configured price via Vault and gives the item (dropping it at the
  player's feet if their inventory is full).

No `shop.yml` edits were needed to wire this up — the button lives in
`miscellaneous`'s existing control row purely in code, exactly like the
Chunk Busters button does in Raiding Materials.

## Why no new database table

A Source Bucket is a physical, permanently-reusable item — once bought,
possession of the item itself *is* the record of ownership, the same way
a Wand, a Backpack, or a Chunk Buster needs no server-side "who owns one"
ledger. The spec's "one-time purchase, unlocks infinite use" describes
what happens to a single physical item (it never gets consumed), not a
per-player entitlement the server must remember independently of whether
the player still has the item. Nothing in the spec asks for re-buy
prevention, a usage log, or any other server-side bookkeeping a table
would exist to serve — so, unlike Base Claims/Shield/Chunk Busters (which
all track state no physical item could carry on its own), this phase adds
none.

## Testability

Every live-FactionsUUID/`CombatManager`/`BaseClaimManager` lookup is
injected into `SourceBucketManager` as a plain functional interface,
mirroring `ChunkBusterManager`'s exact shape (itself mirroring
`ExplosionProtectionListener`'s `Predicate<Location> isBaseClaim`):
production wiring in `VertexPlugin` passes real method references
(`FactionsHook::getClaimFactionId`, `FactionsHook::getClaimFactionTag`,
`baseClaimManager::isBaseClaim`, `combatManager::isTagged`); tests pass
lambdas returning canned per-location values. `SourceBucketManagerTest`
exercises single-source placement, max-depth/obstruction stopping,
faction-boundary stopping (both an ally-flavored and an enemy-flavored
case, asserting the identical outcome), SafeZone/WarZone stopping,
never-enters-unclaimed-land, `base-claim-only` enforcement (including a
flow drifting out of a Base Claim region mid-flow), the charge-after-
success ordering (asserting zero balance change on every failure path),
and the per-variant combat gate — all with a real (mock) `World` for
actual block mutation, but no live FactionsUUID or MockBukkit event
simulation required.

## Commands & permissions

| Command | Permission | Notes |
|---|---|---|
| Right-click a block with a Source Bucket | *(none — gated by zone/combat/economy checks in-code)* | Places water/lava immediately; no confirmation step. |

Source Buckets have no dedicated command of their own — acquisition is
entirely through `/shop`.

## Configuration

- `sourcebuckets.yml` — per-variant `enabled`/`flow-pattern`/`max-depth`/
  `base-claim-only`/`combat-allowed`/`shop-price`/`per-use-fee`/
  `material`/`custom-model-data`/`glow`/`name`/`lore`, grouped under
  top-level `water:`/`lava:` sections, plus its own `disabled-claim-names`
  list (independent from `abilities.disabled-claim-names` in `config.yml`
  and from `chunkbuster.yml`'s own list, for the same reason
  `chunkbuster.yml` gives itself one — see
  [Chunk Busters](chunk-busters.md#why-chunkbusteryml-has-its-own-disabled-claim-names-instead-of-reusing-abilitiesdisabled-claim-names)).
- `lang/en_us.yml`'s `sourcebucket:` section, plus
  `shop.sourcebuckets-category-title` for the `/shop` control-row button.
