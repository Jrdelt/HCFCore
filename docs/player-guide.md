# Player Guide

Everything you can do on a Vertex server, grouped by what you're trying to
achieve. No staff commands appear here — if a command isn't listed, you
probably can't run it.

Most of these open a GUI. Hover an item to read its lore; it usually tells
you what a click does.

---

## Quick reference

| Command | Permission | What it does |
|---|---|---|
| `/kits` · `/kit <name>` | Open to all | Browse and claim kits |
| `/abilities` · `/cooldowns` | Open to all | Browse ability items, check your cooldowns |
| `/tags` | Open to all (some are permission-locked) | Browse and equip cosmetic chat tags |
| `/shop` | Open to all | Buy and sell at live market prices |
| `/ah` | Open to all | Auction House: buy, sell, collect |
| `/cf` | Open to all | Coinflip another player for money, XP, GC, or items |
| `/gc` | `vertex.gc.use` | Open your GC wallet, withdraw a code, or redeem a code |
| `/trade <player>` | `vertex.trade.use` | Secure face-to-face trade |
| `/tradetoggle` | `vertex.trade.use` | Stop receiving trade requests |
| `/mines` | Open to all | Mining world status: ores, KOTH, Hot Zones |
| `/events` | Open to all | View active Mine KOTH and Hot Zone events |
| `/haven` · `/riftlands` · `/zones` | `vertex.zones.use` | Enter farming zones or view zone progress |
| `/boosters` | Open to all | Every bonus currently applying to you |
| `/filter add <material>` | Open to all | Choose what your Backpack throws away |
| `/runes` · `/ce` | Open to all | Browse and buy Custom Enchantment Runes |
| `/koth focus` · `/outpost focus` | Open to all | Track an active event with a bossbar |
| `/f rally` · `/frally` | Faction role / `/f permissions` | Set a faction rally point |
| `/f bank` · `/f upgrades` | Faction role / `/f permissions` | Faction money/XP/TNT bank and upgrades |
| `/tntfill <radius> <amount> bank\|inventory` | `vertex.tntfill.use` | Bulk-fill dispensers in your claim |
| `/language [code]` | Open to all | Change your language |
| `/nextreboot` | Open to all | When the next restart is |

Numbers accept shorthand almost everywhere: `10k`, `1.5m`, `2b`.

---

## Kits & abilities

`/kits` opens the kit browser — left-click to claim, right-click to preview.
`/kit <name>` claims directly. Kits have cooldowns and some have a cost.

`/abilities` browses the special ability items. `/cooldowns` shows every
cooldown you currently have, including vanilla item cooldowns.

See [Kits & Abilities](kits-and-abilities.md).

## Making money

### `/shop`

Categorised buying and selling at **live prices**. Prices move with what the
whole server buys and sells, so selling a lot of one thing pushes its price
down — and it recovers over time. The Buy and Sell lines show whether an item
is currently above or below its usual price.

`/shop buy <item> [amount]` and `/shop sell <item> [amount]` skip the GUI.

### `/ah` — Auction House

Buy-it-now marketplace. `/ah sell <price> [money|exp|gc]` lists whatever you're
holding; the first person to buy it gets it in their Collection Box.

In the browser: **left-click** buys, **shift-click** cancels your own
listing, **right-click** adds it to your watchlist. Your own head opens
*Your Auction Page* — active listings, expired items, collection box,
watchlist, and history.

Bought, cancelled, and expired listing items wait in the **collection box**
(`/ah collect`) before delivery. Items are never dropped because an inventory
is full.

See [Auction House](auctionhouse.md).

### Sell Wands

Left-click a **chest** or **Chunk Collector** with a Sell Wand to sell its
contents straight to your balance. Each wand has limited uses, shown in its
lore.

Selling runs through the same live market one item at a time, so emptying a
full chest earns exactly what selling it by hand would — a container is not
a shortcut around falling prices.

**Never sold:** anything with a custom name, enchantment, or model — your
gear is safe — plus anything the shop doesn't trade. Those are left in the
container untouched.

See [Wands](wands.md).

## Gambling

`/cf` opens the Coinflip browser. To host:

```
/cf <amount>          money
/cf <amount> exp      experience levels
/cf hand              wager the items in your hand
```

Add a player name to aim it at one person. Amounts take shorthand (`/cf 10k`).

Your wager is taken **when you host**, never merely reserved. For item
coinflips the host reviews the opponent's offer before it runs, so nobody can
bait an expensive wager with junk.

Both players watch the same animation and neither result is revealed until it
finishes. **If you disconnect mid-animation the coinflip still completes** —
your result is stored and sent to you when you log back in. Disconnecting
never cancels, rerolls, or duplicates anything.

`/cf ban` self-excludes you from coinflips. It requires confirmation and
**cannot be lifted early**.

See [Coinflips](coinflips.md).

## Notification preferences

Use `/settings` (or `/preferences`) to choose which optional server-wide
announcements you want to receive. Your choices persist between sessions.

## Trading

`/trade <player>` opens a secure two-sided item trade, with both sides having
to confirm. `/tradetoggle` stops incoming
requests.

See [Player Trading](trading.md).

## Backpacks

A Backpack goes in your **offhand** and auto-collects what you mine or farm,
with a drop bonus that grows as you upgrade it.

`/filter add <material>` adds a material your Backpack **throws away** —
useful for cobblestone while mining. Use `/filter remove <material>` to keep
it again, `/filter clear` to empty the list, and `/filter list` to view it.

See [Backpacks](backpacks.md).

## Mining worlds

`/mines` shows both mining worlds at a glance:

- **Stonewake** — coal, iron, redstone. PvP only inside the KOTH zone.
- **Bloodvein** — gold, lapis, diamond, emerald, and rare Netherite. **PvP
  everywhere.**

Click either for detail: exact ore-generation percentages, regeneration
delay, KOTH state, Hot Zone timing, and your own effective mining bonus.

Things to know before you go:

- **Silk Touch and Fortune do nothing** on mine ores. Only the base drop and
  your boosters decide what you get.
- You can only break blocks the mine generates, and you **cannot place
  blocks** inside a mine.
- Mined blocks **regenerate** after about 30 seconds.

### Mine KOTHs

Each mining world has a permanent capture point. Stand in the zone with your
faction to take it; holding it gives your whole faction an **ore drop bonus
in that world**, which grows the longer you keep it (+5% on capture, up to
+20% after two hours).

Taking a defended point takes about twice as long as taking an empty one —
you have to wear the holder's control down first. While two rival factions
are both inside, nobody makes progress. **You need a faction to capture.**

### Hot Zones

Now and then an entire mining world goes **hot** for a while: extra ore drops
and better odds on the rarer ores. It's announced in chat when it starts, and
`/mines` shows how long is left.

See [Mining Worlds](mines.md).

## Boosters

`/boosters` shows every bonus category and what's actually being applied to
you right now — ore drops, sell bonus, mob spawn rate, mob drops, and XP.

Click a category for the per-source breakdown. Sources that *could* apply but
don't are listed with the reason, so you can see exactly why you're not
getting something ("no Backpack equipped", "your faction does not control
this Mine KOTH").

The number shown is always the number the server uses. If a cap reduces it,
you'll see the raw total, the cap, and the result.

See [Boosters](boosters.md).

## Factions

Vertex provides the normal faction system directly, so all the normal
faction commands still work. On top of them:

| Command | Permission / Audience | What it does |
|---|---|---|
| `/f rally [set\|clear]` · `/frally` | Faction role permission (`Set Rally` / `Clear Rally`) | A 4-minute rally point your faction can track |
| `/f bank` | Faction bank role checks | Faction money, experience, and TNT |
| `/f upgrades` | Leaders/Co-Leaders | Buy persistent faction upgrades |
| `/f permissions` | Leaders/Co-Leaders/Admins depending on action | Leaders configure who can do what |

The **TNT bank** holds up to 1,000,000 TNT, raised to 10,000,000 by the TNT
Bank upgrade. `/tntfill <radius> <amount> bank|inventory` fills every
dispenser within range inside your own claim, drawn from the bank or your
inventory.

**TNT Wands** convert Gunpowder in a chest or Chunk Collector straight into
banked TNT. If the bank is full the wand isn't used at all — no uses spent,
no Gunpowder taken.

See [Factions Integration](factions-integration.md).

## Events

`/koth focus [name|off]` and `/outpost focus [name|off]` give you a bossbar
with a **live arrow pointing toward the event**, updating as you move. Turning
focus off keeps it off until you turn it back on.

Capturing needs a faction, and the bossbar hides if you're in the wrong world.

See [KOTH & Outposts](koth-and-outposts.md).

## Spawners & Chunk Collectors

Spawners are bought from the **Spawners & Mob Drops** category inside
`/shop` — there's no separate command. Place one in your own faction's claim,
then right-click it with a matching spawner item to stack them.

Chunk Busters and reusable Source Buckets are bought from `/shop` →
**Raiding Materials**. Chunk Busters are glowing magma blocks and use the
rank selected in your faction's `/f permissions` menu.

**Chunk Collectors** gather everything that dies or drops in their chunk into
one place. The item's lore shows its level, what's stored, and its capacity
before you place it.

Sell Wands work on Collectors, so you can stockpile and sell in one go.

See [Spawners & Chunk Collectors](spawners-and-collectors.md).

## Cosmetics & language

`/tags` browses cosmetic chat tags — some are unlocked by permission, some
are free. `/language [code]` changes your language; `/language` alone lists
what's available.

## Combat

You're **combat-tagged** for a short time after fighting another player.
While tagged you can't use certain escapes, and logging out is penalised.

A staff member **cannot kick or ban you while you're combat-tagged** — that
would hand you a free escape from a fight. They have to wait for the tag to
expire.

See [PvP & Combat](pvp-and-combat.md).
