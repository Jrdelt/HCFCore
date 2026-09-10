# Custom Enchantments

A dedicated Vertex custom enchantment system, built entirely apart from
the vanilla enchanting table: **Runes** (tiered items you roll for a
random enchant), physical **enchant items** (the roll's result, applied
through their own GUI), and **Lucky Gems** (consumable success-chance
boosters). The whole framework lives in `me.vertex.core.enchant` and is
config-driven end to end — adding a new enchant, a new level, or changing
a tier's odds never touches Java code.

## Rune tiers

Four tiers — `SIMPLE`, `ELITE`, `RARE`, `LEGENDARY` — each permanently
fixed to a Rune the moment it's created (a purchase, an admin `/enchant
give`, whatever the source). A `SIMPLE` Rune always rolls off the
`SIMPLE` table in `runes.yml` for its entire life; it never upgrades or
downgrades.

## Rolling a Rune: a direct action, not a GUI

**Right-clicking a Rune rolls it immediately** — no confirmation screen,
no menu. This is a deliberate reading of the spec: section 12 describes
rolling as "right-clicking a Rune... create the physical enchantment
item, give/store it," while section 15 says *application* specifically
goes "through the custom GUI/system." Only application needed a GUI; a
Rune roll is a single, instant, server-authoritative action with a result
message, the same interaction shape `WandListener` already uses for
right-click-to-act items.

The roll:

1. Identifies the Rune's permanent tier.
2. Rolls that tier's `RuneRollTable` (`me.vertex.core.enchant.RuneRollTable`)
   with server-generated randomness.
3. Consumes one Rune from the stack.
4. Creates the resulting physical enchant item and adds it to your
   inventory (dropped at your feet if it's full).

`RuneRollTable` mirrors `me.vertex.core.mine.MineOreTable`'s shape
exactly on purpose: an immutable weighted list of entries, `pick(double
roll)` taking the random draw as a parameter (so the distribution is
unit-testable without depending on chance), and weights normalized
internally so an admin editing one entry's weight never has to rebalance
the rest of that tier's table.

**Each entry is a whole `(enchant, level)` combo**, not just an
enchant — `runes.yml`'s `table:` list for a tier names exactly which
combos that tier can produce and their relative weight. This is how "a
higher level intentionally rolls less often" is expressed: give the
higher-level combo a smaller weight than the lower-level one of the same
enchant, on the same tier's table. There is no separate two-stage
"pick the enchant, then pick the level" roll — the flat weighted list
already gives every entry its own independent odds.

## Physical enchant items

The result of a roll is a real item that sits in your inventory until you
either apply it successfully or lose it through a valid failed
application attempt. Every `(enchant, level)` combo is independently
configured in `enchants.yml`: material, name, lore, glow, proc chance,
application success rate, and a generic ability-strength value. Nothing
about one level's cosmetics or odds is inferred from another level's — a
level 4 enchant item can look, sound, and behave completely differently
from its own level 1.

Roll odds are **not** repeated in `enchants.yml` — they live in
`runes.yml`'s per-tier tables only, so there's exactly one place to look
for "how likely is this to roll" and no risk of the two files drifting
out of sync. A physical enchant item's own lore can still show its real
roll percentage via the `{roll_chance}` placeholder, computed live from
whichever tier actually rolled it (tagged on the item's own PDC).

## Lucky Gems

Consumable items that boost **application success chance only** — never
Rune roll odds. Use as many as you like in one attempt; the total chance
is clamped at 100% (adding more past that point is harmless, just
wasteful). Per-Gem effectiveness is configured in `runes.yml`'s
`lucky-gem-effectiveness:` map, **keyed by the origin tier of the
physical enchant item being applied** (not the target gear, which has no
tier of its own) — a Gem used on a Rune-rolled-from-`SIMPLE` item gives a
bigger boost than the same Gem used on a `LEGENDARY` one, on purpose, so
Gems can meaningfully help a low-tier application without letting anyone
gem their way to a guaranteed Legendary success.

Lucky Gems are deliberately **not** tagged via `TrackedItemIds` — see
[Duplication tracking](#duplication-tracking) below for why.

## Applying an enchant item: the Enchant Application GUI

Right-clicking a physical enchant item opens the Enchant Application GUI
(`me.vertex.core.enchant.EnchantApplyGui`, `gui/enchant-apply.yml`). The
triggering item is physically moved out of your hand into the menu's
middle slot — not merely shown as a copy — so it can never exist in both
places at once. Two more open slots take your gear (left) and any Lucky
Gems (right); a live "current success chance" readout recomputes every
time a slot's contents change. Confirm attempts the application;
Cancel — or simply closing the menu — returns whatever is still sitting
in the three slots to you untouched. Nothing is ever escrowed away.

### Compatibility

Every enchant declares `compatible-types` in `enchants.yml`: a mix of
named groups (`ALL`, `PICKAXE`, `AXE`, `SHOVEL`, `HOE`, `SWORD`, `BOW`,
`TOOL`, `HELMET`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`, `ARMOR`) and/or
explicit vanilla `Material` names, freely mixed. A pickaxe enchant simply
cannot be applied to a helmet — checked before anything else, so an
incompatible attempt never even reaches the level-replacement rules
below. The target item's source never matters: a pickaxe bought from
`/shop`, dropped by a mob, or pulled from a Chunk Collector are all
equally eligible.

### Level-replacement rules (same enchant only)

No custom-to-custom conflict matrix — only the *same* enchant's level is
ever compared:

| Target already has... | You apply... | Result |
|---|---|---|
| Nothing | Level *N* | Rolls for success normally. |
| Level *N* | Level *M* > *N* | Rolls for success normally; success replaces *N* with *M*. |
| Level *N* | Level *M* < *N* | **Rejected.** Level *N* is kept; nothing consumed. |
| Level *N* | Level *N* | **Rejected.** Nothing changes; nothing consumed. |

### Success, failure, and what gets consumed

Rune-roll odds and application odds are completely independent — a Rune
rolling a level is one random draw; applying that level is a second,
separate random draw, boosted by however many Lucky Gems you added.

| Outcome | Enchant item | Lucky Gems | Target item |
|---|---|---|---|
| **Success** | Consumed | Consumed | Enchant applied; lore + PDC updated |
| **Failure** | Consumed | Consumed | **Unchanged** |
| **Rejected** (incompatible / equal / lower-vs-higher) | Kept | Kept | Unchanged |

A valid attempt (success *or* failure) always consumes the physical
enchant item and every Lucky Gem placed, win or lose — this is the spec's
explicit "a valid application attempt can consume the physical item even
if the random application fails" rule. A *rejected* attempt is the only
case that consumes nothing at all.

## Lore

Vanilla enchantments and custom enchantments never compete for the same
lines: a real vanilla `Enchantment` renders in its own tooltip section
above the lore box automatically, by the client, regardless of what
Vertex writes into `meta.lore()`. Custom enchant descriptor lines are
plain lore lines placed *below* that — section 21's required ordering
falls out of how Minecraft already renders items, with no extra ordering
logic needed on Vertex's side.

**Pristine lore is preserved forever.** The first time an item ever
receives a custom enchant, its current lore (whatever it was — from a
crate reward, a previous Vertex feature, hand-written by staff, or simply
empty) is snapshotted once into PDC. Every later lore render — including
every subsequent level-replacement — rebuilds the item's full lore as
*that pristine snapshot* followed by one rendered block per currently
active custom enchant. Nothing above the snapshot is ever recomputed or
lost, and a later enchant application can never accidentally erase an
earlier, unrelated feature's lore lines.

Every lore template (Rune, physical enchant item, and the block appended
to a target item) uses the existing Vertex placeholder convention —
`{key}` curly-brace syntax via `me.vertex.core.menu.MenuPlaceholders`, the
same substitution engine every menu icon already uses (its `apply`/
`render` methods were widened from package-private to public specifically
so this feature — the first consumer outside the `menu` package — could
reuse them rather than the codebase growing a second template engine).
Available keys: `{tier}`, `{enchant}`, `{level}`, `{proc_chance}`,
`{success_rate}`, `{failure_rate}`, `{roll_chance}`, `{ability_value}`.
`{roll_chance}` only resolves to a real percentage on a physical enchant
item's own lore (computed from whichever Rune tier rolled it); on an
already-applied item's lore it renders as `0`, since the acquisition
context isn't carried on equipped gear.

## World restrictions

Each enchant configures its own `enabled-worlds`/`disabled-worlds` in
`enchants.yml` — `enabled-worlds` is a whitelist when non-empty,
otherwise `disabled-worlds` is a blacklist; leaving both empty means
active everywhere. A restricted world **never** removes the enchant from
the item, its data, or its lore — `EnchantManager#isEffectActive` simply
returns `false` there, for a future gameplay-effect listener to consult.
There is no automatic SafeZone/WarZone/claim override of any kind — only
an enchant's own configured restriction ever applies.

## Persistence through legitimate vanilla transformations

Everything Vertex-specific about an item lives entirely in that item's
own PDC (see [Why no new database table](#why-no-new-database-table)
below), which already survives restarts, logout/login, chests, faction
vaults, ender chests, and normal item movement for free. Two transforms
needed **new integration work with no precedent elsewhere in this
codebase**, since the platform computes a genuinely new result
`ItemStack` that cannot be assumed to already carry the source's PDC:

- **Anvil** (`PrepareAnvilEvent`) — rename, repair, and repair-combine.
- **Smithing table** (`PrepareSmithingEvent`) — the netherite (and any
  future template-based) upgrade.

Both hooks live in `RuneListener` and call the same underlying method,
`EnchantManager#preserveAcrossTransform(from, to)`: it copies the item's
`TrackedItemIds` instance ID + kind (via `TrackedItemIds#copy`, itself
new) and any active custom-enchant data + pristine base-lore snapshot
from the primary input item (the anvil's left slot; the smithing table's
base-equipment slot) onto the computed result, then rebuilds the result's
lore from scratch. It's a no-op — and costs nothing — for the overwhelming
majority of anvil/smithing uses that involve no Vertex item at all.

This is unit-tested directly against `preserveAcrossTransform` with plain
`ItemStack`s (data survives the copy, a pre-existing vanilla enchant on
the *result* item is never touched, an unrelated item is a no-op) rather
than against the real events, since MockBukkit has no practical way to
construct a fully wired `AnvilInventory`/`SmithingInventory` with a real
platform-computed result to assert against. **The two event handlers
themselves are thin plumbing with no independent logic of their own, but
should still get a manual smoke test on a real server** (anvil rename,
anvil repair, netherite upgrade) before shipping, per this codebase's
standard practice for genuinely new interaction surfaces.

## Duplication tracking

Every physical Rune (`ItemKind.RUNE`) and every physical enchant item
(`ItemKind.ENCHANTMENT_ITEM`) is tagged at creation; every target item is
tagged (`ItemKind.ENCHANTED_ITEM`) the moment it first receives a
successful application — all via `TrackedItemIds`, Phase 0's shared
per-item instance-ID utility. This is the *entire* integration into the
existing anti-dupe framework: `me.vertex.core.dupe.DupeManager#shouldTrack`
was extended with one additional check — any item carrying a real
(non-`GENERIC`) `ItemKind` is now automatically considered worth
tracking, alongside its existing hardcoded Wand/Blueprint/Collector/
Spawner marker checks. From that point on, `DupeManager`'s own existing
background scanning (triggered by pickups, drops, inventory opens/closes,
chunk loads, and player joins — see [Dupe investigation](dupe-investigation.md))
picks up Runes and enchant items exactly like it already does every other
tracked item, with no second detection system built for this feature.

**Lucky Gems are deliberately not tagged.** `TrackedItemIds#ensureInstanceId`
assigns one instance ID to an entire `ItemStack` object regardless of its
stack amount, which only makes sense for an amount-1 item — a stack of 64
Gems getting split or merged would leave more than one physical Gem
sharing the same "permanent, unique" ID. `DupeManager#ensureIdentity`
already only tracks amount-1 stacks for exactly this reason, so leaving
Gems untagged is consistent with, not a departure from, the existing
framework's scope.

## Why `MenuItemTemplate` grew a `glow` flag

`gui/enchant-apply.yml`'s icons needed a way to glint, and `MenuItemTemplate`
had no such flag before this phase. It now accepts `glow: true/false`
per template, applied via `ItemMeta#setEnchantmentGlintOverride(true)` —
the real Paper API for this (available since the item-components rework;
confirmed present in this project's targeted Paper 1.21.11 API), not a
worked-around dummy-enchant-plus-`HIDE_ENCHANTS` trick. Physical Rune and
enchant items (built directly in `EnchantManager`, not through
`MenuItemTemplate`) use the identical API call for their own per-level
`glow` config.

## Why the Sell Wand's `harmlessMarkers` allowlist was left alone

`WandManager#isPlainStack` already refuses to treat *any* item with a
display name or lore as "plain" — and every Rune, every physical enchant
item, and every successfully-enchanted target item always has at least
one of those (their cosmetics are entirely config-driven; a target item's
lore is rebuilt the moment it receives its first custom enchant). Unlike
the Chunk Collector's `mob_drop` marker — which tags otherwise completely
vanilla-looking loot that *should* stay sellable — none of this feature's
PDC markers are ever the sole thing disqualifying an item from being
"plain." Adding them to `harmlessMarkers` would have had no observable
effect, so nothing was added.

## Why no new database table

Every server-authoritative fact this feature needs — a Rune's tier, a
physical enchant item's identity, a target item's active custom enchants
and its pristine pre-enchant lore — lives entirely in that item's own
PDC. The item itself *is* the record, the same reasoning Source Buckets'
docs give for needing no ownership table: nothing here asks for
re-roll history, a purchase ledger, or any bookkeeping a physical item
can't already carry on its own.

## Acquisition

Runes have their own catalog rather than appearing in `/shop`. Use
`/runes`, `/ce`, `/customenchants`, or `/enchant` to open the four-tier
Rune GUI. Per-tier price, material, name, and lore live in `runes.yml`;
left-click buys a Rune with Vault money.

Lucky Gems have no shop entry — `/enchant give <player> gem [amount]` is
the only distribution path, per the spec's scope for this phase.

## Commands & permissions

| Command | Permission | Notes |
|---|---|---|
| `/runes` / `/ce` / `/customenchants` / `/enchant` | *(none)* | Opens the dedicated Rune shop. |
| Right-click a Rune | *(none)* | Rolls it immediately; no GUI. |
| Right-click a physical enchant item | *(none)* | Opens the Enchant Application GUI. |
| `/enchant give <player> rune <tier> [amount]` | `vertex.enchant.give` | `tier` is one of `simple`/`elite`/`rare`/`legendary`. |
| `/enchant give <player> gem [amount]` | `vertex.enchant.give` | Gives Lucky Gems. |

## Configuration

- `enchants.yml` — per-enchant `display-name`/`compatible-types`/
  `enabled-worlds`/`disabled-worlds`, and per-level `material`/
  `custom-model-data`/`name`/`lore`/`glow`/`proc-chance`/`success-rate`/
  `ability-value`.
- `runes.yml` — the Lucky Gem's own cosmetics, `lucky-gem-effectiveness`
  per tier, and per-tier `material`/`name`/`lore`/`glow`/`shop-price`/
  `table` (the tier's weighted `(enchant, level)` roll list).
- `gui/enchant-apply.yml` — the Enchant Application GUI's background,
  info icon, live chance-display icon, and confirm/cancel buttons.
- `lang/en_us.yml`'s `rune:`/`enchant:` sections.
