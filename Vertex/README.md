# Vertex

Vertex is the network's Paper-compatible gameplay plugin. It provides native
factions, claims, shields, zones, events, shops, custom runes, boosters, and
the associated staff tools.

## Build

Build the deployable shaded JAR with Java 21:

```sh
./mvnw clean package
```

The output is `target/vertex-1.0.0.jar`.

The source and documentation are versioned; generated JARs remain local build
artifacts and are intentionally ignored by Git. Read the [open issues](../issues.md)
and [verification scope](../docs/audit-progress.md) before deployment. Shared
shards require an all-backend maintenance upgrade, not a mixture of old/new jars.

## Active configuration

- `config.yml`: core network and cannon settings.
- `factions.yml`: faction claims, shields, and other faction-owned settings.
- `runes.yml`: legacy Custom Enchant Rune tier materials/models, prices, roll tables, and the universal Lucky Gem price.
- `arena-runes.yml`: Haven/Riftlands Arena Rune price/currency and effect controls; Lucky Gems use the shared rule in `runes.yml`.
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

All staff region creation follows the same selector flow: the `create`
command gives the selector, left-click selects the first corner, right-click
selects the second corner, and sneak-clicking air saves it. There are no
standalone selector-wand commands.

Mine creation accepts only a preconfigured `mines.yml` template name, keeping
its ore table intact:

```text
/mines create <mine-template> [koth]
```

Shift-left-click in `/runes` or `/ce` confirms a purchase of up to 64 runes.
Shift-right-click opens a confirmation for the largest affordable amount that
fits in the player inventory. Shop, confirmation, and catalog menus reject
bottom-inventory transfers so they remain read-only outside their own controls.
