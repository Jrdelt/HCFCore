# Shop

`/shop` is a categorized market. Every item has a **dynamic** price:
buying it pushes the price up, selling it pushes the price down, and it
drifts back toward its base price on its own over time.

## Trading

`/shop` opens a **one-row category picker** — Building Blocks, Decorative,
Raiding Materials, Spawners and Mob Drops, Minerals, Farming and Food, and
Miscellaneous by default (configurable, up to 9 categories since it's a
single row). Clicking a category opens its own paginated buy/sell browser,
with a **Back** button (top-left) to return to the picker.

Spawn eggs and mob drops share the single **Spawners & Mob Drops** category,
and buyable spawner blocks are reached from a button in that category's top
row rather than a separate picker entry — so everything spawner-related is
in one place instead of split across the menu. Spawner blocks are priced per
mob type rather than per material, which is why they open their own menu
instead of appearing as ordinary entries.

You can also trade directly by command, regardless of which category an
item belongs to:

| Command | Effect |
|---|---|
| `/shop` | Opens the category picker |
| `/shop buy <item> [amount]` | Buys at the current price |
| `/shop sell <item> [amount]` | Sells at the current price |

In a category's GUI: left-click buys one (or `default-buy-amount`),
shift-left-click buys a full stack; right-click sells one, shift-right-click
sells a full stack.

## How the price moves

Each item tracks its own **net-volume** — units bought minus units sold
since it last fully recovered. The first `price-change-threshold-units`
of that volume (50 by default) move the price **not at all** — a handful
of trades leaves the price exactly where it was; only volume beyond that
threshold actually counts. Once past it, the buy price is:

```
base-price × (1 + price-change-per-unit) ^ (net-volume beyond the threshold)
```

clamped between `min-price-multiplier` and `max-price-multiplier` of the
base price. The sell price is always a fraction (`sell-price-ratio`) of
the *current* buy price, not the base — so the spread moves with the
market. Buying in bulk costs progressively more per unit within that one
purchase (and selling in bulk pays progressively less), rather than one
flat price for the whole stack, so there's no trick to buying/selling
one giant batch versus many small ones — a single huge batch trade can
still cross the dead-zone threshold within itself.

An item's icon shows a small arrow right after both the Buy and Sell
price whenever the current price has actually moved away from base: a
**red ▲** if it's higher than base (worse to buy, better to sell), a
**green ▼** if it's lower than base (better to buy, worse to sell).
Nothing is shown while it's sitting exactly at base (inside the dead
zone, or freshly recovered).

Every `decay-interval-ticks`, every item's net-volume shrinks by
`decay-fraction` back toward zero — the market recovers on its own
whether or not anyone's trading, so an item that was bought out yesterday
isn't still expensive a week later.

## Persistence

Every item's net-volume lives in Vertex's own database, not in memory —
a restart never resets the market back to base price; it picks up exactly
where it left off. `/vertex storage local|mysql` carries this table over
like everything else.

## Categories (`shop.yml`)

Every top-level key under `categories:` is one icon in the `/shop`
picker, in the order they're defined (max 9 — it's a single row; any
beyond the 9th are ignored with a startup warning). Each category has:

| Key | Purpose |
|---|---|
| `display-name` | Shown in the picker and as that category's GUI title |
| `icon` | The Material shown for this category in the picker |
| `items.<MATERIAL>.base-price` | Each tradeable item's price at equilibrium |

A material can only belong to **one** category — if it's listed twice,
the second listing is ignored (with a startup warning) rather than
silently overwriting the first. Add or remove items by editing a
category's `items` map, or add a whole new category by adding another
key under `categories:` (up to the 9-category cap). `/shop buy|sell
<item>` works regardless of which category an item is filed under.

One extra icon appears after your `shop.yml` categories (still within
the 9-slot cap) — **Spawners**. It isn't a `shop.yml` category and
doesn't count toward the cap accounting above; it opens a separate
catalog priced per mob type from `spawners.yml` instead of the
Material/dynamic-price model every other category uses, since a
spawner's mob type isn't representable as a single `Material`. See
[Spawners & Collectors](spawners-and-collectors.md) for its pricing and
placement rules. Only shown if at least one mob type is configured.

## Other configuration (`shop.yml`)

| Key | Purpose |
|---|---|
| `enabled` | Master on/off switch |
| `price-change-per-unit` | How strongly one unit bought/sold moves the price, once past the dead zone |
| `price-change-threshold-units` | Net volume (in either direction) that moves the price not at all before it starts actually changing |
| `min-price-multiplier` / `max-price-multiplier` | How far the price can drift from base, in either direction |
| `sell-price-ratio` | Sell price as a fraction of the current buy price |
| `decay-interval-ticks` / `decay-fraction` | How often, and how much, prices recover toward base on their own |
| `default-buy-amount` | Amount bought/sold per plain (non-shift) click |

After changing this file, run `/vertex reload` (the price-recovery
interval also reschedules live if you change `decay-interval-ticks`) or
restart the server.
