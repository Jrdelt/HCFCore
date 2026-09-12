# Vertex

Vertex is the network's Paper-compatible gameplay plugin. It provides native
factions, claims, shields, zones, events, shops, custom runes, boosters, and
the associated staff tools.

## Build

Build the deployable shaded JAR with Java 21:

```sh
./mvnw clean package -DskipTests
```

The output is `target/vertex-1.0.0.jar`.

## Active configuration

- `config.yml`: core network and cannon settings.
- `factions.yml`: faction claims, shields, and other faction-owned settings.
- `runes.yml`: legacy Custom Enchant Rune tiers, prices, cosmetics, and roll tables.
- `arena-runes.yml`: Haven/Riftlands Arena Runes, their currency, controls, and bonuses.
- `haven.yml` and `riftlands.yml`: each arena zone's configuration.
- `boosters.yml`: common booster caps and category behavior.

`claims.yml` and `shield.yml` are retired. Their settings now belong in
`factions.yml`.

## Zone administration

The `/haven` and `/riftlands` command roots own zone administration. Portal,
spawnpoint, KOTH, and Outpost actions are their subcommands; there is no
standalone Portal command class or separate portal-command registration.

Arena controls use these forms:

```text
/haven koth create <name>
/haven outpost create <name>
/riftlands koth create <name>
/riftlands outpost create <name>
```

Use Shift-left-click in `/runes` or `/ce` to confirm a purchase of up to 64
runes, or Shift-right-click to confirm the largest purchase permitted by the
player's current balance and inventory space.
