# Vertex — Review Status

Updated 2026-09-06 after applying the approved review answers. Full test
suite is run before each release build.

## Fixed

- **Storage migration:** draining writes no longer shuts down Tags or Death
  saving. The `storage.type` config change is now written atomically, so a
  crash cannot leave a partial `config.yml`.
- **Kit saves:** `/kit save` and `/kit delete` return without blocking the
  server thread. Pending saves now flush correctly during shutdown.
- **Ninja Star:** it rechecks both players at teleport time and cancels when
  either is in a configured protected/no-pearl region.
- **Ghost Players:** only kicked, timed-out, or erroneous disconnects can
  spawn an NPC. Voluntary logouts use the normal combat-logout penalty.
  Failed Citizens spawns now remove their saved record safely.
- **Ranked tab list:** grouped rank sections are enabled by default, use the
  highest LuckPerms group weight, refresh immediately on join, retain
  colored prefixes, and use an Essentials nickname when available.
- **Nametags:** disabling nametags with `/vertex reload` removes Vertex's
  old scoreboard teams instead of leaving stale colors behind.
- **Chunk Collectors:** exactly 24 material types are supported and every
  type has a visible withdrawal slot. A new 25th tagged farm-drop type is
  destroyed to prevent item/PDC lag.
- **Spawner selling:** a spawner stack is not reduced if the Vault refund
  fails.
- **Anti-Blockup Bone:** waits for player data to load before applying its
  effect or consuming the item, so its cooldown cannot be bypassed on join.
- **Dependency checks:** startup and `/vertex reload` verify required and
  optional integrations. A reload can enable Blueprints or Ghost Players
  after their dependencies become available.
- **Documentation:** Backpack drop bonuses, collector limits, Ghost Player
  behavior, safezone Ninja Stars, grouped tab formatting, reload behavior,
  and atomic storage migration documentation are current.

## Intentional behavior

- **Co-Leaders** share Vertex custom permissions with faction leaders; only
  FactionsUUID's native disband rule remains different.
- **Collector capacity reductions** are an offseason/reset-only operation.
- **Chunk Collectors** handle automatic farm drops, not manually peeled mob
  stack kills.
- **Invsee armor-slot handling** is accepted as-is.
