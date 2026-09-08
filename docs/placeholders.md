# PlaceholderAPI Placeholders

Vertex does two separate things with PlaceholderAPI, and this page is
about the second one:

- It **consumes** `%placeholder%` tokens inside its own text templates
  (`chat.*`, tag `display` strings, and anywhere else a config value gets
  resolved) — that's any expansion already installed, LuckPerms' own or
  otherwise. See [Configuration](configuration.md) and
  [Integrations](integrations.md#placeholderapi--optional).
- It **provides** its own `%vertex_<key>%` tokens, listed below, for any
  other plugin to consume — a scoreboard/tablist plugin, a hologram
  plugin, a leaderboard plugin, anything that reads PlaceholderAPI.

Requires [PlaceholderAPI](integrations.md#placeholderapi--optional)
installed and enabled; without it, none of this is registered and the
placeholders simply don't exist. The expansion registers itself
automatically on startup — no `/papi ecloud` or manual install step
needed, since it ships inside Vertex.

Every placeholder here reads live off the same managers Vertex's own
commands and listeners use, so it can never show something stale or out
of sync with what a player would see in-game.

## Identity and rank

| Placeholder | Value |
|---|---|
| `%vertex_name%` | Player name — EssentialsX `/nick` nickname if set, otherwise the real username |
| `%vertex_rank%` | LuckPerms primary group display name, unwrapped |
| `%vertex_rank_prefix%` | Same, wrapped in `[brackets]` — blank if the player has no rank |
| `%vertex_prefix%` | The player's raw LuckPerms prefix meta (carries its own color codes, not escaped) |
| `%vertex_balance%` | Vault balance, formatted by whatever economy plugin is installed |

Blank (not an error) for any of these if LuckPerms/Vault isn't installed.

## Faction

| Placeholder | Value |
|---|---|
| `%vertex_faction%` / `%vertex_faction_tag%` | The player's faction tag, or `None` if factionless |
| `%vertex_faction_role%` | Their FactionsUUID role (`Recruit`, `Admin`, ...), or `None` |
| `%vertex_faction_power%` | Faction power, `current/max` |
| `%vertex_faction_ftop%` | The faction's rank on the power leaderboard |
| `%vertex_faction_online%` | Online members of the player's faction |
| `%vertex_faction_money%` | FactionsUUID's own native `/f money` balance — blank if that economy isn't enabled on this server |
| `%vertex_faction_bank_money%` | Vertex's own `/fbank` money balance (a separate ledger from `/f money` — see [Factions Integration](factions-integration.md)) |
| `%vertex_faction_bank_xp%` | Vertex's own `/fbank` experience balance |

## Combat

| Placeholder | Value |
|---|---|
| `%vertex_combat_tagged%` | `Yes` / `No` |
| `%vertex_combat_remaining%` | Seconds left on the current combat tag, `0` if untagged |
| `%vertex_combat_opponent%` | The tagged opponent's name, `the server` (see `/combattag ... server`), or `None` |

## Kit and ability cooldowns

Parameterized — append the kit name or ability id after the trailing
underscore. Resolves to `Ready` once the cooldown has expired, or blank
if the kit/ability name doesn't exist.

| Placeholder | Value |
|---|---|
| `%vertex_kit_cooldown_<kit>%` | Seconds left on that kit's cooldown, or `Ready` |
| `%vertex_ability_cooldown_<ability>%` | Seconds left on that ability's cooldown, or `Ready` |

Example: `%vertex_kit_cooldown_warrior%`, `%vertex_ability_cooldown_repair%`.
Names match exactly what `/kit <name>` and `/getitem <username> <ability>`
use — check `kits.yml` / `abilities.yml` if unsure.

## Staff

| Placeholder | Value |
|---|---|
| `%vertex_staff_vanished%` | `Yes` / `No` |
| `%vertex_staff_build%` | Whether the player currently has staff-build (claim-bypass) toggled on |

## Server

| Placeholder | Value |
|---|---|
| `%vertex_online%` | Online player count |
| `%vertex_date%` | Current date, `MM/dd` |
| `%vertex_reboot_scheduled%` | `Yes` / `No` |
| `%vertex_reboot_remaining%` | Seconds until the scheduled reboot, `0` if none is scheduled |
| `%vertex_storage_type%` | `local` or `mysql` |

## Backpacks

Reads the player's **equipped** Backpack (in their offhand); `None`/`0`
if they don't have one equipped.

| Placeholder | Value |
|---|---|
| `%vertex_backpack_tier%` | Display name of the equipped Backpack's tier, or `None` |
| `%vertex_backpack_stored%` | Items currently stored in it |
| `%vertex_backpack_capacity%` | Its capacity at its current level |

## Tags

| Placeholder | Value |
|---|---|
| `%vertex_tag%` | The player's currently-equipped cosmetic tag, pre-formatted with its own color/gradient — blank if none equipped |

## Blueprint Base Builder

| Placeholder | Value |
|---|---|
| `%vertex_blueprint_cooldown%` | Seconds left on the player's Blueprint placement cooldown, or `Ready` |

## Faction upgrades

Parameterized — append the upgrade's config key (see the `faction.
upgrades` section of [Configuration](configuration.md), or
`FactionUpgrade.java`) after `upgrade_`.

| Placeholder | Value |
|---|---|
| `%vertex_upgrade_<key>_level%` | The player's faction's level in that upgrade |

Example: `%vertex_upgrade_claim-damage_level%`,
`%vertex_upgrade_fly-boost_level%`. Valid keys: `claim-damage`,
`claim-protection`, `armor-wear`, `fall-protection`, `fly-boost`, `warps`,
`spawner-rate`, `crop-growth`, `mob-xp`.

## Rally

| Placeholder | Value |
|---|---|
| `%vertex_rally_active%` | `Yes` / `No` — whether the player's faction currently has an active rally point |

## Player Trading

| Placeholder | Value |
|---|---|
| `%vertex_trade_accepting%` | `Yes` / `No` — whether the player currently accepts incoming trade requests (`/tradetoggle`) |

## Economy modules (server-wide counts)

Not player-specific — the same value for anyone who reads it.

| Placeholder | Value |
|---|---|
| `%vertex_coinflip_active%` | Currently-listed, unresolved coinflips |
| `%vertex_auction_active%` | Currently-active Auction House listings |

## Sand Bots

| Placeholder | Value |
|---|---|
| `%vertex_sandbot_active%` | How many Sand Bots the player currently has active |

## What isn't covered yet

Spawners, Chunk Collectors, active KOTH/Outpost name, Shop per-item
prices, and coinflip/auction/trade *history* (as opposed to the live
counts above) don't have placeholders yet — most of these are
location- or listing-scoped rather than cleanly per-player, which is why
they were skipped rather than forced into a bad fit. Adding one is cheap
(a new `case` in
`me/vertex/core/placeholderapi/VertexPlaceholderExpansion.java`, reading
off whichever manager already backs the equivalent command) — ask for
whichever ones you actually need surfaced and they can be added the same
way as the set above.
