# Dupe investigation

Vertex runs a staff-only, always-on scanner that watches for one item
existing in two places at once — the defining signature of a duplication
exploit — and opens a persistent case for staff to review. It never
confiscates, deletes, or otherwise touches the items it finds; it only
records evidence and alerts staff.

This is also the shared "anti-dupe framework" other item-tagging features
are expected to reuse rather than build their own detector on top of: any
future feature that hands a player a physical, non-stackable item (Custom
Enchantments' Runes, for example) should route its own duplicate detection
through this system instead of inventing a second one.

## How detection works

Every unstacked item Vertex decides to track (see [Tracked items](#tracked-items)
below) gets a random, hidden instance ID the first time it's seen. That ID
lives only in the item's persistent data — **never in its lore, name, or
anywhere else a player can see it.**

From then on, Vertex periodically takes a snapshot of where every tracked
ID currently is:

- every online player's main inventory and ender chest, whenever they
  join, pick something up, drop something, or open/close/click/drag an
  inventory;
- every loaded chunk's tile entities (chests, etc.) and dropped items,
  swept in small batches over multiple ticks so a large loaded area is
  never scanned all at once and a chunk is never force-loaded just to
  check it.

If the same instance ID is ever observed in more than one place in the
same snapshot — two different inventories, an inventory and a chest, two
copies sitting in the same open chest — that is proof a duplication
happened, and a case opens automatically. A case's fingerprint (identity +
every observed location, sorted) is unique in the database, so the exact
same evidence can never open two cases even if the scan runs twice in a
row before the first case is reviewed.

### Tracked items

Configured in `dupes.yml`:

```yaml
enabled: true
scan-interval-ticks: 100
loaded-chunks-per-pass: 8
staff-list-page-size: 10
tracked-materials:
  - NETHERITE_HELMET
  - NETHERITE_SWORD
  # ...
```

Any material listed under `tracked-materials` is tracked automatically.
On top of that, a handful of Vertex's own high-value items are always
tracked regardless of material, because they carry their own recognizable
marker: Wands, Blueprint templates, Chunk Collectors, and spawner items.

## Case lifecycle

A case starts `OPEN` the moment the scanner detects it, and ends in
exactly one of three terminal states — a case can never be reopened, and
two staff members racing to close the same case can never both "win" (the
database update is guarded with `WHERE status = 'OPEN'`, so only the first
one actually changes anything):

| Status | Meaning |
|---|---|
| `RESOLVED` | Reviewed and handled; not a resubmission of the automatic detector's evidence. |
| `DISMISSED` | Reviewed and judged a false positive (e.g. a legitimate item transfer race, not a real duplicate). |
| `CONFIRMED` | Reviewed and confirmed as a genuine duplicate. |

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/dupe inspect list [page]` | `vertex.dupe.inspect` | Lists open cases, oldest first, paginated. |
| `/dupe inspect <id>` | `vertex.dupe.inspect` | Shows one case's full detail: item, holder, every observed location, and (once closed) who closed it and why. |
| `/dupe resolve <id> [reason]` | `vertex.dupe.resolve` | Closes a case as `RESOLVED`. |
| `/dupe dismiss <id> [reason]` | `vertex.dupe.resolve` | Closes a case as `DISMISSED` (false positive). |
| `/dupe confirm <id> [reason]` | `vertex.dupe.resolve` | Closes a case as `CONFIRMED` (genuine duplicate). |

**Every resolve, dismiss, or confirm attempt is written to the console
log — whether or not the sender actually had permission.** A denied
attempt to close a dupe case is exactly the kind of thing an investigation
later needs to see, the same reasoning behind the punishment
combat-bypass log and the GC admin-command log.

Staff with `vertex.dupe.alert` also get:

- a live chat alert the instant a new case opens;
- a one-line summary on join if any cases are still open (`/dupe inspect
  list` to see them).

## What players never see

Nothing about a dupe case — the instance ID, the case ID, the fact that an
item is even being tracked — is ever exposed to a normal player. No item
ever gets custom lore or a renamed display name from this system; the
instance ID exists exclusively as persistent-data-container metadata, and
players never receive a message, GUI, or item hinting that the framework
exists. `DupeManager` also never confiscates or deletes an item — closing
a case is purely a record-keeping action.

## Storage

Cases live in one table, `dupe_cases`, following the same
per-connection-per-statement / dialect-aware `CREATE TABLE IF NOT EXISTS`
convention as the rest of Vertex's storage classes (see `CoinflipStorage`
for the fullest example of that shape). It's included in
`StorageMigrator`, so switching between SQLite and MySQL carries every
case (open or closed) across with it.

## The shared item-identity utility

The instance-ID mechanism above is implemented once, generically, in
`me.vertex.core.item.TrackedItemIds` — a generalization of the
per-item instance-ID pattern `BackpackManager` already used to keep two
otherwise-identical empty Backpacks from stacking. It exposes:

- `ensureInstanceId(ItemStack, ItemKind)` — assigns a random instance ID
  and an `ItemKind` tag exactly once (a `PersistentDataContainer.has()`
  guard means calling it again on an already-tagged item is a no-op);
- `instanceId(ItemStack)` / `kind(ItemStack)` — read back what was
  assigned, if anything;
- `isSameInstance(ItemStack, ItemStack)` — true only when both stacks
  carry the same instance ID.

`ItemKind` started as a placeholder enum (`GENERIC` only, nothing tagged
with it). Custom Enchantments is the first real consumer: every physical
Rune (`ItemKind.RUNE`) and physical enchant item (`ItemKind.ENCHANTMENT_ITEM`)
is tagged at creation, and every target item is tagged
(`ItemKind.ENCHANTED_ITEM`) the moment it first receives a successful
application — see [Custom Enchantments](custom-enchantments.md) for the
full mechanics. `DupeManager#shouldTrack` treats any item carrying a real
(non-`GENERIC`) `ItemKind` as automatically worth tracking, which is the
entire integration a new feature needs — no second detection system.

`DupeManager` still keeps its own, separate PDC key for the actual
duplicate-detection identity it assigns and compares (distinct from
`TrackedItemIds`' own instance-ID key); an item only needs to carry a real
`ItemKind` to become eligible for that identity, it doesn't need to share
the same underlying key.

A cautionary precedent shaped `TrackedItemIds`' design: a previous bug let
a Chunk Collector's mob-drop marker leak onto every dropped item, which
broke `WandManager`'s plain-item check (it exempts specific marker keys
via an explicit allowlist, not the whole Vertex namespace, precisely so
one feature's marker can never make an unrelated feature misbehave).
`TrackedItemIds`' instance-ID key is not added to that allowlist in this
phase, since nothing is tagged with it yet — whichever feature starts
tagging real items with it later must decide for itself whether its
`ItemKind` needs that same treatment.
