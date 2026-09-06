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
- **Faction aliases:** rally, permission, and upgrade commands work
  through every alias in `factions.command-aliases`; they no longer rely
  on Bukkit's unsupported multi-word command aliases.
- **Documentation coverage:** public commands, permission nodes,
  configuration roots, language categories, integrations, persistence,
  and the recent Blueprint/Collector/Faction Upgrade behavior are now
  cross-referenced in the README and `docs/`.

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

## Added in this update

- **Rally state items:** allowed **Set Rally** and **Clear Rally** use
  `GREEN_STAINED_GLASS_PANE`; denied permissions use red panes.
- **Permission GUI usability:** all GUI text/lore uses small caps, and
  `/f perms` plus `/f permissions` tab-complete (including configured
  faction-command aliases).
- **Blueprint holograms:** completed, cancelled, and aborted builds now
  delete their hologram instead of leaving it after the beacon is mined.
- **Collector custom withdrawals:** the valid amount is stored on the
  anvil result button, preventing vanilla's rename reset from turning a
  typed number into an invalid amount on click.
- **Legacy Blueprint schematics:** retired generic block ids (including
  `minecraft:bed`) no longer stop a whole build on current Paper; valid
  modern defaults are used with a one-time re-export warning.
- **Faction upgrades:** `/f upgrades` / `/f upgrade` now provides a
  persistent faction GUI for Damage in Claims, Claim Protection, Armor
  Wear, Fall Protection, Fly Boost, Faction Warps, Spawner Rate, Crop
  Growth, and Mob XP. Costs, level caps, and bonuses are configurable;
  all non-warp effects are restricted to the faction's own claim.
