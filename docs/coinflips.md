# Coinflips

`/cf` (alias `/coinflip`) lets a player host a 50/50 wager — money,
experience levels, GC, or items — against anyone, or a specific player.
Winner takes both sides' stake.

## Hosting one

| Command | Permission | Wagers |
|---|---|---|
| `/cf <amount>` | Open to all | Money |
| `/cf <amount> money` | Open to all | Money (explicit keyword, same as above) |
| `/cf <amount> <player>` | Open to all | Money, only that player can play |
| `/cf <amount> exp` or `/cf <amount> xp` | Open to all | Experience levels |
| `/cf <amount> exp <player>` or `/cf <amount> xp <player>` | Open to all | Experience levels, targeted |
| `/cf <amount> gc` | Open to all | GC balance (see [GC](gc-currency.md#gc-as-a-coinflipauction-house-currency)) |
| `/cf <amount> gc <player>` | Open to all | GC balance, targeted |
| `/cf hand` | Open to all | Items — opens picker GUI |
| `/cf hand <player>` | Open to all | Items, targeted |

Amounts accept the server's normal formatted-number syntax: `10k`, `1.25m`,
`1,000,000`, and the configured suffixes in `number-formatting.yml`.

`exp`/`xp`, `money`, and `gc` all tab-complete right after `<amount>`; a player name
never tab-completes there, only right after an explicit currency keyword
(or as the second word of `/cf hand <player>`) — so a targeted wager
always needs the wager type typed first. Typing a name never changes
*what* you're wagering, only *who* can play it. Vertex records a creation
intent before money or levels are removed; an item-picker wager is written as
durable escrow before activation. Successfully hosting one opens the Active
Coinflips browser right after, so you can see your own listing land.

Once the listing is saved, the server announces its creator and coinflip type
in public chat without exposing the amount or item list. Players can disable only these notices in `/settings` (also
`/preferences`) without hiding other server announcements.

**One coinflip open at a time.** A player who already has an unresolved
coinflip listed can't host a second one — cancel or resolve the first
before hosting again (`coinflip.already-hosting`). Likewise, a player
can't propose an item match on a second coinflip while already waiting on
approval for one elsewhere (`coinflip.already-taking-one`).

## Playing one

Open `/cf` to browse every coinflip you're allowed to join — it
re-renders in place on a short interval (`gui-refresh-interval-ticks`,
1 tick by default) so new listings, cancellations, and pending-match
state show up live for everyone with it open, not just after your own
next click. Left-click a listing to play it:

- **Money/experience/GC**: your matching stake is taken immediately and
  the coin is flipped right there. A GC stake moves through Vertex's own
  self-hosted ledger rather than Vault or vanilla levels — see
  [GC (Gift Card / Credit)](gc-currency.md).
- **Items**: clicking Play opens the same picker GUI the host used —
  choose whatever you're willing to risk (no requirement to match the
  host's value or item count) and confirm. This does **not** flip the
  coin immediately — see [Item wager approval](#item-wager-approval)
  below.

Clicking your own listing does nothing but tell you why
(`coinflip.play-is-host`) — there's no silent no-op. Right-click a
multi-item listing to preview its full contents first. Shift-click your
own listing (or any listing, with `vertex.coinflip.remove`) to cancel it
and get the wager back — see [Admin removal](#admin-removal).

A listing's icon in the browser is the host's own head for a money or
experience wager; for an item wager it's the actual wagered item(s)
instead, cycling through every stack over time
(`item-icon-cycle-ticks`) if there's more than one.

The item picker itself (`/cf hand`, and the one opened by clicking Play
on an item listing) is a compact 3-row GUI: the middle row is 9 open
slots to drag items into (matching `max-item-stacks-per-wager`'s default
of 9), with Confirm (lime dye) and Cancel (red dye) centered on the
bottom row.

## Item wager approval

A host's item wager can bait with something that looks valuable while an
opponent risks real value against it (or the reverse) — there's no way to
verify a fair trade automatically. So an opponent's chosen items are never
just accepted: they're held (persisted, not just kept in memory) and the
host gets a chat prompt with a single clickable **[ Click to Review ]**
link — deliberately not a plain-text item list, since flattening an item
into chat text loses its custom name, lore, and enchants. Clicking it
runs `/cf review <id>`, opening a compact GUI: each side's player head
sits directly above their own row of items — the host's head over the
host's wager, the opponent's head over their proposed items — showing
every item exactly as it really is, not summarized. A lime dye **Accept**
and red dye **Deny** sit centered on the bottom row; **closing the GUI
any other way — Escape, opening something else, disconnecting — counts
as Deny too**, never as "still pending". (`/cf approve <id>` / `/cf deny
<id>` work too, for deciding straight from chat without opening the GUI.)
Only one match can be pending per coinflip at a time — a second player
trying to join sees it's already awaiting a decision.

- **Approve**: the coinflip resolves immediately, exactly like a
  money/experience play — winner takes both sides via the claim stash.
- **Deny**: the opponent's items go to their claim stash (never straight
  into their live inventory, whether they're online or not — the same
  place a win, an expiry, or any other refund in this system lands) and
  the coinflip stays open for someone else to try.
- **No response**: if the host doesn't decide within
  `item-match-approval-timeout-seconds` (default 300 = 5 minutes), the
  match auto-denies and the opponent is refunded the same way.

The host's own listing shows a lore hint (`/cf approve <id>` / `/cf deny
<id>`) while a match is pending on it, and the listing shows a
"someone's waiting" hint to everyone else instead of letting them queue
up a second offer.

## Result animation

When a coinflip resolves, each online participant sees a short
slot-machine-style GUI: a single reel alternates between the host's and
the opponent's head, slowing down each flip, and lands on the winner's
head. The win/lose chat message is
deliberately held back until the reel actually lands — showing it the
instant the coin is flipped would spoil the animation before it's even
opened, so it only fires once the shared animation timer completes. The
result is still server-authoritative — closing the animation or
disconnecting changes neither the winner nor the payout. A disconnected
participant's result is retained and shown when they next join.

## Claiming item payouts

Winning an item coinflip never drops items straight into your inventory —
they go to your personal claim stash, shown by the **Collect Stash**
button in `/cf` (lit up when you have something waiting). Open it and
click **Claim All** to receive everything at once, with any overflow
left in the durable stash until enough inventory space is available. Money and
experience settle through the payout outbox instead — there is no item to
claim for those.

If you win experience while offline (only possible if you're the host and
went offline after posting), it's credited automatically the next time you
join, with a chat notice.

## Self-ban

Struggling with the temptation to keep playing? The **Self Ban** button in
`/cf` (or `/cf ban`) locks you out of hosting *and* playing coinflips for
`self-ban-days` (default 30) once confirmed. This is deliberately not
something staff or you can shortcut: `/cf ban confirm` (within 30 seconds
of running `/cf ban`) is required to actually apply it, and once active,
`/cf unban` only succeeds after the full duration has elapsed. There is no
override.

## Admin removal

`vertex.coinflip.remove` lets staff shift-click any listing in `/cf` (or
run `/cf cancel <id>`) to pull it off the board and refund the host's
wager. This only works on a coinflip that hasn't been played yet — a
resolved outcome is never reversible in-game. If a resolved coinflip needs
investigating (a disputed loss, an item that seems to have vanished),
that's what the audit log is for.

## Staff audit log

Every resolved or cancelled coinflip is written permanently to a database
log — host, opponent, wager, winner, and outcome — independent of the
`coinflips`/`coinflip_claims` tables, which only ever hold what's still
*open* or *unclaimed*. `/cf logs [player] [page]` (needs
`vertex.coinflip.logs`) reads it, newest first, optionally filtered to one
player. `log-retention-days` controls how long entries are kept (0 = never
deleted).

`/cf payouts [key] [paid|retry]` (`vertex.coinflip.payouts`) lists and
reconciles Vault/EXP payouts left uncertain by an interrupted external
acknowledgement. GC payouts use idempotent operation IDs and can safely
recognize an already-applied transaction.

`/cf intents [key] [debited|not-debited]` (`vertex.coinflip.intents`) lists
interrupted wager creations. Staff must verify the external economy/XP debit
before choosing; the decision is permanent, idempotent, and audit logged.

## Storage and persistence

Coinflips, creation intents, claims, payout state, self-bans, and the audit log all live in Vertex's own
database (SQLite by default, or MySQL) — not on any item, not in memory
only. A crash or restart never loses an open coinflip, a pending item
payout, or a self-ban: everything reloads exactly as it was. `/vertex
storage local|mysql` (see [Configuration](configuration.md#database))
carries all of it over when switching backends.

A predetermined transaction remains represented by durable escrow/payout rows
until its handoff is acknowledged. Vertex never blindly retries an uncertain
Vault/EXP credit, because those external APIs cannot accept an idempotency key.

## Configuration reference (`coinflips.yml`)

| Key | Purpose |
|---|---|
| `enabled` | Master on/off switch |
| `min-money-wager` / `max-money-wager` | Bounds for `/cf <amount>` |
| `min-exp-wager` / `max-exp-wager` | Bounds (in levels) for `/cf <amount> exp` |
| `min-gc-wager` / `max-gc-wager` | Bounds for `/cf <amount> gc` — see [GC (Gift Card / Credit)](gc-currency.md) |
| `max-item-stacks-per-wager` | Cap on distinct item stacks per side of an item coinflip -- the picker GUI itself only has 9 slots, so a value above 9 has no effect |
| `item-match-approval-timeout-seconds` | How long an item wager waits for the host's approval before auto-denying and refunding (minimum 30) |
| `house-fee-percent` | Percentage of the *loser's* wager the winner doesn't get back (destroyed, not paid to anyone); 0 by default |
| `self-ban-days` | How long a self-ban lasts once confirmed |
| `item-icon-cycle-ticks` | How often a multi-item listing's icon rotates |
| `gui-refresh-interval-ticks` | How often the open Active Coinflips browser re-renders in place for everyone viewing it; 1 tick (the fastest a server can meaningfully update) by default |
| `log-retention-days` | How long resolved/cancelled log entries are kept; 0 = forever |

`broadcast-results` (server-wide chat announcement of who won what) is
also configurable, on by default.

After changing this file, run `/vertex reload` or restart the server.

## Permissions

| Permission | Grants |
|---|---|
| `vertex.coinflip.remove` | Cancel any player's active coinflip |
| `vertex.coinflip.logs` | Read the staff audit log |
| `vertex.coinflip.payouts` | Inspect and reconcile uncertain Vault/EXP payouts |
| `vertex.coinflip.intents` | Inspect and reconcile interrupted wager creations |

Hosting, playing, browsing, claiming, and self-banning are open to every
player — there's no permission node gating normal use.
