# GC (Gift Card / Credit)

GC is a third currency alongside money and experience. It is **100%
self-hosted** — Vertex's own database (the `gc_balances`, `gc_log`, and
`gc_redeem_codes` tables) is the sole balance authority. It never reads
from, writes to, or defers to Tebex, PlaceholderAPI, or any other
external plugin for the balance itself. A new player's GC balance is `0`
— nothing seeds it.

Unlike Coinflip/Auction House state, GC is a persistent wallet: it is
never wiped, reset, or even snapshotted by the season-reset system
covered elsewhere, the same way Vault money never is.

## The wallet (`/gc`)

`/gc` opens a small GUI (`gui/gc.yml`) showing your balance and four
buttons:

| Button | What it does |
|---|---|
| Balance | Informational only — your current GC balance. |
| Deposit | Converts Vault money into GC. |
| Withdraw | Converts GC back into Vault money. |
| Redeem | A hint pointing you at `/gc redeem <code>` — codes are typed in chat, not picked from a GUI. |
| Logs | Opens your own paginated transaction history (`GcLogMenu`) — see [Transaction log](#transaction-log) below. |

Every balance mutation anywhere in GC — deposits, withdrawals, staff
adjustments, redeemed codes, Coinflip wagers, Auction House
listings/sales — follows the same debit → persist → compensate-on-failure
shape: the in-memory balance changes immediately (so the next check
sees it right away), the database write is queued right behind it on a
per-player queue, and if that write is ever the one in a hundred-thousand
that fails, the in-memory effect is reversed and (for the deposit/
withdraw flow specifically) whatever was already taken from Vault is
handed back. Nothing is ever left half-applied.

## Deposit & Withdraw: the shared sign

Unlike every other amount prompt in Vertex (chat, for faction bank
deposits and Chunk Collector withdrawals), typing a GC deposit or
withdrawal amount uses a **physical sign** — this is a deliberately new
kind of interaction surface, with no prior `SignChangeEvent` precedent
anywhere else in this codebase.

### Setup

An admin points at any block (within 8 blocks, `vertex.gc.adjust`) and
runs:

```
/gc setsigninput
```

That exact block becomes the server's one shared "GC input sign." The
location is written into `gc.yml`'s `sign-input` section and reused
forever after — there's no need to run the command again unless you want
to move it. The block itself can be anything (a fence post, a floor
tile, air) the rest of the time; it only becomes a sign for the few
seconds someone is actively typing an amount.

### Flow

1. A player clicks Deposit or Withdraw in `/gc`.
2. Vertex saves the configured block's exact current state, converts it
   to a sign, and opens the sign-editing screen for that player only
   (`Sign#setAllowedEditorUniqueId`).
3. The player types a whole number on the sign's first line and clicks
   Done.
4. Vertex cancels the resulting `SignChangeEvent`, parses the amount
   (the same `Numbers.parseLongPositive` grammar as everywhere else —
   `100k`, `1.5m`, `1,000,000` all work), restores the original block
   exactly as it was, and reopens `/gc` with the result.

### Only one player at a time

The sign is a **lock, not a queue**. If someone else is already using it,
the next player is told to try again shortly rather than being queued up
behind them.

### Timeout

Pressing Escape on a sign-editing screen fires no event at all in
vanilla Minecraft — there is no way to detect "the player backed out."
To make sure the block doesn't stay a live sign forever, a timeout task
(`gc.yml`'s `sign-prompt-timeout-seconds`, default 45) force-restores the
block and releases the lock if nobody finishes in time. A disconnect
mid-edit releases the lock immediately, without waiting for the timeout.

> **Not yet verified against a real client.** This sign flow (like any
> genuinely new interaction surface) has been reasoned through carefully
> but not smoke-tested on a live server — test the full Deposit/Withdraw
> loop, including an intentional Escape-and-walk-away and a disconnect
> mid-edit, before relying on it in production.

## Redeem codes

Staff generate a code, players consume it once:

```
/gc redeem create <amount> [uses] [expires-in]
/gc redeem <code>
```

Defaults: **single-use**, a random **12-character** alphanumeric code
(unambiguous charset — no `0`/`O`/`1`/`I`), **no expiry** unless staff
sets one at creation (`expires-in` accepts a bare number of seconds or a
shorthand like `7d`/`12h`/`30m`).

Redeeming is atomic against the database itself, not just Vertex's own
in-memory state — two players racing the same single-use code cannot
both win it, because the code's remaining-uses counter is decremented
with a conditional SQL update that only one of them can win.

## Transaction log

Every mutation is written once, permanently, to `gc_log`: who caused it
(`actor`, null for a system-driven change like a Coinflip payout), whose
balance changed (`target`), what kind of change it was, the amount, the
resulting balance, an optional note (a redeem code, a staff reason), and
when.

- **Players** see only their own history, via `/gc`'s Logs button
  (paginated, `log-page-size` per page).
- **Staff** with `vertex.gc.logs` can read anyone's (or everyone's) via
  `/gc admin logs [player] [page]`.

## Staff tools

| Action | Command | What it does |
|---|---|---|
| View a balance | `/gc admin balance <player>` | Read-only. |
| Credit | `/gc admin give <player> <amount>` | Adds to the balance. |
| Debit | `/gc admin remove <player> <amount>` | Subtracts — refused if the player doesn't have enough. |
| Overwrite | `/gc admin set <player> <amount>` | Sets the balance to exactly this value, regardless of what it was. |
| Reset | `/gc admin zero <player>` | Shorthand for `set ... 0`. |
| Audit | `/gc admin logs [player] [page]` | Reads the permanent log. |

`give`/`remove`/`set`/`zero` all sit behind `vertex.gc.adjust` — a
high-risk permission, since it directly moves a real-money-equivalent
currency. Mirroring the `vertex.staff.punish.bypasscombat` precedent
(see [Staff Tools](staff-tools.md)), **every attempt is written to the
server console as a loud warning line, whether or not it was actually
permitted** — the staff member's name, the action, the target, and the
amount. There is no way to attempt one of these silently.

## GC as a Coinflip/Auction House currency

GC is a full third option everywhere Coinflip and the Auction House
already support money and experience:

- `/cf <amount> gc [player]` — hosts a GC coinflip.
- `/ah sell <price> gc` — lists an item priced in GC; the Auction House
  browser's currency filter cycles All → Money → Experience → GC.

Every GC wager/listing debit, refund, and payout goes through the exact
same ledger primitives described above — a Coinflip loss or an Auction
House purchase is not a special case internally, it's the same
debit/credit calls the wallet GUI itself uses, just with a different
audit-log action (`COINFLIP_WAGER`, `AUCTION_SALE`, etc. — see
`GcAction`) so the transaction log reads clearly.

If GC somehow isn't available yet (a very early startup race), both
systems refuse the GC action cleanly with a "no economy" style message
rather than throwing — GC is never *required* for Coinflip or the
Auction House to work; money and experience wagers are completely
unaffected either way.

## The interop hook (optional)

`gc.yml`'s `interop-command` is a blank-by-default, config-gated console
command fired whenever a player's own action credits their balance (a
deposit or a redeemed code) — for example, to notify a future Tebex
webstore confirmation, or relay the event to a Discord webhook bridge.
It mirrors the exact reward-command idiom `CaptureEventManager` already
uses for KOTH/Outpost rewards: `{player}`/`{amount}`/`{reason}` tokens
are substituted into the configured template, then dispatched as console
commands.

This hook is **never load-bearing** for the ledger. GC's balance is
fully correct and fully functional with `interop-command` left blank (the
default) — the hook exists purely to let an admin wire up an external
notification, not to make the currency itself work.

## Commands

| Command | Permission | Notes |
|---|---|---|
| `/gc` | `vertex.gc.use` (default: true) | Opens the wallet GUI. |
| `/gc redeem <code>` | `vertex.gc.use` | Consumes a redeem code. |
| `/gc redeem create <amount> [uses] [expires-in]` | `vertex.gc.redeem.create` (default: op) | Generates a new code. |
| `/gc admin balance <player>` | `vertex.gc.view` (default: op) | Views another player's balance. |
| `/gc admin give\|remove\|set\|zero <player> <amount>` | `vertex.gc.adjust` (default: op) | Mutates a balance directly. Every attempt is logged loudly — see [Staff tools](#staff-tools). |
| `/gc admin logs [player] [page]` | `vertex.gc.logs` (default: op) | Reads the permanent audit log. |
| `/gc setsigninput` | `vertex.gc.adjust` | Points the shared deposit/withdraw sign at the block you're looking at. |

## Configuration (`gc.yml`)

| Key | Default | Meaning |
|---|---|---|
| `min-deposit` / `max-deposit` | `1` / `1000000000` | Bounds on a single Deposit. |
| `min-withdraw` / `max-withdraw` | `1` / `1000000000` | Bounds on a single Withdraw. |
| `redeem-code-length` | `12` | Character length of a generated redeem code. |
| `redeem-code-charset` | unambiguous alphanumeric | Character set generated codes draw from. |
| `sign-prompt-timeout-seconds` | `45` | How long an opened sign prompt waits before being force-restored. |
| `sign-input.world/x/y/z` | blank | The shared sign-input block, set via `/gc setsigninput`. |
| `interop-command` | `""` (blank = disabled) | Optional console command fired on a GC credit — see [The interop hook](#the-interop-hook-optional). |
| `log-page-size` | `8` | Rows per page in both the player Logs GUI and `/gc admin logs`. |

Coinflip's `min-gc-wager`/`max-gc-wager` live in `coinflips.yml`
alongside its money/experience bounds, the same way every other
per-currency Coinflip range is configured.

See [Commands & Permissions](commands-and-permissions.md) for GC
alongside every other command, and [Coinflips](coinflips.md) /
[Auction House](auctionhouse.md) for how a GC wager or listing behaves
end to end in each system.
