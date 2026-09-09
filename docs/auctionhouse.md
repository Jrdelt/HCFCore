# Auction House

`/ah` (alias `/auctionhouse`) is a buy-it-now marketplace for any item a
player is holding. List it at a fixed price; the first player to buy it
gets it immediately — no bidding, no waiting for an auction to end.

## Trading

| Command | Effect |
|---|---|
| `/ah` | Opens the browser |
| `/ah sell <price> [money\|exp\|xp\|gc]` | Lists the item in your main hand at that price. "money" is the default if omitted; `xp` aliases `exp` |
| `/ah cancel <id>` | Cancels your own listing (or any listing, with `vertex.auction.remove`) and returns the item |
| `/ah collect` | Opens the claim GUI for items waiting on you |
| `/ah logs [player] [page]` | Staff audit log (`vertex.auction.logs`) |

Prices accept the same formatted-number syntax as every other Vertex economy
surface: `/ah sell 10k money`, `/ah sell 1.25m exp`, and `/ah sell 1,000,000`.

In the browser: **left-click** a listing to buy it; **shift-click** your
own listing (or anyone's, with `vertex.auction.remove`) to cancel it and
get the item back; **right-click** toggles it on your
[watchlist](#watchlist-and-your-auction-page). Row 0 also has:

- The **Collection Box** shortcut (opens `/ah collect`).
- A **Sort** button (hopper) — click to cycle Date Posted → Alphabetical →
  Price, shift-click to flip the direction (oldest/lowest first vs.
  newest/highest first).
- A **Currency** filter (paper) — click to cycle All → Money →
  Experience → GC → All, narrowing the grid to just that currency.
- Your own head, opening [Your Auction Page](#watchlist-and-your-auction-page).

`/ah sell` takes the item straight out of your hand the moment you list
it — if the listing is rejected (bad price, too many active listings,
can't afford the fee), it's handed straight back rather than left in
limbo.

## Currency

A listing is priced in **money** (the default), **experience levels**,
or **GC**. Buying an experience-priced listing takes levels from the
buyer and credits levels to the seller instead of touching Vault at all
— if the seller is offline when it sells, the levels wait for their next
login (persisted, not held only in memory, the same as every other
in-flight payout in this plugin). A GC-priced listing works the same way
but through Vertex's own self-hosted GC ledger instead of Vault or vanilla
levels — see [GC (Gift Card / Credit)](gc-currency.md#gc-as-a-coinflipauction-house-currency)
— and, since GC is a persistent database balance rather than something
tied to being online, a GC sale credits the seller immediately regardless
of whether they're connected.

## Watchlist and Your Auction Page

Right-clicking a listing adds it to your **watchlist** (right-click again
to remove it) — a personal, persisted bookmark list, separate from
actually owning or bidding on anything. Your own head in the browser
opens **Your Auction Page**, a hub linking to:

- **Active Listings** — your own current, unsold listings.
- **Expired Items** — a read-only look at listings of yours that expired
  unsold (the items themselves are already back in your
  [claim stash](#claim-stash), same as always — this page is just a
  filtered view of *when that happened*, pulled from your own history).
- **Collection Box** — identical to `/ah collect`.
- **Watchlist** — every listing you're currently watching that's still
  active (a sold, expired, or cancelled listing quietly drops off
  everyone's watchlist, so it never shows something you can no longer
  act on).
- **Auction History** — every resolved listing you were the seller or
  buyer of, read-only. This is deliberately **self-filtered only** — it
  never shows another player's activity. The full cross-player staff
  audit log stays behind `vertex.auction.logs` and `/ah logs`, exactly
  as before; nothing about this page changes who can see that.

## Claim stash

If a listing sells, expires, or gets cancelled while the recipient is
offline (or their inventory is full), the item doesn't try to force its
way in — it queues in a claim stash instead, collected any time via
`/ah collect` or the claim button in the browse GUI. A money sale settles
instantly through Vault, whether the seller is online or not; an
experience sale credits the seller's levels immediately if they're
online, or waits (persisted) for their next login if not — either way, a
sale never needs the seller to be logged in.

## Expiry

Every listing expires `listing-duration-hours` after it was created if
nobody buys it. An expired listing is swept automatically (on the
interval set by `sweep-interval-ticks`) and the item goes back to the
seller through the same claim stash as everything else — an expiry never
destroys an item.

## Fees

Two independent, optional percentages, both disabled (`0.0`) by default:

- `listing-fee-percent` — taken from the seller **upfront**, when the
  listing is created, whether or not it ever sells. If the seller can't
  afford it, the listing is rejected and nothing is taken.
- `sale-tax-percent` — taken out of the seller's proceeds only when the
  listing actually **sells**.

Both are simply deleted, not paid to anyone (there's no server "cut"
destination) — set to `0.0` to disable either one entirely.

## Persistence

Every active listing, pending claim, watchlist entry, pending offline
experience payout, and log entry lives in Vertex's own database, not on
the item or in memory — a restart never loses a listing (whether it's
priced in money or experience), and `/vertex storage local|mysql`
carries all of it over like everything else this plugin stores.

## Configuration reference (`ah.yml`)

| Key | Purpose |
|---|---|
| `enabled` | Master on/off switch |
| `min-price` / `max-price` | Bounds on a listing's buy-it-now price |
| `max-active-listings-per-player` | Cap on one player's simultaneous unsold listings |
| `listing-duration-hours` | How long an unsold listing stays up before it expires |
| `listing-fee-percent` | Taken from the seller upfront, regardless of whether it sells (0 disables it) |
| `sale-tax-percent` | Taken from the seller's proceeds on a sale (0 disables it) |
| `log-retention-days` | How long a resolved (sold/expired/cancelled) entry stays in `/ah logs` before being pruned; 0 means never |
| `sweep-interval-ticks` | How often expired listings are swept back to their sellers |

After changing this file, run `/vertex reload` (the expiry-sweep interval
also reschedules live if you change `sweep-interval-ticks`) or restart
the server.
