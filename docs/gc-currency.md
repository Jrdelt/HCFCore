# GC (Gift Card / Credit)

GC is Vertex's self-hosted third currency. Its balance, redeem codes, and audit
trail live in Vertex's database; it does not convert to or from Vault money.
GC persists across normal restarts and is available for Coinflips and Auction
House listings.

## Player wallet

`/gc` shows the current balance, a red **Withdraw** command hint, a green
**Redeem Code** command hint, and the player's transaction logs.

- `/gc withdraw <amount>` atomically turns that GC balance into one single-use
  redeem code. The balance debit, code creation, and audit note are one
  database transaction. The code is printed in copyable chat text.
- `/gc redeem <code>` consumes a code once and credits the current player.
- `/gc` → Logs shows only the player's own history. Withdrawal log entries
  include the generated code in the audit note.

There is no sign or anvil input flow and no GC-to-money conversion.

Code claims have a **3-second per-player cooldown** by default. Invalid,
expired, and already-used codes also count as attempts. While a claim is
processing, another claim from that player is rejected immediately, even
if the cooldown has expired or is disabled. Other players are not blocked
by that player's timer. Code usage and the wallet credit are committed in
one database transaction; the cooldown is a spam limit, not the protection
against two players redeeming the same single-use code.

The timer survives reconnects on the same server but is local to that server
and resets on restart. `/vertex reload` applies the configured delay to new
attempts; an already-running cooldown keeps its original expiry.

Every SQL debit checks sufficient funds, including when another shard has
changed the wallet. Withdrawals also reserve the amount locally while pending.
Wallets use exact nonnegative integers up to `9,223,372,036,854,775,807` GC;
an over-limit credit is rejected, not rounded or clamped after payment. A
rejected redemption leaves the code unused. Existing corrupt wallet rows fail
closed and require staff investigation; they are not silently rewritten.

## GC as a Coinflip/Auction House currency

An auction GC buyer debit commits in the same SQL transaction as the listing
removal, item claim and seller payout entitlement. A joining coinflip player's
GC debit likewise commits with the result and payout entitlement. If the
debit fails, those rewards do not become claimable. GC payout retries use
idempotent operation IDs. The GUI balance is a local projection refreshed on
login and settlement; SQL remains the spending authority.

Public chat guards code-shaped messages against accidental leaks. An
unescaped code is cancelled and the player receives a clickable approval;
approving permits exactly their next chat message for 15 seconds. Prefixing
the code with `\` bypasses the warning intentionally. Previously issued code
formats remain recognized after local reload/restart. Keep code-generation
settings consistent across shards; a format newly introduced on another node
is not pushed to already-running chat guards automatically.

## Staff tools

| Command | Permission | Action |
| --- | --- | --- |
| `/gc admin balance <player>` | `vertex.gc.view` | View another player's balance |
| `/gc admin give\|remove\|set\|zero <player> <amount>` | `vertex.gc.adjust` | Credit / debit / set / reset a balance |
| `/gc admin logs [player] [page]` | `vertex.gc.logs` | Read audit history |
| `/gc redeem create <amount> [uses] [expires-in]` | `vertex.gc.redeem.create` | Create a staff GC code |

All staff balance actions are logged. Keep the adjust and logs permissions
restricted: GC code values and balances are sensitive data.

Staff changes report success only after SQL confirms them. They run
asynchronously; `/gc admin balance` refreshes from SQL before replying. If an
operation cannot be confirmed, inspect the ledger before repeating it.
Expiry supports seconds and `m`/`h`/`d` suffixes, including `1.5h`. By default
the maximum explicit lifetime is 365 days; omitting expiry still means no expiry.

## Large GC transaction audit

When `transaction-audit.enabled` is true, successful GC transactions strictly
above `transaction-audit.minimum-gc` also append to a weekly UTC audit file
under `plugins/Vertex/<log-directory>/`. Holders of
`vertex.transaction.audit` receive a live staff alert. The separate
`vertex.transaction.audit.ip` permission exposes the IP-address portion of
that alert and should be granted only where that access is appropriate.

See [Configuration](configuration.md#large-gc-transaction-audit) for the
full config keys and retention considerations.

## Configuration (`gc.yml`)

| Key | Purpose |
| --- | --- |
| `min-withdraw` / `max-withdraw` | Bounds for `/gc withdraw <amount>`. |
| `redeem-code-length` / `redeem-code-charset` | One-use code generation. |
| `redeem-cooldown-seconds` | Whole seconds, 0–60; default 3. Invalid values warn and fall back to 3. Setting 0 disables only the delay, never the in-progress guard. |
| `max-code-lifetime-seconds` | Maximum explicit expiry; default 31,536,000 (365 days), configurable up to 3,153,600,000. |
| `interop-command` | Optional non-authoritative callback after a GC credit. |
| `log-page-size` | Rows shown per transaction-log page. |
