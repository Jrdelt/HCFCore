# Vertex — Review Status

Reviewed and resolved 2026-09-06. Build and test checks pass.

## Fixed

- **Spawners:** decrement mode now leaves the remaining live stack intact;
  spawner PDC is reconciled on startup/chunk load after interrupted SQL
  writes; daylight fallback spawning works for supported spawners.
- **Collectors:** ownership is indexed without loading every old chunk;
  collector data is protected on claim, unclaim, overclaim, and disband;
  PDC material types are capped; permissions are rechecked in an open GUI.
- **Faction bank:** money and experience changes are serialized and saved
  durably before the operation completes; a failed deposit is refunded.
- **Blueprints:** legacy active anchors are migrated, GUI actions recheck
  faction/claim access, explosions clear active records and holograms,
  missing completed holograms are recreated, and active builds use a
  private schematic snapshot across restart.
- **Storage migration:** requires an idle server, drains writes, and keeps
  Blueprint row IDs so anchor PDC remains valid.
- **Mob stacking:** natural and spawner mobs no longer merge together, so
  their loot sources stay correct.
- **Faction upgrades/permissions:** explicit tier gaps warn clearly; stale
  faction permission settings are removed on disband.

## Notes for live testing

- Test an active Blueprint through a restart and through an explosion.
- Test `/f bank` with Vault enabled and a deliberately unavailable database
  in a staging environment to confirm the configured economy provider's
  refund behavior.
- Test `/f unclaim`, `/f unclaimall`, overclaim, and disband with both a
  spawner stack and a filled Chunk Collector.

## Verified

- `./mvnw -q test`
- `git diff --check`
