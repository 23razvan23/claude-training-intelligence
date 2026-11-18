# ADR 0019: Schedules: entering a plan by hand, session history, the Plan screen

- Status: accepted (stage 2-1a PR 1, 2026-11-18; coach answers the same day)
- Related: ADR 0018 (Phase 2 schema, V22: `demand` is the only key marker, append-only `planned_session_revision`), SPEC.md Module 3. The spreadsheet import (stage 2-1a PR 2) adds to this ADR.

## In plain words
The adaptive engine (stages 2-2 to 2-4) needs a schedule to adapt. This stage lets the coach type one in on the new **Plan** tab: a plan (name and dates), then sessions with a date, a type, a demand (key, supporting or easy), a workout description and targets. Every change to a session is kept in its history: who changed it, when, and what it looked like before and after. Athletes see their own plan on the same tab, read-only.

No database change was needed. Nothing here touches features, scores, the model or the live holdout.

## Decisions

### D1. One active plan per athlete (coach, 2026-11-18)
A new plan starts ACTIVE. Creating a second one while the first is ACTIVE is refused ("archive or complete it first"). Sessions added without naming a plan go into the ACTIVE plan; with no active plan, the screen asks the coach to create one first. The check is in the service, not a database index: a unique index would need a migration, and with a single coach two clashing requests are not realistic. If that changes, add a partial unique index `(athlete_id) WHERE status = 'ACTIVE'`.

### D2. A session outside the plan's dates stretches the plan (coach, 2026-11-18)
Adding or moving a session before the start or after the end moves the plan's start or end to include it. Shortening a plan so that it would leave a session outside is refused, so no session ever sits outside its plan.

### D3. Session types and the demand a new session starts with [configurable]
The types are the fixed list of the spec's session library (coach liked the wording): easy, recovery, long run, long run with fast finish, threshold/tempo, VO2max intervals, race pace, hills, strides, race, cross-training, rest. The codes are the same as `session_template.session_type`.

A new session starts with a demand that depends on its type:
- KEY: long run, long run with fast finish, threshold/tempo, VO2max, race pace, race;
- SUPPORTING: hills;
- EASY: everything else.

The coach can change it on every session. **Rationale:** these are the sessions a week is usually built around, which the spec's guardrail "protect key sessions" is about. This is a coaching convention, not a measured fact. The defaults live in `planning.SessionTypes` and move to the settings store when stage 2-2 builds it. `demand` stays the only key marker (ADR 0018 D1).

### D4. What a session holds
- **Date, type, demand, title.** The title is optional; without one the screen shows the type's name.
- **Workout description.** The coach's own words for structure, pace, heart-rate zone or RPE (coach: pace, zone and RPE as text for now). It is stored as a single text step in `planned_session.structure` (`[{"text": "…"}]`), so Phase 3's structured steps fit the same column later.
- **Distance (km) and duration (min).** Numbers. Distance is stored in metres (`target_distance_m`) and shown in km.
- **Estimated strain (0–21).** Optional, and left empty unless the coach fills it in (coach). We do not invent a number.
- **Notes. Status:** PLANNED, DONE, SKIPPED or REPLACED.
- **Not used yet:** `plan_week`, `template_id` and `is_taper` stay empty; the plan builder (Phase 3) fills them. Weekly totals on the screen are added up from the sessions.

The input limits only catch typos; they are not training advice:
- distance 0–500 km;
- duration 0–1440 min;
- strain 0–21 (its scale, ADR 0008);
- title up to 200 characters;
- text fields up to 2000 characters.

### D5. History of every change, and deletes
Every create, edit and status change writes one `planned_session_revision` row in the same transaction as the change. The row holds the session before and after (the API's session JSON) and who made the change. A status change is an EDIT row, since the table knows only CREATE, EDIT and DELETE. History rows cannot be changed (ADR 0018 trigger).

**A delete removes the session and its whole history**, because the history rows belong to the session (`ON DELETE CASCADE`, ADR 0018 D5). So the screen shows "set it to Skipped or Replaced instead" next to Delete, and asks before deleting. The service still writes a DELETE row first, so nothing needs to change if the history is later kept after a delete. Keeping it would need a migration: make `planned_session_id` nullable with `ON DELETE SET NULL`. Delete is meant for sessions entered by mistake.

### D6. Who may do what (coach, 2026-11-18)
- The coach reads and edits every athlete's plan.
- An athlete reads only their own plan and its history; every change is coach-only (403 otherwise).
- The coach also wants athletes to be able to **ask** for a session to be moved or changed when a day is missed. That is deferred to a later stage, so it lives in the backlog and is not built here.

### D7. The Plan screen
It lists this week (from Monday) and the next 7 weeks, grouped by week, with each week's session count, km and key-session count. Skipped and replaced sessions are struck through and left out of the totals. Key sessions have a coloured edge and a "Key" badge. Today is the athlete's local date (their timezone).

The screen has no sideways scrolling at 390 px, works light and dark, and all text goes through i18n keys. Eight weeks is a display choice, not a training number. The full calendar with drag and drop comes in Phase 3 (stage 3-3).

## API
- `GET /api/plan/session-types`: the types and their default demand.
- `GET|POST /api/athletes/{id}/plans` and `PUT /api/plans/{planId}`.
- `GET /api/athletes/{id}/sessions?from=&to=` (at most 400 days per request) and `POST /api/athletes/{id}/sessions`.
- `PUT|DELETE /api/sessions/{sessionId}` and `GET /api/sessions/{sessionId}/revisions`.

## Why the live holdout and the golden check are unaffected
This stage writes only `plan`, `planned_session` and `planned_session_revision`. It adds no migration and changes nothing in `metrics/` or `ingestion/`. Feature engineering and scoring read none of these tables, and the golden check (ADR 0017) neither hashes nor replays them. So it still prints `0 differences`, `FEATURE_VERSION` stays `f3`, and the holdout count is not restarted.

## Tests
- `PlanIT`:
  - entering and editing, with a history row per change and the right before and after;
  - a session outside the plan stretches it;
  - history rows can't be changed, and a delete takes the history with it;
  - bad input is refused with plain messages and nothing is saved;
  - the one-active-plan rule;
  - athlete read-only access, and no access to another athlete's plan.
- `SessionValidatorTest`: defaults and messages.
- Frontend: `plan.test.ts` (weeks, totals, timezone) and `PlanPage.test.tsx` (list, adding a session where the demand follows the type, athlete read-only, no plan yet).
- Screenshots: `docs/screenshots/2-1a/`.
