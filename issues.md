# Vertex — Review Status

Reviewed 2026-09-06. Build and tests pass (135 tests).

## Fixed

- **Blueprints:** partial builds and repairs no longer refund a free Blueprint;
  preview holograms include their world; rebooted builds pause for GUI resume;
  snapshot bounds are used for claim checks; and schematic size, block, and
  dimension limits are configurable in `blueprints.yml`.
- **Collectors:** startup reconciles every already-loaded chunk; storage PDC,
  tier, capacity, and material count are capped safely; and lookup is indexed
  by chunk instead of scanning all Collectors for each drop/hopper check.
- **Spawners:** recovered PDC data is indexed on startup; overclaiming now
  immediately reapplies the new faction's spawner-rate upgrade; daylight
  spawning works while lava remains a valid kill method.
- **Mob stacks:** periodic merging now uses nearby spatial cells rather than
  comparing every tracked mob in a world with every other tracked mob.
- **Faction upgrades:** auto-disband deletes saved upgrade levels as well as
  regular disband.
- **Rollback history:** all expired death records are pruned globally every
  six hours instead of waiting for that player to die again.
- **Faction permissions GUI:** Set Rally / Clear Rally now render as the same
  green/red stained-glass pane as every other permission, instead of a
  Beacon/Barrier that read as "blocked" even when allowed. All of its lore
  and labels are rendered in small caps to match the rest of the plugin's
  GUIs, and `/f perms` / `/f permissions` are now tab-completeable off `/f`
  (they previously ran fine but weren't suggested). If you're still seeing
  the old icons or no tab-completion in-game, the server is running a jar
  built before this fix — redeploy.
- **Anvil amount prompts (Collector withdraw, faction bank deposit/withdraw):**
  the input item no longer has a display name, so the anvil's rename box now
  starts genuinely blank instead of pre-filled with a "Type Amount" prompt
  the player had to select and clear first. The prompt text moved to the
  item's lore instead. The zero-cost override is now applied *after*
  `PrepareAnvilEvent#setResult()` rather than before, in case a result swap
  was silently resetting it back to a nonzero "Enchantment Cost" on some
  Paper builds.
- **Upgrade write-failure race:** the purchase lock for a given faction+upgrade
  now stays held until a failed purchase's rollback has actually finished
  applying, not just until the failed database write completes. Previously,
  releasing the lock immediately on write failure left a window where a
  second purchase could price itself off the about-to-be-reverted level,
  letting a faction end up one level higher than what was actually paid for.
- **Faction rename and managed blocks:** a new listener retags every Spawner
  and Chunk Collector a faction owns to its new tag the moment
  `FactionRenameEvent` fires (uncancelled), before FactionsUUID applies the
  rename. A renamed faction's managed blocks no longer risk becoming
  unclaimable-on-disband or invisible to overclaim handling because their
  stored tag stopped matching.
- **Collectors after faction disband (decision made):** Chunk Collectors now
  follow the same policy Spawners already used — a single chunk with an
  active Collector can't be `/f unclaim`'d, and `/f unclaimall`/disband/
  auto-disband drop every Collector in the released land as an item instead
  of leaving it behind in unclaimed territory with a stale faction tag. This
  was chosen over "give to the former leader" (no clear recipient on
  auto-disband or an offline leader) and a manual staff-recovery command
  (extra manual work with no upside once the item just drops normally).
- **Kit cooldown example:** `config.yml`'s `max-cooldown-seconds` was the
  string `86400//2`, which Bukkit silently rejects in favor of the code's
  own 86,400-second default. The `//2` was clearly meant to halve it, so
  it's now the plain number `43200` (12 hours) rather than a string Bukkit
  can't parse.

## Needs an operator decision (not a code bug)

These depend on choices specific to your server's economy and your own
FactionsUUID config, not something Vertex's code can decide for you.

- **Warp-level mismatch:** Vertex offers 15 Warps levels, but FactionsUUID's
  own `warps` setting has `maxLevel: 1` with only a level-1 count defined.
  Vertex correctly writes the native `WARPS` level as players buy it, but
  levels 2–15 can't grant more warps until FactionsUUID's own config has
  matching levels/counts. Raise `maxLevel` and add per-level warp counts to
  FactionsUUID's config to match (or below) Vertex's 15 levels before
  selling them.
- **Overlapping native upgrades:** FactionsUUID has native versions of Damage
  Boost, Damage Resistance, Armor Durability, Fall Reduction, Growth, Mob XP,
  and Spawner Rate; Vertex implements those same effects independently.
  Disable FactionsUUID's native duplicates in its own config to use Vertex's
  pricing/lore instead, or leave both on deliberately if you want them to
  stack — just don't leave both on by accident, since nothing currently
  warns if they overlap. Native Flight can stay enabled regardless; Vertex's
  Fly Boost only changes the speed of players already flying, it doesn't
  grant flight itself.
- **Native FactionsUUID upgrade menu:** Vertex claims `/f upgrades`/`/f upgrade`
  for its own persistent GUI, so players can no longer reach FactionsUUID's
  native upgrade menu through that command. Since Vertex now implements
  every native upgrade type FactionsUUID has (including Warps), there's
  currently nothing native left that needs a separate access path — revisit
  this only if FactionsUUID ships an upgrade type Vertex doesn't cover.

## Remaining limits / live tests

- Blueprint parsing is still synchronous because FAWE clipboard access is not
  safe to move blindly off the server thread. The new limits prevent runaway
  files and builds, but a deliberately large allowed schematic can still make
  one short loading pause. Test your largest approved schematic on staging.
- Test a partial Blueprint cancel, repair cancel, restart/resume, and a
  same-coordinate preview in two worlds.
- Test a PDC-only Collector/Spawner after a controlled restart, plus spawner
  rate immediately after an overclaim.
- Test a full Collector with malformed/oversized PDC values only on staging;
  excess corrupt values are safely discarded to retain a usable Collector.
- Test one purchase of every Vertex faction upgrade in a claim, then verify
  the matching FactionsUUID native duplicate is disabled. For Warps, test
  the exact native FactionsUUID level/count mapping after configuration is
  aligned.
- Test a faction rename with active Spawners/Collectors, and confirm both
  still recognize the faction as owner (overclaim, unclaim, disband) under
  its new tag.
- Test `/f unclaim` on a chunk with an active Collector (should be blocked),
  and `/f unclaimall`/disband/auto-disband with Collectors present (should
  drop them and message the leader if online).

## Verified

- `./mvnw -q -o clean test` — 135 tests, 0 failures, 0 errors
- `./mvnw -q -o clean package` — builds `vertex-1.0.0.jar` cleanly

---

## Newly requested — addressed this pass

- **Faction permissions GUI polish:** Set Rally/Clear Rally green-glass icons,
  small-caps lore/labels throughout, and `/f perms`/`/f permissions`
  tab-completion. All three were already implemented in the current source
  when asked about again — see the "Faction permissions GUI" entry under
  Fixed above for what was actually still missing (the config.yml material
  override was reverted to Beacon/Barrier and has been corrected back to
  green stained glass) and why this may still look unfixed in-game (a stale
  jar).
- **Anvil amount-entry UX (Collector withdraw; faction bank deposit/withdraw
  for money, XP, and TNT):** removed the pre-filled placeholder text so the
  amount field starts blank and typing works immediately, and moved the
  zero-cost override to run after the result item is set so the
  "Enchantment Cost" label can't reappear from a result-triggered cost
  recalculation. See the matching entry under Fixed above.
