Vertex Addons — Haven & Riftlands Zone System / Mines teleport option in the gui follow same entry method as the Haven and riftland


Build the following Haven / Riftlands system into the existing Vertex Addons plugin.

This is not a standalone throwaway plugin. Before adding new managers/listeners/storage, inspect and reuse the existing Vertex systems wherever possible, especially:

• FactionsUUID integration
• combat-tag system
• combat logger
• /freeze
• Backpack system and Backpack mob-drop boosters
• GUI framework
• custom item/PDC identity system
• existing Blaze Rod region-selection tool
• PlaceholderAPI integration
• existing message system / en_us.yml
• existing reload/config architecture
• existing loot protection if one already exists
• existing PvP Arena logic
• existing server/world region utilities

Do not duplicate an existing service just because this specification mentions it.

────────

1. Zone Names and Core Identity

There are two zone types:

Haven

Haven is the PvE-only / PvP-disabled zone.

Players enter Haven to fight zone-generated mobs and grind rewards without PvP risk.

Riftlands

Riftlands is the PvPvE / PvP-enabled zone.

Players fight the same style of zone-generated mobs while also being vulnerable to other players.

Riftlands should inherently be more rewarding than Haven through configuration, scoring, progression rewards, and/or loot tuning.

Command Naming

Keep the commands the players/admins have already been using:

```text
/haven
/Riftlands
```

Display names are:

```text
Light Zone -> &a&lHaven
Dark Zone  -> <dark_gray><bold>Riftlands
```

────────

2. Defining Haven and Riftlands Regions

Reuse Vertex’s existing Blaze Rod region-selection system.

Do not build a second generic region wand if the current Blaze Rod selector can be extended.

Admin workflow should allow selecting two corners and finalizing the selected cuboid/region as either:

/haven claim wand
/riftlands claim wans

```text
HAVEN
RIFTLANDS
```

The existing Vertex Blaze Rod interaction pattern should be preserved where practical:

• select corner 1
• select corner 2
• shift-click / existing finalize interaction
• assign/save the region

Support multiple regions of each type.

Each region needs a persistent internal ID/name.

Example admin concepts:

```text
/haven region create <name>
/Riftlands region create <name>

/haven region list
/Riftlands region list

/haven region delete <name>
/Riftlands region delete <name>
```

all tab completeable

The exact command tree may adapt to the existing Vertex command framework.

Region Rules

Haven and Riftlands regions must not overlap.

If an admin attempts to save an overlapping region:

• refuse the save
• clearly explain which existing region conflicts
• do not partially write region state

Persist region definitions through reboot/reload.

A season reset should not delete region definitions unless explicitly configured.

────────

3. Zone Gameplay Restrictions

Inside Haven and Riftlands:

• players cannot break blocks
• players cannot place blocks
• players are there to fight zone mobs
• Riftlands additionally allows player-versus-player combat
• Haven does not

Do not allow building/destruction exploits through:

• normal block break/place
• buckets
• pistons triggered by players if applicable
• custom block placement items
• explosion-based player building bypasses
• plugin abilities that modify terrain

Reuse existing protection logic when possible.

────────

4. PvP Rules

Haven

PvP is disabled.

This includes guided-flight entry and normal ground gameplay.

A player in Haven should not be able to damage another player in Haven.

Riftlands

PvP is enabled.

Players may fight:

• on the ground
• during the guided entry flight
• while farming mobs

Use the existing combat module and FactionsUUID relationship logic.

Do not create a second combat-tag implementation.

────────

5. Entering a Zone

Players enter with:

```text
/haven
/Riftlands
```

Running the command does not teleport immediately.

Instead, open a small configurable confirmation GUI.

Confirmation GUI

The main confirmation item is an Emerald Block by default.

Example:

```yml
entry-gui:
  confirm-item:
    material: EMERALD_BLOCK
```

The Emerald Block must have configurable:

• material
• custom model data
• name
• lore
• glow
• slot
• click sound
• GUI title
• GUI size
• filler items if used

The lore should explain:

• which zone the player is entering
• Haven = PvP disabled
• Riftlands = PvP enabled
• a 5-second countdown starts after confirmation
• movement/damage/combat cancels the countdown

Use modern clean GUI styling.

All player-facing GUI text should be configurable.

────────

6. Entry Countdown

When the player clicks the Emerald Block:

• close the GUI
• begin a default 5-second countdown
• show the countdown in chat
• after the countdown succeeds, start the zone guided flight

Default:

```yml
entry:
  countdown-seconds: 5
```

Example message progression:

```text
Teleporting to Riftlands in 5...
Teleporting to Riftlands in 4...
Teleporting to Riftlands in 3...
Teleporting to Riftlands in 2...
Teleporting to Riftlands in 1...
```

Messages belong in en_us.yml.

Countdown Cancellation

Cancel entry immediately if the player:

• moves from the starting location
• takes damage
• becomes combat-tagged
• dies
• disconnects
• changes world
• teleports by another source
• otherwise becomes invalid for zone entry

If cancelled:

• do not start the guided flight
• do not apply a re-entry cooldown
• player may try again
• show a configurable cancellation message
• avoid countdown message spam

Combat Check

A player who is already combat-tagged must be denied from zone entry.

Check combat state:

1. when /haven or /Riftlands is run
2. when the GUI confirm button is clicked
3. immediately before guided flight begins

If combat begins during countdown, cancel the entry.

────────

7. Guided Flight Entry

Use server-controlled player flight, not Phantom entities.

The player should smoothly follow an admin-defined route inside the selected zone.

The player:

• may look around freely
• cannot steer away from the route
• is moved smoothly along the configured waypoints
• can voluntarily leave the route with left-click or right-click
• automatically leaves at the route’s configured end if they never click

Do not rely on client flight permission alone as the path-following mechanism.

Use server-authoritative route movement.

Preserve Existing Flight State

Before temporary guided flight starts, record relevant existing flight state.

When route control ends:

• do not accidentally grant permanent flight
• do not remove legitimate flight granted by another Vertex system
• restore the correct previous state

────────

8. Route Creation

Each zone region may have multiple manually defined flight routes.

A route must:

• start inside its claimed Haven/Riftlands region
• remain inside the same region for its entire path
• end inside the same region
• never cross outside the arena

Use the existing Blaze Rod tooling where possible for route waypoint editing.

Example commands:

```text
/haven route create <name>
/Riftlands route create <name>

/haven route edit <name>
/Riftlands route edit <name>

/haven route delete <name>
/Riftlands route delete <name>

/haven route list
/Riftlands route list

/haven route preview <name>
/Riftlands route preview <name>
```

Route Editing Concept

In route-edit mode:

```text
Left Click    = add waypoint
Right Click   = remove last waypoint
Shift + Click = finish/save
```

Adapt exact controls if Vertex’s existing selector already defines an established editing style.

Store full waypoint position including Y.

Example:

```yml
entry-routes:
  north:
    enabled: true
    speed: 0.8
    auto-drop-at-end: true
    waypoints:
      - world: world
        x: 100.5
        y: 110.0
        z: -220.5
      - world: world
        x: 125.5
        y: 105.0
        z: -195.5
      - world: world
        x: 160.5
        y: 95.0
        z: -160.5
```

Route Validation

Before saving:

• every waypoint must be inside the target zone region
• the interpolated route between waypoints must remain inside the region
• reject routes that clip outside the selected arena
• reject unsafe/invalid route definitions
• do not save partial invalid state

Provide an admin preview mode.

────────

9. Route Selection

When a player successfully enters a zone, choose one enabled route for that zone.

For Riftlands, prefer a route whose entry area is not crowded with hostile players.

Use FactionsUUID to determine potentially hostile players where relevant.

Avoid repeatedly dumping players into the same predictable ambush point if multiple valid routes exist.

Route selection behavior should be configurable.

────────

10. Leaving the Flight

A player may leave the guided route by:

• left-clicking
• right-clicking
• reaching the route end
• being hit by another player in Riftlands

Once released:

• the flight route is permanently ended for that zone entry
• the player cannot reattach to it
• immediately apply Slow Falling
• keep Slow Falling until the player reaches the ground
• remove Slow Falling promptly once safely grounded

Do not use a fixed short duration that might expire while the player is still falling from a high route.

Track ground arrival safely.

────────

11. Riftlands PvP During Flight

Players are not invulnerable during guided flight.

In Riftlands:

• player may attack other players from the air
• other players may attack them
• normal combat tagging applies

If the flying player is hit:

1. apply the hit normally
2. apply normal combat-tag logic
3. immediately release the player from the guided route
4. preserve natural knockback as much as possible
5. apply Slow Falling
6. continue normal Riftlands PvP from that point

In Haven, PvP remains disabled during flight.

────────

12. Disconnect During Guided Flight

If a player disconnects during guided flight without combat:

• end that entry flight permanently
• on next login, place them safely on the ground near the X/Z where they disconnected
• keep the destination inside the same zone region
• do not put them back onto the flight route
• do not leave them floating
• do not leave temporary flight enabled

If the player disconnects while combat-tagged:

• existing combat logger rules take priority
• do not bypass combat logger punishment through the flight system

────────

13. Zone Re-Entry Cooldowns

Normal leaving does not create a re-entry cooldown.

Players may leave a zone and immediately enter again if otherwise eligible.

Death Cooldown

Any death inside a zone triggers that zone’s configured death re-entry cooldown.

The cause does not matter.

Examples:

• mob death
• PvP death
• fall death
• environmental death
• other valid death

Haven and Riftlands must have independent values.

Example:

```yml
reentry-cooldown:
  on-death-seconds: 120
```

Each zone stores its own value in its own configuration.

Setting the value to 0 disables that zone’s death re-entry cooldown.

────────

14. Leaving via /spawn

Players may leave the zones using /spawn.

Default channel times:

```text
Haven:     10 seconds
Riftlands: 20 seconds
```

Both are configurable.

/spawn Channel Rules

If the player is combat-tagged:

• deny /spawn
• do not start a countdown

If not in combat:

• start the configured channel
• cancel if the player moves
• cancel if they take damage
• cancel if they become combat-tagged
• cancel on death/disconnect/invalid teleport
• teleport to normal spawn only after successful completion

Use existing Vertex teleport/channel systems if present.

────────

15. Dynamic Zone Mob Spawning

Zone mobs must not be spawned throughout the entire map or throughout empty sections of a zone.

Only spawn zone mobs around players who are actually present inside Haven/Riftlands.

Player Activation Radius

Default concept:

```yml
mob-spawning:
  activation-radius: 100
```

Only the local area around active zone players should maintain zone mobs.

Spawn Distance Around Player

Expose separate configurable minimum and maximum spawn distances.

Example:

```yml
mob-spawning:
  min-spawn-distance: 20
  max-spawn-distance: 80
```

Do not spawn mobs directly on top of players.

Spawn positions must:

• remain inside the same zone
• be on valid terrain
• not suffocate mobs
• not place mobs in obviously invalid hazards unless intentionally configured
• be reachable/reasonable for combat

────────

16. Player Density Scaling

The more players in a local area, the more zone mobs should spawn.

Do not simply multiply without limit.

Use:

• base local mob budget
• additional mob budget per nearby player
• hard cap per active local cluster

Example:

```yml
mob-spawning:
  base-local-mobs: 15
  additional-mobs-per-player: 8
  max-local-mobs: 75
```

The actual defaults should be tuned to feel populated, not nerfed into emptiness.

Performance should be sensible without making the activity boring.

Nearby Player Clustering

Players near each other should share a local spawn budget rather than independently spawning duplicate full populations on top of one another.

Players far enough apart inside the same large region may form separate active clusters.

Make cluster/radius behavior configurable if needed.

────────

17. Empty-Zone Mob Cleanup

If no players remain inside an active zone/arena:

• despawn all mobs generated by that zone system
• clear temporary spawn tracking
• stop spawning entirely
• avoid leaving AI entities ticking in an empty arena

The cleanup should happen quickly enough to reduce server load.

A configurable small despawn delay may be provided if desired.

When players return, the local population should build back naturally.

────────

18. Zone Mob Identity

Only mobs created by the Haven/Riftlands zone spawning system are eligible for:

• zone progression kills
• 5-minute kill event scoring
• zone loot pool rolls
• zone-specific rewards

Do not count:

• mobs brought in from outside
• player-created spawner mobs
• unrelated server mobs
• manually renamed normal mobs
• pets
• summoned mobs not created by this system
• exploit-created mobs

Use a reliable PDC/internal identity marker.

────────

19. Configurable Mob Definitions

All zone mob definitions must be configurable.

Haven and Riftlands may have separate mob pools/settings.

Each configured mob should support:

• internal ID
• vanilla entity type
• enabled/disabled
• spawn weight
• minimum health
• maximum health
• possible names array
• damage value by selected name
• armor/equipment if desired
• potion/effect settings if desired
• movement/AI attributes if desired
• loot eligibility
• spawn limits

Random Health

Each spawn rolls health from its configured range.

Example:

```yml
mobs:
  brute:
    type: ZOMBIE
    health:
      min: 30.0
      max: 50.0
```

Random Names

Each mob may randomly select a display name from a configured array.

Example:

```yml
names:
  - "Rift Marauder"
  - "Void Butcher"
  - "Ash Reaver"
```

Damage Based on Selected Name

Damage should be configurable specifically for the selected mob name/profile.

Example concept:

```yml
profiles:
  "Rift Marauder":
    damage: 7.0

  "Void Butcher":
    damage: 9.0

  "Ash Reaver":
    damage: 11.0
```

Do not assume every randomly named mob must deal identical damage.

Structure the config cleanly so admins can tune this without code changes.

────────

20. Mob Equipment Safety

Zone mobs must not:

• pick up player armor
• pick up weapons
• equip dropped player gear
• steal dropped items through vanilla pickup behavior

Disable mob pickup/equipment acquisition for these zone-generated entities.

Configured equipment assigned by Vertex itself is still allowed if that mob profile uses it.

────────

21. Kill Credit

Only the final killing blow receives credit.

No assists.

The player who lands the final valid hit receives:

• zone kill progression
• event score
• loot roll eligibility

Player projectiles should credit the real shooter.

Custom damage should resolve to the real player source where possible.

Do not give credit to:

• assists
• nearby party members
• faction members who did not land final hit
• environmental damage with no valid final player source

────────

22. Seasonal Zone Progression

Haven and Riftlands each have an independent per-player kill progression track.

Example player state:

```text
Haven Kills: 8,250
Riftlands Kills: 12,900
```

These are separate totals.

Seasonal Reset

At every map/season reset:

• Haven kills reset to 0
• Riftlands kills reset to 0
• Haven progression booster resets
• Riftlands progression booster resets
• zone progression ranks/state reset as appropriate

Do not wipe:

• region definitions
• loot pool configuration
• mob configuration
• route definitions

unless specifically configured.

────────

23. Configurable Progression Milestones

Milestones must be independent per zone.

Do not hardcode fixed intervals.

Example:

```yml
progression:
  milestones:
    1000: 1.0
    5000: 2.0
    10000: 5.0
    25000: 8.0
    50000: 12.0
```

Riftlands should have stronger default progression rewards than Haven because PvP is enabled.

Example only:

```yml
# haven.yml
progression:
  milestones:
    1000: 1.0
    5000: 2.0
    10000: 5.0
    25000: 8.0

# riftlands.yml
progression:
  milestones:
    1000: 2.0
    5000: 4.0
    10000: 7.0
    25000: 12.0
```

These percentages are Mob Drop Amplification, not changes to item rarity.

────────

24. Mob Drop Amplification Model

Do not increase the configured rarity/chance of individual loot-pool items.

If an item has:

```text
5% base chance
```

it remains a 5% base chance.

Boosters instead increase the chance/amount of additional random loot-pool rolls after a successful reward.

Example

Player has:

```text
Haven Progression: +10%
Kill Event Winner: +5%
Backpack Booster: +15%

Total Mob Drop Amplification: +30%
```

Normal reward:

1. normal zone loot successfully drops
2. player receives the normal reward
3. there is a 30% chance for one additional independent loot-pool roll

The bonus roll uses the original configured item rarities again.

It does not simply duplicate the first item.

────────

25. Amplification Above 100%

Allow amplification to scale naturally beyond 100%.

Example:

```text
+40%  = 40% chance for one extra roll

+100% = 1 guaranteed extra roll

+125% = 1 guaranteed extra roll
        +25% chance for a second extra roll

+250% = 2 guaranteed extra rolls
        +50% chance for another
```

Expose an optional configurable hard cap if developers want one later, but do not artificially nerf the system by default unless needed for balance.

────────

26. Booster Sources

Mob Drop Amplification should aggregate compatible sources through one centralized calculation.

At minimum include:

• current Haven progression boost while farming Haven
• current Riftlands progression boost while farming Riftlands
• current Top 3 kill-event winner booster
• equipped Backpack mob-drop booster
• future compatible personal/event boosters

Do not apply the Haven progression boost while farming Riftlands.

Do not apply the Riftlands progression boost while farming Haven.

────────

27. Backpack Integration

Backpacks already exist.

Do not create a second Backpack system.

Use the existing equipped Backpack and existing configured mob-drop booster.

Add Haven/Riftlands tuning hooks only if needed.

The Backpack’s mob-drop bonus should contribute to Mob Drop Amplification while equipped.

Avoid double-applying the same Backpack booster through multiple systems.

────────

28. Zone Loot Pools

Haven and Riftlands each have an independently editable loot pool.

Admin commands:

```text
/haven lootpool
/Riftlands lootpool
```

Optional aliases:

```text
/haven lootpool
/riftlands lootpool
```

Admins open an editable inventory GUI.

They may:

• place real items into the GUI
• remove items
• move items
• close the GUI to save

Reopening shows the saved loot pool.

Preserve Exact Items

Persist full item data, including:

• material
• amount where meaningful
• custom name
• lore
• vanilla enchants
• custom enchants
• CustomModelData
• PDC/custom identity
• attributes
• other supported item metadata

Do not reconstruct complex items from display text.

────────

29. Loot Pool Item Rarity

Every loot-pool item has its own configurable rarity/chance.

When an admin first adds a new item:

```text
default chance = 20%
```

Default is configurable.

Example:

```yml
loot-pool:
  default-item-chance: 20.0
```

An admin must be able to edit each stored item’s chance through GUI interaction.

Do not require manual YAML editing for normal loot-pool maintenance.

Possible workflow:

```text
Admin right-clicks loot item
-> opens chance editor
-> changes value
-> saves
```

Adapt to existing Vertex GUI conventions.

────────

30. Player Loot Pool GUI

Players can open a read-only loot-pool GUI.

The player version must not allow moving/removing items.

Each loot item’s lore should show:

• item name
• configured base rarity
• current player’s Mob Drop Amplification
• relevant active booster sources
• whether rare-item duplicate protection applies
• any useful zone-specific information

Important:

The item’s actual rarity does not change because of amplification.

Example lore concept:

```text
Base Drop Chance: 2.50%

Mob Drop Amplification:
Haven Progress: +10%
Backpack: +15%
Kill Event: +5%
Total: +30%

Amplification grants additional random loot rolls.
Your base item rarity remains 2.50%.
```

────────

31. Rare Item Multi-Drop Protection

Items below a configurable rarity threshold must never be awarded more than once from a single mob kill.

Default:

```yml
loot-pool:
  rare-multi-drop-threshold-percent: 5.0
```

Rule:

```text
item chance < 5.0%
=> maximum one copy/award of that item from the same mob kill
```

This applies even if:

• the player has multiple guaranteed amplified rolls
• several bonus rolls independently select the same rare item

Do not silently increase rare item supply through amplification.

Whether exactly 5.0% is protected should follow the explicit rule above: strictly less than 5.0% by default.

────────

32. Kill Streak Event

Run a recurring competitive mob-kill event.

Default cycle:

```text
Every 2 hours
```

Default active competition duration:

```text
5 minutes
```

Flow:

```text
5-minute kill event
-> finalize Top 3
-> apply winner boosters
-> boosters remain for next 1 hour 55 minutes
-> next event begins
-> repeat
```

All timing must be configurable.

Example:

```yml
kill-event:
  cycle-minutes: 120
  duration-minutes: 5
```

────────

33. Kill Event Scoring

All eligible zone-generated mobs have the same base value.

No elite/boss extra score.

Scoring:

```text
Haven mob kill     = 1.0 point
Riftlands mob kill = 1.5 points
```

Both accumulate into one combined event leaderboard.

Example:

```text
100 Haven kills     = 100 points
100 Riftlands kills = 150 points

Total = 250 points
```

The multipliers are configurable.

Example:

```yml
scoring:
  haven: 1.0
  riftlands: 1.5
```

Final hit only.

────────

34. Kill Event Top 3 Boosters

At event end, reward Top 3 with temporary Mob Drop Amplification.

Suggested defaults:

```text
1st: +5%
2nd: +3%
3rd: +1%
```

All values configurable.

Example:

```yml
rewards:
  first: 5.0
  second: 3.0
  third: 1.0
```

These boosters do not alter individual item rarity.

They contribute to the winner’s Mob Drop Amplification.

Winner Booster Lifetime

Winner boosters remain active until the next kill event finishes/replaces them.

Normal cycle:

```text
5-minute event
-> winner booster applies
-> lasts 1h55m
-> next 5-minute event
-> old winner boosters replaced when new result finalizes
```

If a season reset occurs between events:

• seasonal Haven/Riftlands progression resets
• existing Top 3 winner booster remains
• it is replaced normally when the next kill event completes

────────

35. Kill Event BossBar

During the active 5-minute event, show a live BossBar.

Only show it to players currently inside:

• Haven
• Riftlands

Do not show the event BossBar globally to players outside the zones.

Refresh

Default refresh:

```text
every 2 seconds
```

Configurable.

Content

Show:

• time remaining
• current #1 IGN + score
• current #2 IGN + score
• current #3 IGN + score

Example:

```text
Mob Kill Event | 02:41 | #1 Cesar: 142.5 | #2 PlayerTwo: 131 | #3 PlayerThree: 118.5
```

Because Riftlands is worth 1.5, call the value points in detailed GUI/lore where helpful to avoid confusion over decimal “kills”.

BossBar progress should count down visually from full to empty during the event.

BossBar formatting should be configurable:

• text template
• color
• style
• refresh interval

If a player leaves the zone:

• remove BossBar immediately

If they re-enter during the same active event:

• show current BossBar/standings immediately

At event end:

• remove BossBar immediately

────────

36. Event Ties

Use deterministic tie resolution.

Recommended default:

```text
If two players have the same final score,
the player who reached that score first ranks higher.
```

Persist enough event timing/order state to resolve ties reliably.

Expose a configurable minimum participation score if desired.

────────

37. Zone Progress GUI

Provide a player GUI showing both progression tracks.

Suggested command:

```text
/zones
```

or integrate through /haven and /Riftlands submenus.

Show at minimum:

Haven

• seasonal Haven kills
• current Haven milestone
• current Haven amplification
• next milestone
• kills remaining

Riftlands

• seasonal Riftlands kills
• current Riftlands milestone
• current Riftlands amplification
• next milestone
• kills remaining

Kill Event

• whether event is active
• current event score
• current rank
• Top 3
• time remaining
• next event countdown when inactive
• active winner booster if any

Booster Breakdown

Show current compatible Mob Drop Amplification sources:

• zone progression
• kill-event winner
• Backpack
• other compatible future boosters

Use a clean configurable GUI.

────────

38. Riftlands Farming Session Loot

Riftlands uses a session loot ledger.

Loot earned while farming Riftlands is considered unextracted/current-session loot until the player leaves Riftlands successfully.

Session Start

A Riftlands session begins when the player enters Riftlands.

Track exactly which Riftlands-generated rewards are acquired during that session.

Session End / Securing Loot

When the player successfully leaves Riftlands:

• current session loot becomes safe
• clear the session ledger
• do not later treat those secured items as loss-on-death loot

Leaving includes successful /spawn completion or another explicitly approved normal Riftlands exit.

Do not secure loot merely because of a temporary movement glitch.

────────

39. Backpack Session Tracking

Zone loot normally goes directly to the player’s existing Backpack.

Do not wipe the entire Backpack on death.

Instead track exact Riftlands session rewards.

The ledger should record enough information to remove/drop only session-earned loot.

Prefer unique item identity / exact item snapshots and quantities.

If the player had an item before entering Riftlands, that pre-existing item is not part of the current session merely because an identical item exists in the Backpack.

Avoid destructive “remove all matching material” logic.

────────

40. Riftlands Death Loot

On death inside Riftlands:

• player keeps normal armor
• player keeps normal weapons
• player keeps ordinary inventory unless another existing system says otherwise
• do not clear the entire Backpack
• remove the tracked current-session Riftlands loot
• physically drop that session loot at the death location

This creates PvP risk without making players lose their normal fighting kit.

PvP Death

If there is a valid player killer:

• drop all tracked Riftlands-session loot
• killer gets temporary exclusive loot pickup protection
• after protection expires, loot becomes public

Use existing combat/loot-protection logic if available.

Non-PvP Death

If death is caused by:

• mob
• fall
• environment
• no valid player killer

then:

• drop all tracked Riftlands-session loot
• loot is public immediately
• no reclaim protection for the dead player

Combat Logger Death

If existing combat logger kills the player:

• use the last valid PvP attacker
• treat that attacker as the killer for Riftlands loot protection

Do not invent a killer when no valid PvP attacker exists.

────────

41. Killer Loot Protection

For a valid Riftlands PvP kill, protect dropped session loot for the killer for the existing/configured loot-protection duration.

Track ownership by UUID.

During protection:

• killer may pick up protected loot
• others may not

After expiration:

• anyone may pick it up

If Vertex already has a combat loot-protection system, reuse it.

Do not create conflicting duplicate ownership systems.

Make duration configurable if not already globally configured.

────────

42. PvP Arena Separation

The existing dedicated PvP Arena remains separate from Riftlands behavior.

If a player dies in the PvP Arena:

• use the PvP Arena’s existing full-drop behavior
• do not apply Riftlands session-backpack clearing rules
• do not mistakenly clear the Backpack via the Riftlands ledger

Territory checks must clearly distinguish:

```text
Riftlands
Haven
PvP Arena
Other world/claims
```

────────

43. Riftlands Ticket

Add a custom Riftlands Ticket item.

Display name may be configurable, but it is functionally the Riftlands emergency escape ticket.

Item Identity

Fully configurable:

• vanilla material
• CustomModelData
• name
• lore
• glow
• sounds
• messages

It may be:

• a plain vanilla item
• a vanilla item with custom model data/resource-pack appearance

Use Vertex’s PDC/custom-item identity system.

Do not identify it only by display name/material.

────────

44. Riftlands Ticket Usage Requirements

The ticket can only be used when:

1. player is physically inside Riftlands
2. player is currently combat-tagged

If either condition is false:

• deny use
• do not consume ticket
• do not start any cooldown

No Cooldown

Riftlands Tickets have no use cooldown.

If a player escapes, gets attacked again, and owns another ticket, they may use another immediately.

────────

45. Riftlands Ticket Activation

Activation is instant.

No warmup.

No 3-second channel.

No damage-cancel countdown.

On valid use:

1. determine all relevant hostile players
2. search for a safe valid location inside the same Riftlands arena
3. choose the safest/farthest valid destination
4. teleport immediately
5. clear combat state
6. clear last-attacker state appropriately
7. consume exactly one ticket
8. player may immediately start /spawn or safely log out

Only consume after a valid destination is found and teleport succeeds.

If no destination can be found:

• do not consume
• do not clear combat
• show configurable failure message

────────

46. Riftlands Ticket Enemy Evaluation

Do not only evaluate the most recent attacker.

Use FactionsUUID to determine all possible nearby enemies.

Consider:

• last valid player attacker
• nearby enemy faction members
• nearby neutral players if Riftlands rules allow them to attack
• factionless hostile players if applicable

Ignore:

• same-faction members
• allies
• players who cannot legally PvP the ticket user

The teleport destination should maximize safety relative to the hostile group as a whole.

────────

47. Riftlands Ticket Destination Search

Search only inside the same Riftlands region the player is currently in.

Candidate location must:

• be inside Riftlands
• have safe solid footing
• have enough head/body clearance
• not suffocate player
• avoid lava/fire/obvious hazards
• avoid invalid blocks
• stay a configurable distance from region border
• avoid zone entry routes/landing areas
• prefer locations far from hostile players

Score valid locations using something similar to:

```text
minimum distance to any hostile player
```

Prefer the candidate that maximizes the minimum hostile distance.

Do not simply teleport “opposite the last attacker”.

────────

48. Avoid Entry Routes

Riftlands Ticket teleport destinations should avoid the guided entry routes and drop/arrival areas.

Expose a configurable avoid radius.

Example:

```yml
Riftlands-ticket:
  teleport:
    entry-route-avoid-radius: 50
```

This prevents an escaping player from being teleported onto newly arriving players.

────────

49. Riftlands Ticket Combat Clearing

On successful ticket teleport:

• clear the player’s combat timer
• clear relevant last-attacker state
• ensure combat action bar updates correctly
• make player immediately eligible to use /spawn
• allow safe logout after success

Do not reduce the timer before successful teleport.

Do not clear combat if the teleport fails.

────────

50. Riftlands Ticket Loot Acquisition

Riftlands Tickets exist in both Haven and Riftlands loot pools.

Default base rarity:

```text
0.75%
```

They remain subject to the same loot-pool model.

Because 0.75% < 5%, rare-item multi-drop protection applies.

Therefore:

```text
maximum one Riftlands Ticket from a single mob kill
```

even if the player has multiple amplified bonus rolls.

────────

51. Riftlands Ticket Admin Command

Admins can give tickets directly.

Example:

```text
/Riftlands ticket give <player> <amount>
```

Add tab completion.

Use a dedicated admin permission, for example:

```text
vertex.admin.Riftlands.ticket
```

Adapt naming to Vertex’s existing permission structure.

────────

52. Player Loot Delivery

Normal Haven/Riftlands mob rewards should go through the existing Backpack system when that is the server’s established behavior.

If Backpack cannot accept an item:

• use a safe existing fallback
• do not silently delete the item
• do not duplicate it

Fallback behavior should be configurable or reuse existing Backpack overflow logic.

Riftlands session ledger must still know whether the reward went to Backpack or fallback inventory/drop.

────────

53. Config File Split

Use separate major configs:

```text
haven.yml
riftlands.yml
```

Shared settings may live in a common zone config if Vertex architecture already prefers that.

All performance-related settings should remain centralized in the existing performance.yml if that is the project-wide rule.

haven.yml should include

• region/zone settings
• PvP disabled
• spawn density
• mob pool
• mob profiles
• progression milestones
• loot pool references/chances
• /spawn exit time
• death re-entry cooldown
• entry settings
• route settings where appropriate

riftlands.yml should include

• PvP enabled
• spawn density
• mob pool
• stronger/default reward tuning
• progression milestones
• loot pool references/chances
• /spawn exit time
• death re-entry cooldown
• entry settings
• route settings
• Riftlands Ticket settings
• session loot rules

Every config option should have a descriptive YAML comment explaining:

• what it controls
• default behavior
• what changing it does

────────

54. en_us.yml

All normal player-facing messages belong in en_us.yml.

Include configurable messages for:

• combat-blocked entry
• entry countdown
• entry cancelled by movement
• entry cancelled by damage
• death re-entry cooldown remaining
• /spawn channel
• /spawn cancelled
• /spawn denied in combat
• entering Haven
• entering Riftlands
• leaving zone
• event start/end
• winner messages
• loot reward
• Riftlands Ticket invalid location
• Riftlands Ticket requires combat
• Riftlands Ticket only works in Riftlands
• Riftlands Ticket success
• Riftlands Ticket no safe location
• admin loot-pool save
• invalid/overlapping region
• invalid route

Use placeholders and MiniMessage formatting if supported by existing Vertex messaging.

Avoid message spam.

────────

55. PlaceholderAPI

Expose useful placeholders.

At minimum:

```text
current zone
Haven seasonal kills
Riftlands seasonal kills
Haven current progression %
Riftlands current progression %
Haven next milestone
Riftlands next milestone
kills remaining to next milestone
current Mob Drop Amplification
Backpack amplification source
kill-event active state
kill-event current score
kill-event current rank
kill-event remaining time
next kill-event time
Top 1 IGN
Top 1 score
Top 2 IGN
Top 2 score
Top 3 IGN
Top 3 score
active winner booster %
Riftlands session loot count/value if practical
death re-entry cooldown remaining
```

Use stable placeholder names consistent with existing Vertex conventions.

────────

56. Persistence

Persist:

• region definitions
• route definitions
• loot pools
• per-item loot chances
• mob configs via YAML
• seasonal Haven kills
• seasonal Riftlands kills
• progression state
• current kill-event state where needed
• current winner boosters
• death re-entry cooldowns if intended to survive restart
• Riftlands session ledgers safely enough to prevent reboot exploits

Season reset should wipe only season-specific player progression/state according to the rules above.

Do not wipe zone structure/configuration.

────────

57. Kill Event Restart Behavior

Do not restart a fresh 5-minute event simply because the server rebooted.

Use a real schedule/cycle.

On startup:

• determine whether a kill event should currently be active
• determine remaining duration
• restore/recompute current winner booster lifetime appropriately
• prevent rebooting from extending rewards indefinitely
• prevent rebooting from resetting event standings unless explicitly desired

Use the server’s existing scheduling architecture.

────────

58. Anti-Exploit Requirements

Explicitly protect against:

• outside mobs counting as zone mobs
• player spawner mobs counting
• dragging mobs across zone borders to manipulate scoring
• duplicate kill credit
• two players both receiving final-hit credit
• amplified loot duplication from repeated events
• Backpack reward duplication
• ticket duplication
• logout/restart session-loot exploits
• moving Riftlands loot into another container to avoid death loss if the session ledger says it is still unsecured
• re-enter command spam
• GUI stale-click duplication
• route escape outside zone
• fake combat clear before ticket teleport succeeds
• abusing ticket near entry routes
• using ticket outside Riftlands
• using ticket while not in combat
• loot-pool admin GUI accidentally dropping/copying items
• mob pickup of player gear

────────

59. Performance Requirements

The system should be optimized sensibly without being “nerfed to the ground”.

Do not spawn/tick mobs in empty zones.

Prefer:

• spawn processing only around players actually inside zones
• local player clusters
• hard local mob caps
• no full-region scans every tick
• despawn zone mobs when zone becomes empty
• cached region lookups where natural
• batched safe spawn checks where helpful
• event BossBar updates every 2 seconds, not every tick
• only update players who should actually see the BossBar
• async persistence where safe
• all Bukkit/Paper world/entity mutations on correct server thread

Any tunable performance attributes should have sensible defaults and comments.

────────

60. Admin / Debug Tools

Provide useful admin tools for testing and maintenance.

Suggested capabilities:

```text
force start kill event
force stop kill event
inspect current event
set/reset player Haven kills
set/reset player Riftlands kills
inspect player amplification
inspect player's Riftlands session ledger
force clear a session ledger
spawn a configured zone mob
reload Haven/Riftlands configs safely
list regions
list routes
preview routes
open/edit loot pools
set/test item rarity
give Riftlands Ticket
```

Do not expose sensitive debug commands to normal players.

────────

61. Implementation Validation Checklist

Before considering this feature complete, test all of the following.

Region / Entry

• Haven region can be created with existing Blaze Rod selector
• Riftlands region can be created with existing selector
• overlap is rejected
• /haven opens correct GUI
• /Riftlands opens correct GUI
• Emerald Block confirmation works
• 5-second countdown works
• movement cancels countdown
• damage cancels countdown
• entering combat cancels countdown
• already combat-tagged players cannot start
• route remains inside region
• guided flight follows waypoints smoothly
• left click releases
• right click releases
• Slow Falling remains until ground
• route end auto-releases
• Riftlands player can attack/be attacked during flight
• being hit knocks/releases them from route
• Haven flight remains PvP-disabled
• noncombat disconnect in flight returns player to safe ground
• combat disconnect follows combat logger

Mobs

• mobs spawn only around active zone players
• no players = all zone mobs despawn
• minimum spawn distance works
• maximum spawn distance works
• player-density scaling works
• hard local cap works
• outside mobs do not count
• zone mobs cannot pick up/equip player armor
• health random range works
• random names work
• damage maps correctly to selected name/profile

Progression

• final hit only
• Haven kill increments Haven total only
• Riftlands kill increments Riftlands total only
• milestone transitions work
• Riftlands defaults can be stronger
• season reset wipes progression

Kill Event

• starts on configured cycle
• lasts default 5 minutes
• Haven kill = 1.0
• Riftlands kill = 1.5
• same mob base value
• BossBar only shown inside zones
• BossBar updates every 2 seconds
• Top 3 displays correctly
• timer displays correctly
• BossBar disappears on leave/event end
• winner boosters apply
• old boosters replaced next event
• season reset does not prematurely remove current winner booster

Loot

• admin can add/remove exact items in loot pool
• closing admin GUI saves
• exact item metadata preserved
• default new-item chance = 20%
• admin can edit chance
• player GUI is read-only
• player GUI shows base chance
• boosters do not alter base rarity
• amplified reward rolls are random independent rolls
• <5% item cannot award more than once from one mob kill
• Backpack booster contributes to amplification
• no duplicate booster application

Riftlands Session Loot

• entering starts session
• rewards recorded accurately
• leaving secures loot
• normal armor/weapons are preserved on Riftlands death
• only tracked session loot is removed/dropped
• PvP killer gets temporary pickup protection
• non-PvP death loot is public immediately
• combat logger attacker receives protection when valid
• PvP Arena death behavior remains separate

Riftlands Ticket

• only usable in Riftlands
• only usable while combat-tagged
• instant activation
• no cooldown
• all relevant hostile players evaluated
• same-faction/allies ignored
• safe candidate remains inside same arena
• entry routes avoided
• combat clears only after successful teleport
• ticket consumed exactly once
• failed teleport does not consume
• failed teleport does not clear combat
• successful user can immediately /spawn or logout
• 0.75% default loot rarity in Haven and Riftlands
• max one ticket per mob kill due to rare-item protection
• admin give command works

────────

62. Final Build Requirement

Implement this as a polished production system that feels integrated into Vertex Addons rather than bolted on.

Priorities:

1. correctness
2. exploit resistance
3. clean integration with existing Vertex systems
4. configurable gameplay
5. smooth player experience
6. performance under multiple active zone players
7. maintainable/extensible code

Do not silently simplify or omit a mechanic from this specification.

If an existing Vertex system conflicts with this design, preserve existing functionality where possible and integrate through its API/service rather than replacing it without reason.