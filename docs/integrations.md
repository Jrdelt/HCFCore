# Integrations

Vertex has exactly one hard dependency. Every other plugin listed here is
optional — Vertex boots and runs fine without any of them, and each
missing integration simply turns off the specific feature it powers
rather than causing an error.

## FactionsUUID — required

The one hard dependency (`depend` in `plugin.yml`). Vertex refuses to
enable if it isn't present and enabled. Vertex reads faction identity,
claims, relations, power, roles, native permissions, the TNT bank, and
the native Warp upgrade through FactionsUUID's API. Vertex stores its own
upgrade levels, money/XP bank balances, rally permissions, and managed
block ownership in its selected database/configuration. It is built against
**FactionsUUID 4.4.0**; the 4.7.0 API has also been checked for the direct
methods Vertex uses. See
[Factions Integration](factions-integration.md) for the full picture.

## Vault — optional

Enables money costs. Specifically:

- A kit's `cost.money` requirement (see [Kits & Abilities](kits-and-abilities.md#cost)).
- Buying spawners from `/spawners` and selling them back.
- Buying Chunk Collector upgrade tiers.
- Buying faction-upgrade levels through `/f upgrades`.
- Depositing to or withdrawing from the faction **money** bank.
- The `{balance}` scoreboard placeholder.

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

- Rank display in chat and on the scoreboard (`{rank}`, `{rank_prefix}`,
  `{prefix}` placeholders, and `chat.rank-format`).
- The **Repair** ability, which grants a temporary LuckPerms permission
  node so a player can break/fix their own gear for a limited window.

Without LuckPerms, those placeholders resolve to nothing and the Repair
ability is disabled. LuckPerms' built-in `default` group is specifically
handled so a player in it shows a blank rank instead of the literal word
"default".

## PlaceholderAPI — optional

Lets `chat.*` and `scoreboard.lines` templates use `%placeholder%`
tokens — from LuckPerms' own PlaceholderAPI expansion, or any other
installed expansion — mixed in alongside Vertex's own `{curly}`
placeholders. They're expanded as a final pass over the fully-resolved
line, per viewing player.

Without PlaceholderAPI, any `%...%` token in a template is left as
literal text rather than being expanded.

## EssentialsX — optional

Wherever a player's name is shown — chat, scoreboard, the tags GUI
nickname preview, and default join/quit/death messages — their
EssentialsX `/nick` nickname is used instead of their real username, if
one is set.

Without EssentialsX, or for a player with no nickname set, the real
username is used as before.

## FastAsyncWorldEdit + DecentHolograms — optional, required together

Both are required for the **Blueprint Base Builder**
([full details](blueprints.md)) — FastAsyncWorldEdit is the only way
Vertex can load and paste a `.schem` file, and DecentHolograms provides
the required build-progress display. If either is missing, the feature
is never wired up (logged once at startup, no errors); it isn't a
partial/degraded mode, it's fully off until both are present.

## Summary table

| Plugin | Required? | Powers |
|---|---|---|
| FactionsUUID | **Yes** | Claims, roles/permissions, relations, chat/scoreboard/nametags, rallies, native TNT/Warps |
| Vault | No | Kit money costs, spawner/collector/faction-upgrade economy, faction money bank, `{balance}` |
| WorldGuard | No | Region-based ability and no-pearl restrictions |
| LuckPerms | No | Rank display, the Repair ability |
| PlaceholderAPI | No | `%placeholder%` support in chat/scoreboard templates |
| EssentialsX | No | Nickname display everywhere a player's name appears |
| FastAsyncWorldEdit | No (paired with DecentHolograms) | Loading/pasting Blueprint `.schem` files |
| DecentHolograms | No (paired with FastAsyncWorldEdit) | Blueprint build-progress display |
