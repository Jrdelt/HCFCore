# Integrations

Vertex has no hard plugin dependency beyond Paper. Every plugin listed here is
optional — Vertex boots and runs fine without any of them, and each
missing integration simply turns off the specific feature it powers
rather than causing an error.

## Native factions

Vertex owns faction identity, claims, relations, power, roles, permissions,
homes, warps, chat, upgrades, banks, and managed-block ownership. No external
faction plugin is used. See [Native Factions](factions-integration.md).

## Vault — optional

Enables money costs. Specifically:

- A kit's `cost.money` requirement (see [Kits & Abilities](kits-and-abilities.md#cost)).
- Buying spawners from the Spawners & Mob Drops category in `/shop` and selling them back.
- Buying Chunk Collector upgrade tiers.
- Buying faction-upgrade levels through `/f upgrades`.
- Depositing to or withdrawing from the faction **money** bank.
- The `{balance}` placeholder in chat.

Without Vault, only free or item-cost kits work, spawners/collectors that
require a purchase can't be bought, faction money transactions and paid
upgrade levels are unavailable, and `{balance}` resolves to nothing. Faction
XP and TNT bank operations do not require Vault.

## WorldGuard — optional

Enables region-based restrictions:

- `abilities.disabled-regions` — ability items can't be used inside
  listed WorldGuard regions.
- `pvp.no-pearl-regions` — ender pearls can't be thrown from or land
  inside listed regions.

Without WorldGuard, both settings are silently ignored (region names in
config have no effect) — the equivalent faction-claim-name restrictions
(`disabled-claim-names`, `no-pearl-claim-names`) still work regardless,
since those don't need WorldGuard.

## LuckPerms — optional

Enables:

- Rank display in chat (`{rank}`, `{rank_prefix}`, `{prefix}`
  placeholders, and `chat.rank-format`).
- The **Repair** ability, which grants a temporary LuckPerms permission
  node so a player can break/fix their own gear for a limited window.

Without LuckPerms, those placeholders resolve to nothing and the Repair
ability is disabled. LuckPerms' built-in `default` group is specifically
handled so a player in it shows a blank rank instead of the literal word
"default".

## PlaceholderAPI — optional

Two directions. Lets `chat.*` templates use `%placeholder%` tokens —
from LuckPerms' own PlaceholderAPI expansion, or any other installed
expansion — mixed in alongside Vertex's own `{curly}` placeholders.
They're expanded as a final pass over the fully-resolved line, per
viewing player.

Vertex also registers its own expansion, providing `%vertex_...%`
tokens (faction stats, cooldowns, combat status, the faction bank, ...)
for any other installed plugin to read — a scoreboard/tablist plugin in
particular. See [Placeholders](placeholders.md) for the full list.

Without PlaceholderAPI, any `%...%` token in a template is left as
literal text rather than being expanded, and Vertex's own tokens simply
don't exist for other plugins to use.

## EssentialsX — optional

Wherever a player's name is shown — chat, the tags GUI nickname preview,
and default join/quit/death messages — their EssentialsX `/nick`
nickname is used instead of their real username, if one is set.

Without EssentialsX, or for a player with no nickname set, the real
username is used as before.

## FastAsyncWorldEdit + DecentHolograms — optional, required together

Both are required for the **Blueprint Base Builder**
([full details](blueprints.md)) — FastAsyncWorldEdit is the only way
Vertex can load and paste a `.schem` file, and DecentHolograms provides
the required build-progress display. If either is missing, the feature
is never wired up (logged once at startup, no errors); it isn't a
partial/degraded mode, it's fully off until both are present.

## FancyNPCs — optional

Powers Sand Bot visual displays and their click-to-manage control panel.
FancyNPCs must be installed and enabled before a Sand Bot can be placed.
Ghost Players are native killable Villagers and do not require an NPC plugin.

## Summary table

| Plugin | Required? | Powers |
|---|---|---|
| Native Vertex factions | Built in | Claims, roles/permissions, relations, chat, rallies, TNT, and warps |
| Vault | No | Kit money costs, spawner/collector/faction-upgrade economy, faction money bank, `{balance}` |
| WorldGuard | No | Region-based ability and no-pearl restrictions |
| LuckPerms | No | Rank display, the Repair ability |
| PlaceholderAPI | No | `%placeholder%` support in chat templates, and Vertex's own `%vertex_...%` tokens for other plugins |
| EssentialsX | No | Nickname display everywhere a player's name appears |
| FastAsyncWorldEdit | No (paired with DecentHolograms) | Loading/pasting Blueprint `.schem` files |
| DecentHolograms | No (paired with FastAsyncWorldEdit) | Blueprint build-progress display |
| FancyNPCs | No | Sand Bot displays and controls |
