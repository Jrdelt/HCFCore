# Faction Shield

Faction Shield is a schedule-based protection window for a faction's Base
Claims (see [Base Claims and Raid Claims](base-and-raid-claims.md)). While
a faction's Shield is active, PvP inside its Base Claims can be disabled
(configurable); Raid Claims are **never** protected by Shield, regardless
of state.

`/f map` is intentionally left completely untouched — disassembling
FactionsUUID's `factions-4.4.0.jar` confirmed `CmdMap.handle` builds its
hover text inline with no hook, event, or config point anywhere in the
call path. Shield state surfaces only through `/f shield` and an extension
to the native `/f who`.

## Schedule model

A Shield schedule is **one daily recurring window**, expressed as a
start-of-day time and a duration, anchored to real-world Eastern time
(`America/New_York`, so it follows EDT in summer the same way the spec's
own "EST" wording would in practice). Because the window is anchored to
wall-clock time of day rather than an elapsing countdown, "keeps advancing
during server downtime" falls out for free — there is nothing to persist
for the recurring part itself; `ShieldSchedule.isActiveAt(instant)` is
always recomputed live from that instant's Eastern local time.

Set a schedule with:

```
/f shield set <HH:mm> <duration-minutes>
```

Leader/Co-Leader only (`vertex.shield.set`), and only once the faction has
passed its **New-Faction Shield Delay** (see below).

## Activation delay ("let the current window finish")

A newly submitted schedule does not apply immediately. It becomes
`pending`, with an absolute activation deadline
(`shield.activation-delay-seconds` in `shield.yml`, default 24h,
continues counting through downtime). Once that deadline passes:

- If the **old** schedule's window is not currently active, the pending
  schedule takes over immediately.
- If the old schedule's window **is** currently active, the old schedule
  keeps being used until that window ends — a protection period already
  in progress is never cut short. The moment it ends, the pending
  schedule becomes permanent.

This is implemented as a pure, stateless function of "now"
(`ShieldManager.effectiveSchedule`): before the delay elapses, the live
schedule applies; after the delay elapses, the live schedule still applies
for as long as its own window remains active, and the pending schedule
applies otherwise. Because the delay deadline stays in the past forever
once reached, the switch becomes permanent the first time the old window
is found inactive — no timer has to "notice" the exact moment. A bounded
sweep (`shield.sweep-interval-seconds`, default 30s; only iterates
factions with a pending swap outstanding, never a full scan) exists purely
to persist that swap and write the one-time `SCHEDULE_ACTIVATED` log
entry — `isShieldActive` never depends on the sweep having run yet.

Re-submitting a schedule (even before a previous submission finished
activating) is always allowed and free — it just restarts the same
activation-delay wait against the new schedule.

## New-Faction Shield Delay

A newly created faction must wait `shield.new-faction-delay-seconds`
(default 6 hours, real elapsed time including downtime) before it may
submit its first Shield schedule. Hooked from FactionsUUID's
`FactionCreateEvent`. A faction can still create its free first Base Claim
during this wait — Base Claim creation is never gated by Shield
eligibility — that Base Claim simply has no Shield protection until the
faction becomes eligible and submits a schedule.

Disbanding a faction (`FactionDisbandEvent`/`FactionAutoDisbandEvent`)
deletes its Shield row entirely, including any override, so a
disbanded-and-recreated faction always gets a fresh wait rather than
inheriting anything stale.

## Combat protection

While a faction's Shield is active, `ShieldCombatListener` cancels
`EntityDamageByEntityEvent` (including projectiles) when the victim is a
player standing inside that faction's Base Claim, and the attacker is
also resolved to a player. Controlled by `shield.combat-protection-enabled`
(default `true`). Raid Claims are excluded structurally, not by a
special case: `ShieldManager.isBaseClaimProtected` only ever returns
`true` for a location inside a Base Claim region
(`BaseClaimManager.regionAt`); a Raid Claim location has no region, so it
is never protected no matter how the query is asked.

## Admin override

`vertex.admin.claims` (the same permission Base Claim's Shield-active
removal-block references) lets staff force a faction's Shield `ACTIVE` or
`INACTIVE`:

```
/f shield admin <factionTag> active|inactive|clear
```

- Every attempt (permitted or denied) is console-logged loudly, matching
  `GcCommand`'s `logAdminAttempt` convention for high-risk actions.
- While an override is in effect, the normal schedule is frozen:
  `isShieldActive` returns the forced state unconditionally, ignoring the
  live/pending schedule entirely.
- **Freeze/resume.** Applying an override snapshots how much of the
  *current* window (if any) was left, as `frozen_remaining_millis`.
  Clearing the override (`.../clear`) then:
  - if a window *was* active when the override was applied, stamps a
    `frozen_resume_until = now + remaining` deadline so the faction stays
    protected for exactly that much longer (a one-off extension of that
    occurrence) before the ordinary wall-clock schedule takes back over;
  - if no window was active when the override was applied, simply removes
    the override — the wall-clock schedule was never paused in any
    stateful sense, so nothing needs to be replayed.
- **Overrides are completely silent to the affected faction** — no chat
  message is ever sent to faction members when staff force a state. Only
  the acting staff member sees a confirmation.
- Overrides persist across restart (`faction_shield_overrides`).

## `/f shield` and `/f who`

`/f shield` (no arguments) shows the caller's own faction: ACTIVE/INACTIVE,
time remaining or time to next activation, the live schedule, any pending
schedule and when it activates, and `ADMIN OVERRIDE` + forced state when
one is in effect.

`/f who <faction-or-player>` is FactionsUUID's own native command and is
**not** cancelled or replaced — unlike `/f map`, its output is plain chat,
not baked-in hover text, so there's nothing to disassemble around.
`ShieldCommand` schedules a one-tick-delayed follow-up message after the
native command's own output prints, showing the same Shield
state/countdown/override fields for the faction being looked up.

## Shield event log

Schedule submissions, pending-schedule activation, and staff
force-enable/disable are written to `faction_shield_log`
(`faction_id`, `action`, `actor_uuid`, `details`, `created_at`) for a
future `/f logs` reader. **`/f logs` does not exist yet anywhere in this
codebase** — this phase only writes the rows in the right shape; building
the read-side command is out of scope here. Per spec, override actions
*are* recorded here even though they are never announced to the affected
faction in chat.

## Data model

New tables (`me.vertex.core.shield.ShieldStorage`), added to
`StorageMigrator`:

- `faction_shields` — one row per faction ever seen created: live
  schedule (`schedule_start_minute`/`schedule_duration_minutes`,
  nullable), pending schedule + its activation deadline, `frozen_resume_until`
  (the override-resume extension described above), and
  `new_faction_eligible_at`.
- `faction_shield_overrides` — one row per faction currently under a staff
  override: forced state, the frozen remaining-window snapshot, who set
  it, and when.
- `faction_shield_log` — write-only event log described above.

This is one table more than the two sketched in the original brief
(`faction_shields` / `faction_shield_overrides`); `faction_shield_log` was
split out as its own append-only table rather than folded into
`faction_shields` so a future `/f logs` reader never has to reason about
mutable state while paginating history — the same reasoning
`base_claim_region_chunks` used in Phase 1.

### Deviation from the plan's schema sketch

The plan's sketch described a more general weekly `schedule_data` blob.
This phase instead models a schedule as a single daily recurring window
(`schedule_start_minute` + `schedule_duration_minutes`) rather than an
arbitrary set of weekly windows. This was a deliberate simplification: the
authoritative section of `addme.md` this phase was meant to read from
turned out, when re-checked at implementation time, to no longer contain
the Faction Shield spec described in the approved plan (see the phase's
completion note for the calling agent) — so the schedule shape was chosen
to satisfy every requirement that *is* unambiguous in the plan text
(real-world-time-based, survives downtime, lockable, one schedule shared
across all of a faction's Base Claims, activation delay with an
in-progress-window carve-out, override freeze/resume with a genuine
"remaining time" concept) with the simplest model that supports all of
them cleanly. If a richer weekly schedule is wanted later,
`ShieldSchedule` is the single class to extend — `ShieldManager` never
assumes anything about its shape beyond `isActiveAt`/`currentWindowEndMillis`/
`nextActivationAfter`.

## Commands & permissions

| Command | Permission | Notes |
|---|---|---|
| `/f shield` | *(none — always visible)* | Shows the caller's own faction's Shield status. |
| `/f shield set <HH:mm> <minutes>` | `vertex.shield.set` | Leader/Co-Leader only; rejected until the New-Faction Shield Delay has passed. |
| `/f shield admin <faction> active\|inactive\|clear` | `vertex.admin.claims` | Every attempt is console-logged, permitted or not. Silent to the affected faction. |
| `/f who <faction-or-player>` | *(native FactionsUUID permission)* | Native command untouched; Vertex appends a Shield status follow-up message. |

## Configuration

- `shield.yml` — activation delay, new-faction delay, sweep interval,
  combat-protection toggle.
- `lang/en_us.yml`'s `shield:` section — every player-facing message,
  including override confirmations shown only to staff.
