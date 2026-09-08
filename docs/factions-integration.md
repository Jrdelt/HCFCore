# Factions Integration

Vertex is built directly on top of **FactionsUUID** — it's a hard
dependency, not an optional one. Vertex is compiled against FactionsUUID
4.4.0, and its direct API use has also been checked against 4.7.0. This page
covers everything that plugs into faction identity and relationships:
nametags, rallies, permissions, upgrades, and the bank. Chat formatting
is covered in [Configuration](configuration.md#chat-formatting) since
it's primarily config-driven.

## Nametags

Every player sees a nametag above everyone else's head:

```text
[ftop] [FactionName] PlayerName
```

- `[ftop]` — the faction's power-ranking position (`-` if factionless).
- `[FactionName]` — shows `Neutral` if factionless.
- **Color is relative to the viewer, not fixed.** Your own faction
  renders green, an allied faction renders light purple, and everyone
  else — enemies, truce/no-relation factions, and factionless players
  alike — renders red by default (all four colors configurable under
  `nametags.colors`). The same subject genuinely renders differently to
  different viewers simultaneously, because each nametag is its own
  scoreboard team registered on that specific *viewer's* own scoreboard,
  not a single shared team.
- Ally/enemy status comes from FactionsUUID's real `/f ally` / `/f enemy`
  relations. The more hostile of the two factions' one-directional wishes
  wins — a one-sided ally wish alone doesn't count, but a one-sided enemy
  wish does.
- Teams are keyed by a short hash of the player's UUID, not their name,
  so a username change can't orphan one. Team names are kept to 14
  characters — safely under the classic 16-character vanilla scoreboard
  team-name limit, which still applies to any older client bridged in via
  ViaVersion regardless of the server's own version.
- Nametags are rebuilt on join and on every `/vertex reload`, and kept
  in sync incrementally as factions change in between.

Toggle the whole system with `nametags.enabled`; refresh rate is
`nametags.update-interval-ticks`.

## Rally

`/f rally [set|clear]` (alias `/frally`) sets a rally point at the
sender's current location; with no argument it acts as `set`. The point is
visible to the whole faction for four minutes; `clear` removes it early.
The **Set Rally** and **Clear Rally** role permissions control the two
actions independently. They default to allowed for Moderator, Member, and
Recruit, but the faction leader can change them in `/f permissions`.

Faction members in the rally's world see a green bossbar with live distance
and a compass arrow pointing toward it, refreshing every 10 ticks
(twice per second).
The arrow points to a true compass bearing (north stays north) rather
than one relative to the viewer's own facing. Rally indicators only
display in the same world the rally was set in, preserve each player's
prior compass target on expiry, and clean up their bossbars on shutdown.

## Rally / Faction Permission GUI

A faction leader opens the complete FactionsUUID permission matrix with
`/f permissions` (or `/f perms`, or any alias configured under
`factions.command-aliases`):

- The top row selects which role's permissions you're editing:
  **Moderator** (applies to both FactionsUUID's Co-Leader and Moderator
  roles), **Member**, or **Recruit**.
- The grid lists every FactionsUUID native permission plus Vertex's **Set
  Rally**, **Clear Rally**, **Add Spawners**, **Remove Spawners**, **Open
  Collectors**, **Break Collectors**, **Deposit Bank Resources**, and
  **Withdraw Bank Resources** actions. Every permission is a
  green stained-glass pane when allowed for that role and a red pane when
  denied. The native **Upgrade** permission controls access to Vertex's
  `/f upgrades` menu as well. The state material is deliberately not
  configurable.
- All visible GUI text (title, roles, action names, status, and click
  instructions) renders in small caps. Color tags in `config.yml` still
  work normally.
- **Left-click allows**, **right-click denies**. Changes save immediately
  to FactionsUUID's own permission system for native actions, and to
  Vertex's per-faction configuration for the eight Vertex-specific actions.
- **Admin is intentionally not selectable.** FactionsUUID always permits
  its own Admin role to perform every native action regardless of any
  configured permission, so there is nothing for this GUI to toggle for
  Admin.

The title, each role's slot/icon/name, and any action's label override
live under `rally.permission-gui` in `config.yml` (see
[Configuration](configuration.md#rally-permission-gui)). Per-faction rally
permission choices are saved under `rally.faction-permissions` and are
removed automatically when a faction disbands.

## Faction upgrades

`/f upgrades` (or `/f upgrade`, including configured faction-command
aliases) opens Vertex's persistent per-faction upgrade GUI. The role needs
FactionsUUID's native **UPGRADE** action allowed to open or buy from it.
`leader-only: true` adds a leader-only purchase rule; with the shipped
`false` setting, any role allowed to use **UPGRADE** can buy a level using
their own Vault balance.

Vertex owns the price, level, and bonus for the following entries. Every
level is stored by stable faction id, so it survives restarts and faction
renames; faction disband deletes Vertex-owned levels and bank balances.

| Upgrade | Where it applies | Exact effect |
| --- | --- | --- |
| Claim Damage | Attacker standing in their own claim | Increases damage from the player or their projectile. |
| Claim Protection | Member standing in their own claim | Reduces all incoming damage. |
| Armor Wear | Member standing in their own claim | Reduces durability damage, preserving fractional reductions fairly over repeated hits. |
| Fall Protection | Member standing in their own claim | Adds a second fall-damage reduction. It combines with Claim Protection if both exist. |
| Fly Boost | Already-flying member in their own claim | Multiplies flight speed; does not grant flight. Vertex reapplies the multiplier after another plugin changes the base speed. |
| Spawner Rate | Vertex spawner physically in that faction's claim | Retunes its spawn count and nearby-mob cap, including the daylight/Iron Golem fallback. |
| Crop Growth | Crop physically in that faction's claim | Gives a configured chance for one extra growth stage. No player needs to be present. |
| Mob Experience | Mob killed by a member in that faction's claim | Increases the XP dropped by that kill. |

**Warps is the one native bridge.** Buying a Vertex **Warps** level calls
FactionsUUID's `WARPS` upgrade directly. FactionsUUID remains responsible
for `/f warp`, warp creation, and the number of permitted warps. Vertex
adopts an existing native Warp level when the GUI opens and never lowers it.
Set the same maximum level and a complete level-to-warp-count mapping in
FactionsUUID's own upgrades configuration; Vertex's price/`bonus` field is
only GUI text for this entry.

FactionsUUID also offers native upgrades with overlapping effects (territory
damage, armor durability, fall reduction, growth, mob XP, and spawner rate).
Keep those native duplicates disabled when using Vertex's matching entries
or their effects can stack. Native **Flight** can remain enabled when it is
used to grant flight: Vertex's Fly Boost only changes the speed of players
already flying. Vertex intentionally routes `/f upgrades` to its GUI, so
manage any remaining native FactionsUUID upgrades through FactionsUUID's
administration/configuration rather than expecting that player command to
open both menus.

Set an individual `enabled: false` or set `faction-upgrades.enabled: false`
to stop new purchases and its Vertex effect without deleting the saved
level. See [Configuration](configuration.md#faction-upgrades) for explicit
per-level prices and bonuses. GUI title, names, effects, lore, and messages
are localized under `faction-upgrades` in `lang/en_us.yml`.

## Faction bank

`/f bank` opens a six-row bank GUI. Its upper rows show the faction's
stored **money** (Gold Block), **experience** (Experience Bottle), and
**TNT**, followed by deposit and withdraw controls that open an amount
prompt.

Money, experience, **and TNT** are all stored by Vertex in the selected
database and survive a restart; money moves through Vault. Deposit/withdraw
opens a free anvil prompt: type a positive whole number, then click the
green confirm result. Vertex accepts a number typed after the displayed
prompt as well as a replaced prompt. Amounts accept shorthand — `10k`,
`1.5m`, `2b` — through the shared parser described in
[Configuration](configuration.md#number-formatting).

Vertex owns the TNT balance rather than delegating to FactionsUUID's native
TNT bank. The native bank is an unsaved in-memory field whose ceiling comes
from FactionsUUID's own config, which meant deposits could be lost on
restart and no Vertex upgrade could raise the cap. On the first start after
upgrading, any balance still sitting in the native bank is moved into
Vertex's storage once and cleared from the native field, so nothing is lost
and the two can never both claim the same TNT.

A faction's TNT ceiling starts at `faction-upgrades.tnt-base-capacity`
(default 1,000,000) and is raised by the **TNT Bank** faction upgrade. That
upgrade is unusual: each level's `bonus` is the absolute capacity at that
level rather than a percentage, so capacities are read straight off
`config.yml` instead of being derived from a multiplier. The shipped levels
run 2M → 10M.

`/tntfill <radius> <amount> bank|inventory` (see
[Commands & Permissions](commands-and-permissions.md#factions--rally))
draws from this same TNT bank (or the player's own inventory instead) to top
up every dispenser within range inside the player's own claim, without
manually depositing/withdrawing and hand-filling each one.

Vertex serializes each faction's money/XP write before it changes the cached
balance. If a money/XP deposit cannot be saved, it returns the resources;
failed money payouts are compensated back into the bank. `bank-deposit` and
`bank-withdraw` appear in `/f permissions` and default to allowed for
Moderator, Member, and Recruit. All names, lore, prompts, and messages are
under `faction-bank` in the language files.

## Leader-leave protection

`factions.prevent-leader-leave` (default `true`) blocks a faction
leader's `/f leave` — and any alias listed in `factions.command-aliases`
— with an explanatory message instead of letting it through. This
prevents a leader from leaving their own faction (accidentally or as an
exploit) without transferring leadership to someone else first.

## Faction compatibility

Every hostile ability, and Archer Tag, refuses to target faction members
or allies; Portable Bard's buffs are the deliberate exception, designed
to be shared with your own faction. See
[PvP & Combat](pvp-and-combat.md#faction-compatibility) for the full
list. Vertex is built against FactionsUUID 4.4.0; the currently used 4.7.0
API has been checked for compatibility with Vertex's direct integrations.
