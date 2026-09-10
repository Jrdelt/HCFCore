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

## Staff tools

| Action | Command | Permission |
| --- | --- | --- |
| View | `/gc admin balance <player>` | `vertex.gc.view` |
| Credit/debit/set/reset | `/gc admin give\|remove\|set\|zero <player> <amount>` | `vertex.gc.adjust` |
| Read audit history | `/gc admin logs [player] [page]` | `vertex.gc.logs` |
| Create a staff code | `/gc redeem create <amount> [uses] [expires-in]` | `vertex.gc.redeem.create` |

All staff balance actions are logged. Keep the adjust and logs permissions
restricted: GC code values and balances are sensitive data.

## Configuration (`gc.yml`)

| Key | Purpose |
| --- | --- |
| `min-withdraw` / `max-withdraw` | Bounds for `/gc withdraw <amount>`. |
| `redeem-code-length` / `redeem-code-charset` | One-use code generation. |
| `interop-command` | Optional non-authoritative callback after a GC credit. |
| `log-page-size` | Rows shown per transaction-log page. |
