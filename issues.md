# Vertex — Audit Status

Reviewed: 2026-09-05. The full code audit is complete. There are no
known open code defects from this audit; `./mvnw test` and packaging pass.

## Fixed

- **Spawner upgrades:** older databases automatically gain the missing
  `owner_faction` column before spawners load.
- **Ability cooldowns:** writes are ordered per player and ability, so an
  older async write cannot overwrite a newer cooldown.
- **Tag reloads:** reload waits for the queued tag save first.
- **Blueprint ownership:** active builds store the faction's stable id,
  not its renameable tag. Claim checks and holograms use that id.
- **Scoreboard performance:** faction-top ranks are calculated once per
  scoreboard update, not once per viewer.
- **Spawner economy:** invalid negative prices and refund percentages over
  100 are clamped and logged.
- **Faction aliases:** permission commands work through every alias in
  `factions.command-aliases`.

## Manual server checks

- Before releasing a new Blueprint template, use the checklist in
  [docs/blueprints.md](docs/blueprints.md#staging-checklist): normal
  build, faction rename, claim loss, restart/resume, corrupt schematic,
  and database outage.
- FactionsUUID Admin is intentionally absent from the GUI: that plugin
  always lets Admin bypass its role matrix, so there is nothing reliable
  for Vertex to deny.
- **CI setup:** the build workflow is ready locally, but GitHub rejected
  its upload because the configured OAuth token lacks `workflow` scope.
  Refresh that token with the scope, then add `.github/workflows/build.yml`.

## Requested future feature

- **Faction upgrades GUI:** Damage in claims, protection, armor wear,
  fall protection, fly boost, warps, spawner rate, crop growth, and mob
  XP upgrades. This is a new feature request, not an active bug fix.

## Added in this update

- **Rally state items:** allowed **Set Rally** and **Clear Rally** use
  `GREEN_STAINED_GLASS_PANE`; denied permissions use red panes.
- **Permission GUI usability:** all GUI text/lore uses small caps, and
  `/f perms` plus `/f permissions` tab-complete (including configured
  faction-command aliases).
