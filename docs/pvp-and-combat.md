# PvP & Combat

## Combat tag

Landing or receiving a hit from another player starts a combat tag
lasting `pvp.combat-tag-seconds` (default 30s) for both players. While
tagged:

- Both players see a live action bar (see below).
- Every command in `pvp.blocked-commands-in-combat` is blocked — kits,
  teleports/warps/homes, economy/trade commands, `fly`/`god`/`heal`, and
  faction-exploit commands like `f leave`/`f kick`/`f disband`. A
  one-word entry blocks that whole command tree; a multi-word entry like
  `f home` blocks only that exact subcommand, leaving the rest of `/f`
  untouched.
- Disconnecting while tagged counts as a combat log if
  `pvp.logout-penalty` is true.

## Ghost Players (Citizens)

With [Citizens](integrations.md#citizens--optional) installed, Vertex can
replace the instant combat-log death with a killable NPC. Enable
`pvp.ghost-players.enabled` to use it. Ghosts are created only for server
kicks or forced disconnects; a voluntary logout always receives the normal
combat-logout penalty. By default a forced disconnect also needs an active
Vertex combat tag; set `combat-tagged-only: false` to allow every forced
disconnect in the allowed worlds.

The disconnected player's inventory, armor, offhand, and cursor item are
saved to `plugins/Vertex/ghost-players.yml` and removed from their real
player data before the NPC becomes vulnerable. If the NPC dies, those exact
items drop once and its owner dies on their next login. If the owner returns
while it is alive, Vertex removes the NPC and restores the saved items. The
pending-record step also makes an interrupted save/restart resolve safely:
the player gets their saved inventory back rather than duplicating or losing
it.

`npc-type` accepts a living Bukkit entity type, although `PLAYER` is the
intended choice for a player-looking Citizens NPC. `allowed-worlds: []`
means all worlds. `despawn-after-seconds: 0` keeps a ghost until it is killed
or its owner returns; a positive value removes an untouched ghost and safely
restores its inventory when the owner next joins.

**Landing a kill** shortens the killer's *own* tag down to
`pvp.post-kill-combat-seconds` (default 5s) instead of leaving them stuck
out the full duration — enough time to loot the body and retreat. This
only shortens an existing tag; a kill can't start one that wasn't already
there. Dying clears the victim's tag entirely without touching the
killer's, so the killer's shortened cooldown always survives the death
that caused it.

## Loot protection and death messages

When a player kills another player, the victim's dropped items are marked
for the killer alone for `pvp.loot-protection.seconds` (20 seconds by
default). Everyone else is prevented from picking them up until the timer
ends; the killer's pickup removes the temporary marker immediately, so the
item can be traded or dropped normally afterwards. Set
`pvp.loot-protection.enabled: false` or `seconds: 0` to disable it.

All Vertex death announcements are configurable under the `death:` section
of `lang/en_us.yml`: player kills, lava, fire, falls, void, drowning,
suffocation, and a generic fallback. Available placeholders are `{victim}`,
`{killer}`, and `{cause}`.

### Action bar

Three independent MiniMessage templates under `pvp.actionbar`, one per
situation:

| Template | When it's used | Extra placeholders |
|---|---|---|
| `vs-player` | Tagged against a real, currently-online opponent | `{opponent}`, `{health}` |
| `vs-server` | Tagged via `/combattag <you> server` — a synthetic opponent for solo testing | — |
| `vs-unknown` | Tagged, but the real opponent went offline | — |

All three also get `{seconds}` (time left, always pre-colored green) and
`{your_cps}`/`{their_cps}` (clicks per second, tracked from arm swings
for every online player so the count is already warm the instant a tag
starts — not just players currently tagged). `{health}` arrives
pre-colored red. Everything else in wording, color, and layout is fully
yours to rearrange. Refresh rate is `pvp.actionbar-update-interval-ticks`
(default 2 ticks = 10×/second).

### Testing & admin commands

| Command | Permission | Purpose |
|---|---|---|
| `/combattag <player> [opponent\|server]` | `vertex.combat.tag` | Force a tag for testing. No second argument (or `server`) tags against the synthetic **Server** opponent — lets one admin see the action bar alone. A real opponent name tags both players against each other. |
| `/combatcheck <player>` | `vertex.combat.check` | Reports tagged status, time left, and (if tagged) the opponent's name, health, and ping. |
| `/uncombat <player>` | `vertex.combat.uncombat` | Clears a tag early; notifies both staff and the target. |

## Item cooldowns

`pvp.pearl-cooldown-seconds`, `golden-apple-cooldown-seconds`, and
`enchanted-golden-apple-cooldown-seconds` override vanilla's own
cooldowns for those items. They're saved per-player and **survive
logout** — relogging is not a way to reset them.

The pearl cooldown specifically only starts once a throw actually
**lands** (on teleport, not on throw) — a throw blocked by a protected
zone or by the Pearl Stunner ability costs nothing and starts no
cooldown, since a pearl that never landed shouldn't count against you.

A pearl thrown while actively falling or looking downward also gets
extra velocity (`pvp.pearl-velocity-multiplier`, default 1.35×); a flat
or upward throw is untouched, so this can't be used to snipe someone
across the map.

## No-pearl zones

`pvp.no-pearl-regions` (WorldGuard region names) and
`pvp.no-pearl-claim-names` (faction claim names, case-insensitive) are
enforced at **both ends** of a throw:

- Throwing from inside one of these zones cancels the throw outright.
- A pearl landing inside one of these zones cancels the teleport instead.

Between the two, a pearl can never cross into, out of, or through a
protected zone in either direction. Either way the pearl itself is
refunded (dropped at your feet if your inventory is full), since vanilla
consumes it on throw, not on landing. The Time Warp Pearl ability checks
its recorded origin against the same zones, so a pearl thrown out of
spawn long before a fight can't become an anytime recall-to-safety
button. Switcher Snowball refuses to swap either side if the thrower or
the target is standing in one of these zones, for the same reason.

## Hunger

`pvp.disable-hunger-worlds` lists worlds where hunger never changes at
all — useful for a spawn/safezone world where players shouldn't need to
eat.

## Splash healing potions

Splash Potions of Healing (tier I or II — the only levels Instant Health
has) always deal their full effect to everyone caught in the splash,
regardless of distance from the impact point. Vanilla scales a splash
potion's strength down toward the edge of its radius; this is overridden
specifically for healing potions so it doesn't feel like a coin flip.

## Legacy Combat (1.8 PvP style)

`pvp.legacy-combat` restores a set of pre-1.9 PvP mechanics. Set
`enabled: true` and optionally scope it to specific `worlds` (empty list
= everywhere). Every sub-feature below has its own toggle:

| Setting | Effect |
|---|---|
| `attack-speed` (default 1024.0) | Effectively instant attacks — no cooldown bar. |
| `disable-sweeping-attacks` | Removes axe sweep damage entirely. |
| `legacy-weapon-damage` + `weapon-damage` table | Overrides a weapon's base attack damage with a flat value per material (e.g. diamond sword 8.0 vs. diamond axe 6.0) instead of the modern attribute-based amount. A material left out of the table keeps its normal damage. Enchantments, potion effects, and armor still apply normally on top. |
| `legacy-armor-calculations` | Cancels the armor toughness modern armor carries (the mechanic that gives diminishing returns against big hits), so armor reduction goes back to a flat, predictable percentage. |
| `projectile-knockback` (`fishing-rod`/`snowball`/`egg`) | Restores those hits pushing the target even though they deal no damage, which modern Minecraft removed. |
| `knockback` (`horizontal`/`vertical`/`sprint-bonus`) | Overrides vanilla's own knockback on every melee hit and every enabled projectile hit above. |
| `legacy-health-regen` | Replaces vanilla's fast, saturation-boosted regen with a flat 1 heart every 4 seconds (still gated on food level 18+, same as vanilla's own natural-regen requirement). |
| `legacy-golden-apples` + `golden-apple-effects` / `enchanted-golden-apple-effects` | Replaces vanilla's golden apple and enchanted golden apple effects entirely with an admin-defined list (each entry: `type`, `amplifier`, `duration-seconds`). An empty list for one apple type leaves that apple's vanilla effects alone. |

Sword blocking (1.8's right-click-to-block with a sword) is not
included — Minecraft removed it entirely in 1.9 in favor of shields, and
there's no reliable vanilla event to hang a faithful approximation on.

## Archer Tag

A player wearing a full **leather** armor set (the `archer` kit or its
donator variant, matched by armor material only — see
[Kits & Abilities](kits-and-abilities.md#class-detection)) marks whoever
their arrows hit.

- Each arrow hit adds a stack, up to `archer-tag.max-stacks`, and
  refreshes the mark's `duration-seconds`. Stacks are shared across every
  archer shooting that target — two archers focusing one target stack
  twice as fast.
- **Arrows** landing on a marked player deal
  `arrow-damage-bonus-per-stack` extra damage per stack. The arrow that
  *opens* the mark deals normal damage; the bonus only applies from the
  next arrow on.
- **Melee** hits get `faction-melee-bonus-per-stack` per stack, but only
  for members of the faction whose own archer put the mark there. A
  rival faction or a bystander deals normal melee damage no matter how
  deep the stack is. A factionless archer's mark gives *nobody* the melee
  bonus, including themselves — there's no faction to grant it to.
- Both the archer and the target get a chat message on every hit
  (`archer-tag.message-attacker` / `message-victim`, MiniMessage,
  configured in `config.yml` rather than `lang/*.yml` since it's a single
  admin-authored template, not a per-locale message) naming the other
  player, the current arrow/melee percentages, and seconds left.
- Archers can't mark their own faction members. A mark clears on death
  but deliberately survives a disconnect, so relogging isn't a way to
  shed it mid-fight.

## Faction compatibility

Every ability and PvP mechanic respects faction relationships:

- Hostile abilities (Pearl Stunner, Mage debuffs, Backstab, Archer Tag,
  etc.) cannot be used on faction members or allies.
- Portable Bard's buffs are the exception — they're designed to be shared
  with faction members.
- Melee combat itself is unaffected — it always works normally,
  regardless of relation (FactionsUUID's own claim/friendly-fire rules
  still apply as usual).
