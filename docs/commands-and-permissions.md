# Commands & Permissions

Every command Vertex registers, grouped by area. "Open to all" means no
permission is checked — anyone can run it.

## Kits

| Command | Permission | Notes |
|---|---|---|
| `/kit <name>` | The kit's own `permission` (blank for the six base classes; `-donator` variants require theirs) | Applies a kit, respecting its cooldown and cost. |
| `/kit create <name> [permission] [cooldownSeconds] [cost] [costItem[:amount]]` | `vertex.kit.create` | Saves your current armor + inventory as a new kit. `costItem` is a Material name (e.g. `DIAMOND:2`); amount defaults to 1. `/kit save` is the same command under its legacy name (`vertex.kit.save`). |
| `/kit delete <name>` | `vertex.kit.delete` | Deletes a kit. |
| `/kits` | Open to all | Browse/claim kits in a GUI — see [Kits & Abilities](kits-and-abilities.md). |

## Abilities

| Command | Permission | Notes |
|---|---|---|
| `/getitem <username> <ability> [amount]` | `vertex.ability.give` | Gives ability items directly, ignoring cooldowns. |
| `/abilities` | Open to all | Browse abilities in a GUI; a viewer with `vertex.ability.give` can click to receive one. |
| `/cooldowns` | Open to all | Shows your own active kit, ability, and vanilla item cooldowns. |

## Tags

| Command | Permission | Notes |
|---|---|---|
| `/tags` | Open to all | Browse/equip cosmetic tags — see [Tags & Cosmetics](tags-and-cosmetics.md). |

## Combat

| Command | Permission | Notes |
|---|---|---|
| `/uncombat <player>` | `vertex.combat.uncombat` | Clears a combat tag early. |
| `/combatcheck <player>` | `vertex.combat.check` | Reports tag status, time left, opponent, health, ping. |
| `/combattag <player> [opponent\|server]` | `vertex.combat.tag` | Testing tool — see [PvP & Combat](pvp-and-combat.md). |
| — | `vertex.combat.safezone.bypass` | Not a command — lets staff move into a protected zone while combat-tagged, where every other player is blocked. See [PvP & Combat](pvp-and-combat.md#combat-locks-you-out-of-safezone-entirely). |

## Spawners & Chunk Collectors

Spawners no longer have their own command — they're a category inside
`/shop` now (see [Shop](#shop) below). Buying one opens the same
per-mob-type catalog `/spawners` used to, just reached through the
shop's category picker instead of its own top-level command.

| Command | Permission | Notes |
|---|---|---|
| `/chunkcollector give <player>` | `vertex.collector.give` | Gives a Chunk Collector — no in-game shop for these. |

## Blueprint Base Builder

| Command | Permission | Notes |
|---|---|---|
| `/blueprint give <player> <template>` | `vertex.blueprint.give` | Gives a Blueprint item. Available only when both FAWE and DecentHolograms are loaded. |
| `/blueprint cooldown remove <player>` | `vertex.blueprint.cooldown.remove` | Clears an online or offline player's persisted placement cooldown. Available only when both Blueprint dependencies are loaded. |

## Backpacks

| Command | Permission | Notes |
|---|---|---|
| `/backpack give <player> <tier> [level]` | `vertex.backpack.give` | Gives a Backpack of that tier, optionally starting at any positive level (default 1) — no in-game shop for these. Its material and custom model data come from `backpacks.yml`. |
| `/backpack debug` | `vertex.backpack.debug` | Toggles personal Backpack interaction diagnostics. It prints the received action, hand, cancellation state, and reject/open reason to chat and console; run it again to turn tracing off. |
| `/filter <material>` / `/filter clear` | — | Toggles or clears persistent Backpack auto-collection filters. Filters discard matching routed drops only while a Backpack is equipped. |

## Player trading

| Command | Permission | Notes |
|---|---|---|
| `/trade <player>` / `/trade accept <player>` / `/trade cancel` | `vertex.trade.use` | Creates, accepts, or cancels a secured direct trade. |
| `/tradetoggle` | `vertex.trade.use` | Persists an incoming-request opt-out. |
| `/tradeadmin reload` | `vertex.trade.staff.reload` | Reloads and validates `traders.yml`. |
| `/tradehistory [player]` / `/tradelogs [all\|player]` | `vertex.trade.staff.history` | Opens the read-only, paginated trade audit history. |

`vertex.trade.staff.bypassdistance` and `vertex.trade.staff.bypassblacklist`
are available for staff testing. See [Player Trading](trading.md).

## Coinflips

| Command | Permission | Notes |
|---|---|---|
| `/cf` (alias `/coinflip`) | — | Opens the Active Coinflips browser. |
| `/cf <amount> [money] [player]` | — | Hosts a money coinflip, optionally targeted at one player. "money" is an optional explicit keyword. |
| `/cf <amount> exp [player]` | — | Hosts an experience-level coinflip. |
| `/cf <amount> gc [player]` | — | Hosts a GC coinflip — see [GC (Gift Card / Credit)](gc-currency.md#gc-as-a-coinflipauction-house-currency). |
| `/cf hand [player]` | — | Opens the item wager picker GUI. |
| `/cf review <id>` | Open to the coinflip's host only | Opens the match-review GUI: the host's wager above, the proposed items below, Accept/Deny buttons — see [Item wager approval](coinflips.md#item-wager-approval). |
| `/cf approve <id>` / `/cf deny <id>` | Open to the coinflip's host only | Accepts or rejects a pending item-wager match straight from chat, without opening the review GUI. |
| `/cf ban` / `/cf ban confirm` / `/cf unban` | — | Self-exclusion — see [Coinflips](coinflips.md#self-ban). Confirmation is required within 30 seconds; the ban itself cannot be lifted early. |
| `/cf cancel <id>` | `vertex.coinflip.remove` for someone else's; open to the host for their own | Cancels an unplayed coinflip and refunds its wager. |
| `/cf logs [player] [page]` | `vertex.coinflip.logs` | Reads the permanent staff audit log. |

## GC (Gift Card / Credit)

| Command | Permission | Notes |
|---|---|---|
| `/gc` | `vertex.gc.use` (default: true) | Opens the GC wallet GUI (balance, Deposit, Withdraw, Redeem, Logs). |
| `/gc redeem <code>` | `vertex.gc.use` | Consumes a staff-generated redeem code. |
| `/gc redeem create <amount> [uses] [expires-in]` | `vertex.gc.redeem.create` | Generates a new redeem code (default: single-use, no expiry). |
| `/gc admin balance <player>` | `vertex.gc.view` | Views another player's GC balance. |
| `/gc admin give\|remove\|set\|zero <player> <amount>` | `vertex.gc.adjust` | Mutates a balance directly. Every attempt — permitted or not — is logged to console. |
| `/gc admin logs [player] [page]` | `vertex.gc.logs` | Reads the permanent GC audit log. |
| `/gc setsigninput` | `vertex.gc.adjust` | Sets the shared physical sign Deposit/Withdraw amounts are typed on. |

GC is also a third wager/listing currency in Coinflip (`/cf <amount> gc
[player]`) and the Auction House (`/ah sell <price> gc`) — see
[GC (Gift Card / Credit)](gc-currency.md) for the full picture, including
the sign-based amount-entry flow.

## Dupe investigation

| Command | Permission | Notes |
|---|---|---|
| `/dupe inspect list [page]` | `vertex.dupe.inspect` | Lists open suspected-duplicate cases. |
| `/dupe inspect <id>` | `vertex.dupe.inspect` | Shows one case's full evidence and (once closed) resolution detail. |
| `/dupe resolve <id> [reason]` | `vertex.dupe.resolve` | Closes a case as resolved. Every attempt — permitted or not — is logged to console. |
| `/dupe dismiss <id> [reason]` | `vertex.dupe.resolve` | Closes a case as a false positive. Every attempt is logged. |
| `/dupe confirm <id> [reason]` | `vertex.dupe.resolve` | Closes a case as a confirmed duplicate. Every attempt is logged. |

Cases open automatically — there is no manual "create case" command — the
moment the same tracked item identity is observed in two places at once.
`vertex.dupe.alert` grants a live alert when a new case opens and a
join-time summary of any still-open cases. See
[Dupe investigation](dupe-investigation.md) for the full detection model.

## Personal settings

| Command | Permission | What it does |
|---|---|---|
| `/settings` (alias `/preferences`) | — | Opens personal toggles for optional Coinflip, KOTH, Outpost, Mining Event, and Server announcements. |

## Spawner diagnostics

| Command | Permission | What it does |
|---|---|---|
| `/vertex spawnerinfo` | `vertex.admin` | Look at a nearby spawner for its live type, delay, nearby-mob cap, player range, light state, and tracking/tuning report. |
| `/vertex spawnerdebug` | `vertex.admin` | Toggles live spawn traces for tracked spawners within 64 blocks, including manual-fallback skips and final event cancellation. |

## Mining Worlds

| Command | Permission | Notes |
|---|---|---|
| `/mines` | Open to all | Overview of the placed mining worlds — see [Mining Worlds](mines.md). |
| `/mines wand <mine>` | `vertex.mines.admin` | Blaze-rod corner selection; saving writes the world and bounds into `mines.yml`. |
| `/mines fill <mine>` | `vertex.mines.admin` | Seeds the region with ore from its table. Runs automatically when a region is placed; use this after rebuilding the arena or changing the ore table. |
| `/mines cancel` / `/mines list` | `vertex.mines.admin` | Abandon a selection, or show which mines are placed. Staff subcommands are never named to players who lack the permission. |

## Wands

| Command | Permission | Notes |
|---|---|---|
| `/wand give <player> <tier> [uses]` | `vertex.wand.give` | Gives a Sell Wand or TNT Wand — see [Wands](wands.md). Right-click a chest or Chunk Collector to use one. |

## Boosters

| Command | Permission | Notes |
|---|---|---|
| `/boosters` | Open to all | Shows the bonuses actually in force, per category — see [Boosters](boosters.md). |
| `/boosters inspect <player>` | `vertex.boosters.inspect` | Another player's breakdown. Only advertised in usage text to holders of the permission. |

## Shop

| Command | Permission | Notes |
|---|---|---|
| `/shop` | — | Opens the category picker — see [Shop](shop.md#categories-shopyml). |
| `/shop buy <item> [amount]` | — | Buys at the current dynamic price, regardless of category. |
| `/shop sell <item> [amount]` | — | Sells at the current dynamic price, regardless of category. |

The category picker's last icon is **Spawners** — a separate catalog
(one flat price per mob type, from `spawners.yml`, not part of the
dynamic-price system the rest of the shop uses) with its own Back
button. This replaces the old standalone `/spawners` command.

The **Raiding Materials** category's control row similarly has a "Buy
Chunk Busters" button opening a small catalog of the 4 Chunk Buster types
(priced from `chunkbuster.yml`, same reasoning as Spawners above). See
[Chunk Busters](chunk-busters.md#acquisition).

The **Miscellaneous** category's control row similarly has a "Buy Source
Buckets" button opening a small catalog of every enabled Source Bucket
variant (priced from `sourcebuckets.yml`, same reasoning as Spawners
above). See [Source Buckets](source-buckets.md#acquisition).

The same **Miscellaneous** category's control row also has a "Buy Runes"
button opening a small catalog of the 4 Rune tiers (priced from
`runes.yml`, same reasoning as Spawners above). See
[Custom Enchantments](custom-enchantments.md#acquisition).

## Auction House

| Command | Permission | Notes |
|---|---|---|
| `/ah` (alias `/auctionhouse`) | — | Opens the browse GUI. |
| `/ah sell <price> [money\|exp\|gc]` | — | Lists the item in your main hand at a fixed buy-it-now price, in money (default), experience levels, or GC. |
| `/ah cancel <id>` | `vertex.auction.remove` for someone else's; open to the seller for their own | Cancels an unsold listing and returns the item. |
| `/ah collect` | — | Opens the claim GUI for items waiting on you (sold, expired, or cancelled while you couldn't receive them directly). |
| `/ah logs [player] [page]` | `vertex.auction.logs` | Reads the permanent staff audit log. |

## Factions & Rally

| Command | Permission | Notes |
|---|---|---|
| `/f rally [set\|clear]` (alias `/frally`) | The role's **Set Rally** / **Clear Rally** permission | Sets/clears a 4-minute faction rally point. Defaults allow every editable role; leaders change it in `/f permissions`. |
| `/f permissions` / `/f perms` | Faction leader only (checked in-code, not a permission node) | Opens the faction permission matrix GUI. |
| `/f upgrades` / `/f upgrade` | Faction role needs FactionsUUID's native `UPGRADE` action allowed | Opens Vertex's persistent faction-upgrades GUI. Set `faction-upgrades.leader-only: true` to restrict purchases further. FactionsUUID's native upgrade administration remains separate. |
| `/f bank` | Faction member; role permissions apply to deposits/withdrawals | Opens the six-row faction bank for money, experience, and TNT. |
| `/tntfill <radius> <amount> bank\|inventory` | `vertex.tntfill.use` (default: everyone) | Fills every dispenser within `radius` blocks (max 100) of you, inside your own faction's claim, with up to `amount` TNT — drawn from the faction's Vertex TNT bank (the same one `/f bank` manages) or your own inventory. The bank is debited before any dispenser is filled, and anything the dispensers cannot take is returned immediately. |
| `/f top` (or any `factions.command-aliases` alias) | Open to all | Vertex's claimed, individually-aged spawner-value leaderboard — replaces FactionsUUID's power-based `/f top`. See [Faction Leaderboards](faction-leaderboards.md#f-top-claimed-spawner-value). |
| `/ftopforcecheck` | `vertex.ftop.forcecheck` | Immediately recalculates F Top for every faction without moving the regular scheduled deadline. |
| `/f baseclaim` (or any `factions.command-aliases` alias) | `vertex.baseclaim.view` | Opens the Base Claim info/removal GUI if standing on one; otherwise attempts to create one (Leader/Co-Leader + `vertex.baseclaim.create` only). Confirming removal in that GUI additionally requires `vertex.baseclaim.remove` (default: true). See [Base and Raid Claims](base-and-raid-claims.md). |
| `/f baseclaim buy` | `vertex.baseclaim.purchaseslot` | Any member purchases their faction's next Base Claim slot (#2/#3), paid from their own balance. |
| `/f pvptop` (or any `factions.command-aliases` alias) | Open to all | Faction leaderboard ranked by persisted KOTH/Outpost capture points, independent of F Top. See [Faction Leaderboards](faction-leaderboards.md#pvp-top-objective-points). |
| `/f shield` (or any `factions.command-aliases` alias) | Open to all | Shows the caller's faction's Faction Shield status: active/inactive, countdown, current/pending schedule, admin override. See [Faction Shield](faction-shield.md). |
| `/f shield set <HH:mm> <minutes>` | `vertex.shield.set` | Leader/Co-Leader only; submits a new Shield schedule (takes effect after the configured activation delay). Rejected until the New-Faction Shield Delay has passed. |
| `/f shield admin <faction> active\|inactive\|clear` | `vertex.admin.claims` | Staff force a faction's Shield state, or clear an existing override. Every attempt is console-logged; silent to the affected faction. |
| `/f who <faction-or-player>` | *(native FactionsUUID permission)* | Native command untouched; Vertex appends a one-tick-delayed Shield status follow-up message. |

## Chunk Busters

Chunk Busters have no dedicated `/f` subcommand — they're triggered by
right-clicking a block with the item, purchased from `/shop`'s Raiding
Materials category. See [Chunk Busters](chunk-busters.md) for the
confirmation flow, zone rules, and the batched/restart-safe removal
mechanics.

| Command | Permission | Notes |
|---|---|---|
| Right-click a block with a Chunk Buster | *(none — gated by zone/combat/role checks in-code)* | Opens the confirmation GUI. |
| `/chunkbuster permission <role> <allow\|deny>` | `vertex.chunkbuster.permission` | Faction Leader/Co-Leader only; edits only the caller's own faction's role permissions. |

## Source Buckets

Source Buckets have no dedicated command either — right-clicking with one
places water/lava immediately, with no confirmation step, purchased from
`/shop`'s Miscellaneous category. See [Source Buckets](source-buckets.md)
for the flow patterns, the claim-boundary rule, and the charge-after-
success economics.

| Command | Permission | Notes |
|---|---|---|
| Right-click a block with a Source Bucket | *(none — usable by everyone; gated by zone/combat/economy checks in-code)* | Places water/lava following the item's configured flow pattern. |

## Custom Enchantments

Rolling a Rune is a direct right-click action with no GUI; applying a
physical enchant item opens the Enchant Application GUI. Runes are
purchased from `/shop`'s Miscellaneous category, or given by staff. See
[Custom Enchantments](custom-enchantments.md) for tiers, roll tables, the
level-replacement rules, and Lucky Gems.

| Command | Permission | Notes |
|---|---|---|
| Right-click a Rune | *(none)* | Rolls it immediately against its tier's table and gives you the resulting physical enchant item. Consumes one Rune. |
| Right-click a physical enchant item | *(none)* | Opens the Enchant Application GUI with that item pre-placed. |
| `/enchant give <player> rune <tier> [amount]` | `vertex.enchant.give` | Gives a Rune of the given tier (`simple`/`elite`/`rare`/`legendary`). |
| `/enchant give <player> gem [amount]` | `vertex.enchant.give` | Gives Lucky Gems. |

## KOTH & Outposts

| Command | Permission | Notes |
|---|---|---|
| `/koth focus [name\|off]` | Open to all | Focuses an active same-world KOTH for that player only, using a BossBar/compass instead of their faction rally. |
| `/outpost focus [name\|off]` | Open to all | The Outpost equivalent of `/koth focus`. |
| `/koth create\|wand\|cancel\|start\|stop\|delete\|list\|validate` | `vertex.koth.admin` | Creates cuboid KOTH regions with a Blaze Rod, starts/stops any configured KOTH, and validates KOTH config paths in-game. |
| `/outpost create\|wand\|cancel\|start\|stop\|delete\|list\|validate` | `vertex.outpost.admin` | Creates, starts/stops, and validates Outpost regions. |

See [KOTH & Outposts](koth-and-outposts.md) for capture rules, schedules,
rewards, and the `capture-events.yml` reference.

## Sand Bots

| Command | Permission | Notes |
|---|---|---|
| `/sandbot give <player>` | `vertex.sandbot.give` | Gives a Sand Bot item. |
| `/sandbot stop` | Open to all | Stops your own active Sand Bot(s). |
| `/sandbot stop <player>` | `vertex.sandbot.admin` | Stops another player's active Sand Bot(s). |

## Reboot

| Command | Permission | Notes |
|---|---|---|
| `/reboot [minutes]` | `vertex.reboot.start` | Starts a shutdown countdown. |
| `/reboot cancel` | `vertex.reboot.start` | Cancels an in-progress countdown. |
| `/nextreboot` | Open to all | Shows the scheduled countdown, if any. |

## Staff

| Command | Permission | Notes |
|---|---|---|
| `/staff` | `vertex.staff.mode` | Vanish + staff-build + godmode + flight together. |
| `/vanish` | `vertex.staff.vanish` | Toggles vanish; also lets you see other vanished staff. |
| `/staffchat` | `vertex.staff.staffchat` | Toggles staff-only chat; also needed to read it. |
| `/staffbuild` | `vertex.staff.staffbuild` | Toggles claim-protection bypass everywhere. |
| `/freeze <player>` | `vertex.staff.freeze` | Toggles freezing a player; also needed for the leave-while-frozen ban alert. |
| `/invsee <player>` | `vertex.staff.invsee` | Opens the target's storage/armor/offhand GUI. |
| `/endersee <player>` | `vertex.staff.endersee` | Opens the target's live ender chest. |
| `/rollback <player>` | `vertex.staff.rollback` | Opens the death-history GUI. |
| _(any configured kick/ban)_ | `vertex.staff.punish.bypasscombat` | Kicks/bans are refused while the target is combat-tagged — see [PvP & Combat](pvp-and-combat.md#staff-punishments-during-combat). This node overrides that; every use is logged. Mutes are never guarded. |

## Language

| Command | Permission | Notes |
|---|---|---|
| `/language [code]` | Open to all | View or change your language. |

## Admin

| Command | Permission | Notes |
|---|---|---|
| `/vertex reload` | `vertex.admin` | Reloads config/kits/abilities/tags/messages. |
| `/vertex clearmobstacks` | `vertex.admin` | Clears every tracked stacked mob. |
| `/vertex storage` | `vertex.admin` | Shows which storage backend is currently in use. |
| `/vertex storage <local\|mysql> [confirm]` | `vertex.admin` | Copies all data into the other backend and switches `storage.type` to it. Takes effect on the next restart. `confirm` is required if the target database already has data in it, since it gets overwritten. See [Installation](installation.md#switching-backends-in-game). |
| `/vertex performance` | `vertex.admin` | Reports the performance framework's current OFF/BASIC/DETAILED monitoring state — see [Performance](performance.md). |

## Permission node reference

Beyond the fixed nodes above, three areas use **dynamic**, config-defined
nodes:

| Pattern | Example | Source |
|---|---|---|
| `vertex.kit.<name>` | `vertex.kit.archer` | Default permission for any kit that doesn't set its own blank `permission: ''` — see [Kits & Abilities](kits-and-abilities.md#permission) |
| `vertex.kit.<class>.donator` | `vertex.kit.archer.donator` | The permission each shipped `-donator` kit variant actually uses |
| `vertex.tag.<name>` | `vertex.tag.berserker` | Set per-tag in `tags.yml`; blank unlocks it for everyone |

Plus two bypass nodes for staff testing kits: `vertex.kit.bypasscooldown`
and `vertex.kit.bypasscost`. `vertex.admin` also covers reload/
clearmobstacks as shown above.

### Notification-only nodes

Two nodes grant no command at all — they only decide **who receives an
alert**. That is why they are easy to miss when auditing permissions against
the command list: nothing in `plugin.yml`'s `commands:` block points at
them, and a player holding one sees no new command appear.

| Permission | Who holds it receives | Default |
|---|---|---|
| `vertex.trade.staff.alerts` | Suspicious player-trade alerts — see [Player Trading](trading.md) | op |
| `vertex.sandbot.debug` | Sand Bot placement diagnostics in chat — see [Sand Bots](sandbots.md) | op |
| `vertex.dupe.alert` | A live message when a new suspected-duplicate case opens, plus a join-time summary of any still-open cases — see [Dupe investigation](dupe-investigation.md) | op |

Grant these to the staff who should be *notified*, which is usually a
narrower group than those who can run the related commands.

## Tab-completion

Every command with arguments registers its own `TabCompleter`, so
suggestions appear as soon as the running jar includes them. If
suggestions don't show up in-game:

1. Confirm the server is actually running the jar you just built —
   check the file's modified date, or watch the console for the Vertex
   startup banner's version line.
2. Confirm you hold the command's permission — a player missing
   `vertex.combat.tag`, for example, won't see `/combattag` suggested at
   all.
3. Some clients cache command suggestions per session — rejoin if a
   command was added while already connected.
