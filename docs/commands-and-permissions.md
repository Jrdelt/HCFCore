# Commands & Permissions

Every command Vertex registers, grouped by area. "Open to all" means no
permission is checked — anyone can run it.

## Kits

| Command | Permission | Notes |
|---|---|---|
| `/kit <name>` | The kit's own `permission` (blank for the six base classes; `-donator` variants require theirs) | Applies a kit, respecting its cooldown and cost. |
| `/kit create <name> [permission] [cooldownSeconds] [cost] [costItem[:amount]]` | `vertex.kit.create` | Saves your current armor + inventory as a new kit. `costItem` is a Material name (e.g. `DIAMOND:2`); amount defaults to 1. `/kit save` is the same command under its legacy name (`vertex.kit.save`). |
| `/kit delete <name>` | `vertex.kit.delete` | Deletes a kit. |
| `/kits` | Open to all | Browse/claim kits in a GUI — see [Kits & Abilities](kits-and-abilities.md). |

## Abilities

| Command | Permission | Notes |
|---|---|---|
| `/getitem <username> <ability> [amount]` | `vertex.ability.give` | Gives ability items directly, ignoring cooldowns. |
| `/abilities` | Open to all | Browse abilities in a GUI; a viewer with `vertex.ability.give` can click to receive one. |
| `/cooldowns` | Open to all | Shows your own active kit, ability, and vanilla item cooldowns. |

## Tags

| Command | Permission | Notes |
|---|---|---|
| `/tags` | Open to all | Browse/equip cosmetic tags — see [Tags & Cosmetics](tags-and-cosmetics.md). |

## Combat

| Command | Permission | Notes |
|---|---|---|
| `/uncombat <player>` | `vertex.combat.uncombat` | Clears a combat tag early. |
| `/combatcheck <player>` | `vertex.combat.check` | Reports tag status, time left, opponent, health, ping. |
| `/combattag <player> [opponent\|server]` | `vertex.combat.tag` | Testing tool — see [PvP & Combat](pvp-and-combat.md). |

## Spawners & Chunk Collectors

| Command | Permission | Notes |
|---|---|---|
| `/spawners` | Open to all | Opens the spawner shop GUI. |
| `/chunkcollector give <player>` | `vertex.collector.give` | Gives a Chunk Collector — no in-game shop for these. |

## Blueprint Base Builder

| Command | Permission | Notes |
|---|---|---|
| `/blueprint give <player> <template>` | `vertex.blueprint.give` | Gives a Blueprint item. Available only when both FAWE and DecentHolograms are loaded. |
| `/blueprint cooldown remove <player>` | `vertex.blueprint.cooldown.remove` | Clears an online or offline player's persisted placement cooldown. Available only when both Blueprint dependencies are loaded. |

## Backpacks

| Command | Permission | Notes |
|---|---|---|
| `/backpack give <player> <tier> [level]` | `vertex.backpack.give` | Gives a Backpack of that tier, optionally starting at any positive level (default 1) — no in-game shop for these. Its material and custom model data come from `backpacks.yml`. |
| `/backpack debug` | `vertex.backpack.debug` | Toggles personal Backpack interaction diagnostics. It prints the received action, hand, cancellation state, and reject/open reason to chat and console; run it again to turn tracing off. |
| `/filter <material>` / `/filter clear` | — | Toggles or clears persistent Backpack auto-collection filters. Filters discard matching routed drops only while a Backpack is equipped. |

## Factions & Rally

| Command | Permission | Notes |
|---|---|---|
| `/f rally [set\|clear]` (alias `/frally`) | The role's **Set Rally** / **Clear Rally** permission | Sets/clears a 4-minute faction rally point. Defaults allow every editable role; leaders change it in `/f permissions`. |
| `/f permissions` / `/f perms` | Faction leader only (checked in-code, not a permission node) | Opens the faction permission matrix GUI. |
| `/f upgrades` / `/f upgrade` | Faction role needs FactionsUUID's native `UPGRADE` action allowed | Opens Vertex's persistent faction-upgrades GUI. Set `faction-upgrades.leader-only: true` to restrict purchases further. FactionsUUID's native upgrade administration remains separate. |
| `/f bank` | Faction member; role permissions apply to deposits/withdrawals | Opens the six-row faction bank for money, experience, and TNT. |

## KOTH & Outposts

| Command | Permission | Notes |
|---|---|---|
| `/koth focus [name\|off]` | Open to all | Focuses an active same-world KOTH for that player only, using a BossBar/compass instead of their faction rally. |
| `/outpost focus [name\|off]` | Open to all | The Outpost equivalent of `/koth focus`. |
| `/koth create\|wand\|cancel\|start\|stop\|delete\|list\|validate` | `vertex.koth.admin` | Creates cuboid KOTH regions with a Blaze Rod, starts/stops any configured KOTH, and validates KOTH config paths in-game. |
| `/outpost create\|wand\|cancel\|start\|stop\|delete\|list\|validate` | `vertex.outpost.admin` | Creates, starts/stops, and validates Outpost regions. |

See [KOTH & Outposts](koth-and-outposts.md) for capture rules, schedules,
rewards, and the `capture-events.yml` reference.

## Reboot

| Command | Permission | Notes |
|---|---|---|
| `/reboot [minutes]` | `vertex.reboot.start` | Starts a shutdown countdown. |
| `/reboot cancel` | `vertex.reboot.start` | Cancels an in-progress countdown. |
| `/nextreboot` | Open to all | Shows the scheduled countdown, if any. |

## Staff

| Command | Permission | Notes |
|---|---|---|
| `/staff` | `vertex.staff.mode` | Vanish + staff-build + godmode + flight together. |
| `/vanish` | `vertex.staff.vanish` | Toggles vanish; also lets you see other vanished staff. |
| `/staffchat` | `vertex.staff.staffchat` | Toggles staff-only chat; also needed to read it. |
| `/staffbuild` | `vertex.staff.staffbuild` | Toggles claim-protection bypass everywhere. |
| `/freeze <player>` | `vertex.staff.freeze` | Toggles freezing a player; also needed for the leave-while-frozen ban alert. |
| `/invsee <player>` | `vertex.staff.invsee` | Opens the target's storage/armor/offhand GUI. |
| `/endersee <player>` | `vertex.staff.endersee` | Opens the target's live ender chest. |
| `/rollback <player>` | `vertex.staff.rollback` | Opens the death-history GUI. |

## Language

| Command | Permission | Notes |
|---|---|---|
| `/language [code]` | Open to all | View or change your language. |

## Admin

| Command | Permission | Notes |
|---|---|---|
| `/vertex reload` | `vertex.admin` | Reloads config/kits/abilities/tags/messages. |
| `/vertex clearmobstacks` | `vertex.admin` | Clears every tracked stacked mob. |
| `/vertex storage` | `vertex.admin` | Shows which storage backend is currently in use. |
| `/vertex storage <local\|mysql> [confirm]` | `vertex.admin` | Copies all data into the other backend and switches `storage.type` to it. Takes effect on the next restart. `confirm` is required if the target database already has data in it, since it gets overwritten. See [Installation](installation.md#switching-backends-in-game). |

## Permission node reference

Beyond the fixed nodes above, three areas use **dynamic**, config-defined
nodes:

| Pattern | Example | Source |
|---|---|---|
| `vertex.kit.<name>` | `vertex.kit.archer` | Default permission for any kit that doesn't set its own blank `permission: ''` — see [Kits & Abilities](kits-and-abilities.md#permission) |
| `vertex.kit.<class>.donator` | `vertex.kit.archer.donator` | The permission each shipped `-donator` kit variant actually uses |
| `vertex.tag.<name>` | `vertex.tag.berserker` | Set per-tag in `tags.yml`; blank unlocks it for everyone |

Plus two bypass nodes for staff testing kits: `vertex.kit.bypasscooldown`
and `vertex.kit.bypasscost`. `vertex.admin` also covers reload/
clearmobstacks as shown above.

## Tab-completion

Every command with arguments registers its own `TabCompleter`, so
suggestions appear as soon as the running jar includes them. If
suggestions don't show up in-game:

1. Confirm the server is actually running the jar you just built —
   check the file's modified date, or watch the console for the Vertex
   startup banner's version line.
2. Confirm you hold the command's permission — a player missing
   `vertex.combat.tag`, for example, won't see `/combattag` suggested at
   all.
3. Some clients cache command suggestions per session — rejoin if a
   command was added while already connected.
