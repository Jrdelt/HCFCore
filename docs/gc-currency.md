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

Public chat guards code-shaped messages against accidental leaks. An
unescaped code is cancelled and the player receives a clickable approval;
approving permits exactly their next chat message for 15 seconds. Prefixing
the code with `\` bypasses the warning intentionally.

## Staff tools

| Command | Permission | Action |
| --- | --- | --- |
| `/gc admin balance <player>` | `vertex.gc.view` | View another player's balance |
| `/gc admin give\|remove\|set\|zero <player> <amount>` | `vertex.gc.adjust` | Credit / debit / set / reset a balance |
| `/gc admin logs [player] [page]` | `vertex.gc.logs` | Read audit history |
| `/gc redeem create <amount> [uses] [expires-in]` | `vertex.gc.redeem.create` | Create a staff GC code |

All staff balance actions are logged. Keep the adjust and logs permissions
restricted: GC code values and balances are sensitive data.

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
| `interop-command` | Optional non-authoritative callback after a GC credit. |
| `log-page-size` | Rows shown per transaction-log page. |
