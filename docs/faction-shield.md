# Grace and Faction Shield

Vertex owns both systems; no external faction plugin is required.

## Grace

### Command and permissions

| Command | Permission | Purpose |
|---|---|---|
| `/f grace` | Open to all | Shows whether global faction Grace is active and how much time remains. |
| `/fa grace on <duration>` | `vertex.fa.grace` | Enables global Grace (e.g. `2d12h`) with compact duration support. |
| `/fa grace off` | `vertex.fa.grace` | Disables global Grace immediately. |
| `/f shield` | All faction members can view; Leader or Co-Leader can configure | Opens the weekly Shield GUI. |
| `/f shield activate` | Leader or Co-Leader with `vertex.shield.activate` | Starts an optional manual Shield activation window, with the configured duration/cooldown rules. |
| `/fa shield <faction> active \| inactive \| clear --force` | `vertex.fa.shield` | Persistent audited staff override; `clear` returns to real schedule/manual deadlines. |

Grace is global explosion protection for every claimed faction location. Compact
durations support days, hours, minutes, and seconds. `grace.maximum-duration-seconds`
in `shield.yml` is the hard configured limit.

Grace stores an absolute database deadline, so downtime neither pauses nor
restarts it. Every enable/disable action is written to `faction_grace_log`.

## Weekly Faction Shield

`/f shield` opens the weekly Shield GUI. Every faction member can view it, but
only the durable Leader or Co-Leader can save changes. Each day has one start
time and duration. The defaults are:

- At most 8 protected hours in each calendar day, including a previous day's
  cross-midnight spill-over.
- A 24-hour lock before the schedule may be edited again.
- A 24-hour delay before a newly saved schedule becomes active.
- `America/Los_Angeles` as the schedule time zone.

These values are configured under `shield.schedule` in `shield.yml`. The
entire week and its PvP option are saved as one unit. A stale GUI cannot
overwrite a newer role, faction, or schedule decision because authorization
and the edit lock are rechecked in the database transaction.

The optional `/f shield activate` command provides a manual activation window.
It is limited to Leader/Co-Leader, requires `vertex.shield.activate`, and uses
the configured duration/cooldown. The `shield-duration` faction upgrade adds
its explicitly configured bonus, capped by `shield.maximum-duration-seconds`.
Manual and weekly windows coexist; either can make the Shield active.

## Protected territory and PvP

Shield protects only the faction's current Base Claim chunks. Raid Claims are
never Shield-protected. If a faction has no Base Claims, its schedule/settings
remain stored but protect nothing; recreating a Base during an active window
applies protection immediately.

Grace and Shield remove only protected claim locations from explosion block
lists and cancel entity explosion damage only at protected locations. An
explosion crossing a border can therefore damage Wilderness/Raid land while
leaving the protected Base intact.

Leader/Co-Leader can toggle the faction's Shield PvP option in the same GUI.
When enabled, players may still enter a protected Base but cannot damage each
other there while the Shield is active. `shield.combat-protection-enabled`
can force that behavior globally.

## Staff override

The staff override path requires `vertex.fa.shield` (or `vertex.fa.*`). It is
persistent and audited. Clearing the override returns the faction to its real
weekly/manual state; it does not reset or extend those deadlines.

## Storage

- `faction_grace` and `faction_grace_log` store global Grace.
- `faction_shield_weekly` stores current/pending weekly schedules, PvP choice,
  activation delay, edit lock, and editor.
- `faction_shield_activations` stores optional manual activation/cooldown.
- `faction_shield_overrides` stores the current staff override.
- `faction_shield_log` stores activation, schedule, and override audit events.

All tables are included in Vertex storage migration and season reset.
