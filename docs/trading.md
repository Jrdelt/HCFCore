# Player trading

`/trade <player>` opens a direct two-player trade after the target accepts
the request. Players must be in the same world and within the configured
3D distance. `/tradetoggle` permanently opts a player out of incoming
requests; `/trade cancel` returns both offers immediately.

## Safety model

- A player may have only one pending request or active trade at once.
- The six-row GUI has separate, 16-slot offer grids. A player cannot click,
  drag, shift-click, drop, or edit the other side's items.
- Items move into the shared trade escrow as soon as they are placed. An item
  offer becomes read-only when that player locks it. Money and XP are not
  tradeable in the current item-only trade system.
- When both sides lock, only the first player to lock can press the final
  accept button.
- If either inventory cannot receive the opposite items, the entire trade is
  cancelled before anything transfers. Disconnects, closing either GUI,
  moving out of range, blacklisted world/gamemode changes, inactivity, and
  plugin shutdown all cancel safely.
- Current escrow snapshots are stored in SQL. Completion/cancellation converts
  both escrow sides into the correct durable recipient claims and writes the
  history row in one transaction before inventory delivery. Startup returns
  unresolved claims from an interrupted trade before new sessions can begin.
- Claim rows use reservation tokens. They are deleted only after their tagged
  inventory copies are present, so full inventories, disconnects, and an
  interrupted acknowledgement can be retried without recreating claim rows.

Right-click a shulker box or barrel in either trade grid to inspect its
contents. The viewer is read-only and returns to the trade on close.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/trade <player>` | `vertex.trade.use` | Request a trade. |
| `/trade accept <player>` | `vertex.trade.use` | Accept a pending request. |
| `/trade cancel` | `vertex.trade.use` | Cancel and refund the current trade. |
| `/tradetoggle` | `vertex.trade.use` | Toggle incoming requests; setting persists. |
| `/trade payouts [key] [paid\|retry]` | `vertex.trade.payouts` | Inspect or reconcile an uncertain legacy money/EXP payout. |
| `/vertex reload` | `vertex.admin` | Reload Vertex configuration, including `traders.yml`. |
| `/tradehistory [player]` | `vertex.trade.staff.history` | Open a player's paginated audit history. |
| `/tradelogs [all\|player]` | `vertex.trade.staff.history` | Open the server-wide or player-filtered audit history. |

Staff can use `vertex.trade.staff.bypassdistance` and
`vertex.trade.staff.bypassblacklist` for testing.

## Configuration

`plugins/Vertex/traders.yml` controls the distance, request/idle/cooldown
timers, GUI materials, and world, gamemode, or material blacklist. Bad
numerical or Material values are logged and replaced with safe defaults; run
`/vertex reload` after editing it.

Trade activity is written to `trade_history` for audit purposes. The staff
history menus query it with SQL pagination rather than loading all records at
once, while
unfinished sessions live only in `trade_escrow`; completion/cancellation moves
them atomically into `trade_claims` before removing the escrow rows.

Old money/EXP credits from builds that allowed currency trading are migrated
atomically into the current payout outbox with deterministic IDs. A payout
whose external result cannot be proven remains reserved for staff review via
`/trade payouts`; it is never guessed or automatically paid twice.
