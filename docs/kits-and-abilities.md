# Kits & Abilities

## Kit classes

Six armor-based classes ship in `kits.yml`, each with a free tier and a
permission-gated `-donator` tier (better enchantments, larger cooldowns
on the ability items it hands out, and a longer reuse cooldown on the kit
itself).

| Class | Armor | Free tier gives | Passive class effects | Ability items |
|---|---|---|---|---|
| **Archer** | Leather | Bow (Flame + Infinity), 16× Rabbit's Feed, 16× Jump Boost Fruit | Speed II, Jump Boost II | Rabbit's Feed, Jump Boost Fruit |
| **Miner** | Iron | Iron pickaxe, 32 torches | Haste III, Night Vision | — |
| **Bard** | Gold | 2× golden apple, 16× Portable Bard | Speed II, Regeneration II, Resistance II | Portable Bard |
| **Diamond** | Diamond | Diamond sword, Speed splash potion, Instant Health splash potion, 2× golden apple | Speed I, Invisibility | — |
| **Rogue** | Chainmail | 8× Gold Dagger (backstab), 16× Ninja Star | Speed III, Jump Boost I | Rogue Backstab, Ninja Star |
| **Mage** | Gold helmet/boots + Chainmail chest/legs | 16× each Wither/Slowness/Poison spell item | Speed II, Invisibility, Jump Boost II | Mage debuffs (×3) |

Donator tiers keep the same armor materials with added `UNBREAKING`,
larger ability-item stacks, and a much longer kit cooldown (5–10 minutes
instead of 30 seconds) in exchange for the permission gate — see
`kits.yml` for exact numbers per class.

### Class detection

Two different matching rules are used, deliberately:

- **Exact match** (`KitManager`) decides whether a kit's passive
  `effects` apply — every armor piece's material, enchantments, *and*
  durability must match the kit definition precisely. A diamond kit
  won't match enchanted or already-damaged diamond armor; this is what
  requires the warmup below.
- **Loose, material-only match** (`ArmorClass`) is used where gameplay
  needs to recognize "is this player generally an Archer/Bard/Mage"
  regardless of which tier or how worn the armor is — Portable Bard's
  buff doubling, Mage spell doubling, and Archer Tag eligibility all use
  this. Durability damage taken in a fight, or a donator's extra
  enchantments, must not silently drop a player out of their class
  mid-fight.

### Passive effects, warmup, and armor matching

Equipping a kit's *exact* full armor set (same material, enchantments,
and durability on all four pieces) grants its `effects` after a
`kits.effect-warmup-seconds` delay (default 5s) — giving visual feedback
and preventing instant on/off effect flicker. Taking off any piece
removes the effects immediately, no warmup on the way out.

When topping up an already-active kit's effects, only effect *presence*
is checked, not amplifier — an external potion effect (PvP, milk bucket)
sharing an effect type with the kit legitimately overrides it without
being treated as the kit effect having "fallen off" and re-triggering the
warmup mid-fight.

### Cost

A kit can optionally require money, an item, or both before it's handed
out:

```yaml
cost: {money: 50000}                          # money only
cost: {item: DIAMOND, item-amount: 32}         # item only
cost: {money: 50000, item: DIAMOND, item-amount: 32}   # both
```

None of the six shipped classes use a cost by default. Money costs
require Vault; without it, only free/item-cost kits work (see
[Integrations](integrations.md)). `vertex.kit.bypasscost` skips this
entirely for staff testing.

### Permission

Leaving `permission` out of a kit entry does **not** make it open to
everyone — it defaults to `vertex.kit.<name>`. The six free classes
explicitly set `permission: ''` (empty string) to open them to all
players; the `-donator` variants set an explicit permission node instead.
`vertex.kit.bypasscooldown` skips a kit's cooldown for staff testing.

### Creating and managing kits

1. Equip the armor and hold the items you want in your kit.
2. Run `/kit create <name> [permission] [cooldownSeconds] [cost]
   [costItem[:amount]]` (permission `vertex.kit.create`) — this saves
   your current gear as a new kit. The older `/kit save` alias still
   works (`vertex.kit.save`).
3. Open `kits.yml` to add `effects`, an `icon` override (defaults to the
   kit's first armor piece if omitted), or a `purpose` line shown in the
   `/kits` GUI.
4. Run `/vertex reload` — no restart needed.

`/kit delete <name>` (`vertex.kit.delete`) removes a kit.

`/kits` opens a fixed 4-row GUI: the six base kits fill row 2, each
one's `-donator` variant sits directly below it in row 3 (same column).
Left-click claims a kit respecting its cooldown/cost; right-click
previews its contents read-only without claiming it.

## Abilities

`abilities.yml` defines every ability item's material, name, lore, and
mechanics-specific settings (cooldown, duration, damage, multipliers,
etc.). All 21 catalog entries below are individually giveable through
`/getitem <username> <ability> [amount]` (`vertex.ability.give`), though
the five `bard-buff-*` entries are normally only obtained by picking a
buff from the Portable Bard menu rather than handed out directly.

| Ability | Trigger | Effect |
|---|---|---|
| Pearl Stunner | Melee hit | Blocks the victim's pearl use for `stun-seconds` |
| Rabbit's Feed | Right-click | Speed V for `speed-duration-seconds` |
| Jump Boost Fruit | Right-click | Jump Boost V for `jump-duration-seconds` |
| Anti-Blockup Bone | Melee, `hits-required` hits | Denies the victim block placement for `deny-seconds` |
| Fake Pearl | Right-click | Looks and throws exactly like an ender pearl, but never teleports — bait your enemy |
| Grappling Hook | Right-click | Fishing-hook pull toward a hooked block/player, `uses` charges, no fall damage on landing |
| Leap | Right-click | Forward + upward launch with a speed buff on landing |
| Rogue Backstab (Gold Dagger) | Melee, from behind | Flat `damage` bonus; item is consumed on a successful hit |
| Mage: Wither / Slowness / Poison | Melee | Applies the named effect; doubled duration and amplifier when worn in the full Mage set, normal otherwise |
| Portable Bard | Right-click | Opens a menu to pick one buff (see below) |
| Bard buffs (Speed/Strength/Resistance/Regeneration/Jump Boost) | Right-click | Buffs everyone in `abilities.bard-share-radius-blocks` (default 30) of the same faction, same world; doubled duration/level in the full Bard set, halved otherwise (never below level I / 1s) |
| Repair | Right-click | Grants temporary permission to repair gear, with a countdown shown on the scoreboard (needs LuckPerms) |
| Switcher Snowball | Throw | Swaps positions with whoever it hits |
| Time Warp Pearl | Right-click | Teleports back to the last location you actually ender-pearled from |
| Ninja Star | Right-click | Teleports to whoever hit you last, after a 5s warning to them — needs that hit within the last 15s and both of you currently in combat; grants Regeneration II, Strength III, Speed V for 3s on arrival |

**Portable Bard in detail:** opening the menu and picking a buff consumes
one Portable Bard from your inventory and hands you that buff item
instead of applying it directly — the menu stays open until you're out of
Portable Bards, so holding several lets you pick a buff for each one in a
row. The master item itself has no cooldown; each buff item is its own
ability with its own cooldown (7s by default), spent only once actually
used.

### Material choice matters for right-click abilities

A right-click ability's `material` must be something Minecraft's client
recognizes as having its own right-click action — a bow, fishing rod,
ender eye/pearl, firework rocket, anvil/any placeable block, or any
edible item all qualify. A material with no vanilla right-click behavior
at all (a plain feather, rabbit's foot, nether star, stick, etc.) only
sends the interact packet when the player is looking at a block —
right-clicking open air does nothing client-side, before the plugin's
listener ever sees it. This is why Ninja Star, Rabbit's Feed, and Jump
Boost Feather use a compass, golden carrot, and chorus fruit rather than
a nether star, rabbit's foot, or plain feather — the swap changes nothing
about cooldown, lore, or behavior, only what the item looks like in hand.

### Cooldowns and restrictions

- Items are only consumed on **successful** activation.
- `/cooldowns` shows every active cooldown: kits, ability items, the
  shared global ability cooldown, and the vanilla pearl/gapple/enchanted
  gapple timers.
- **Global cooldown** (`abilities.global-cooldown-seconds`, default 4s)
  blocks all ability items while active, on top of each item's own
  cooldown.
- Abilities are disabled in two independent, additive ways:
  - `abilities.disabled-regions` — WorldGuard region names (no effect
    without WorldGuard).
  - `abilities.disabled-claim-names` — faction claim names abilities
    can't be used in (default: `safezone`); a **blocklist**, not an
    allowlist, so abilities work in the wilderness and any other
    faction's claimed land. Matches case-insensitively, so it also
    covers the system SafeZone/WarZone factions if you've kept their
    default names.

### `/abilities` and `/getitem`

`/abilities` opens a browsing GUI listing every ability's name and lore.
A viewer with `vertex.ability.give` who clicks one receives a copy;
anyone else's click does nothing. `/getitem` is the direct command-line
equivalent, ignoring cooldowns entirely (capped at
`abilities.max-getitem-amount` per call).
