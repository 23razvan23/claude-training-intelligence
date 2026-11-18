# ADR 0019: Schedules: entering a plan by hand, session history, the Plan screen

- Status: accepted (stage 2-1a PR 1, 2026-11-18; coach answers the same day)
- Related: ADR 0018 (Phase 2 schema, V22: `demand` is the only key marker, append-only `planned_session_revision`), SPEC.md Module 3. The spreadsheet import (stage 2-1a PR 2) is the import note at the end (D8–D12).

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

## Import note: plans from a spreadsheet (stage 2-1a PR 2, coach answers 2026-11-18)

### In plain words
The coach drafts a plan with Claude in a chat and imports the CSV (or an Excel file). On the Plan tab, **Import from spreadsheet** shows a preview of every row first. Nothing is saved until the coach presses **Import**, and then every row is saved or none is.

### D8. The file format (agreed with the coach)
`date,type,demand,title,workout,distance_km,duration_min,notes`, one row per session; rest days optional (type `rest`). All eight columns must be present, in any order.
- **date**: DD/MM/YYYY. In an XLSX a real date cell is accepted too (Excel stores it as a day count).
- **type**: one of the codes of `SessionTypes` (easy, recovery, long, long_fast_finish, threshold, vo2max, race_pace, hills, strides, race, cross, rest). No mapping was needed: the coach's names are the codes. Letter case, spaces and hyphens are tolerated ("Long fast finish").
- **demand**: key, supporting or easy; empty gives the type's default (D3).
- **title** optional; **workout** is free text, stored as the single text step (D4).
- **distance_km**: a decimal with a dot (12.5); **duration_min**: whole minutes. Both optional. A comma decimal (12,5) is refused with a message rather than guessed at, because it could be a thousands separator.
- **notes** optional; standard CSV quoting. A row with more values than columns (typically an unquoted comma in the workout) is refused and says so.
- Estimated strain is not in the file; it stays empty (D4).

**Tolerant where it is safe:** header case and spaces, a UTF-8 BOM, empty rows anywhere, `;` as the CSV separator (some Excel regions save CSV that way; chosen when the header line has more `;` than `,`). **Strict elsewhere:** an unknown, missing or repeated column, a date that is not DD/MM/YYYY or does not exist, a number that is not in the format above, an unknown type or demand, and every limit of `SessionValidator` (D4).

### D9. One rule set: `SessionValidator` and `PlanService`
Each row becomes a `SessionRequest` and goes through `SessionValidator.check`, the same check typed sessions get, so messages and limits agree. The commit saves each row with `PlanService.createSession`, so an imported session follows exactly the rules of a typed one: it goes into the ACTIVE plan, a date outside the plan stretches the plan (D2), and a `planned_session_revision` CREATE row is written per session (D5). Row numbers in messages are the spreadsheet's own row numbers (header = 1). A multi-line quoted note counts as one row in a CSV, so a later row can be numbered lower than the line you see in a text editor.

### D10. Preview, then commit; all or nothing
- `POST /api/athletes/{id}/plan-import/preview` reads the file and returns every row with its errors and notes. It writes nothing.
- `POST /api/athletes/{id}/plan-import/commit` takes the **same file again**, checks it again, and saves everything in one transaction. Nothing is kept on the server between the two calls, so a preview cannot go stale or be tampered with. A file with any error is refused as a whole ("Nothing was imported. …"); a failure while saving rolls back every row, the history rows and the plan's stretched dates.
- Both are coach only (403 for an athlete).

### D11. Sessions already on the same date (coach, 2026-11-18)
The imported session is **added alongside** the existing one (a real day can hold two sessions, and a re-import after an edit can then be tidied by hand or by setting the old one to Skipped). The preview marks the row with a note: "this date already has a session (easy); the new one is added next to it". Other notes, none of which block the import: another row in the same file has the same date; the date is in the past (in the athlete's timezone); the date is outside the plan, so the plan will be stretched (and the preview shows the new dates).
Importing the same file twice therefore creates every session twice. That is deliberate (nothing is silently dropped or overwritten, and each addition has its own history); the preview's "already has a session" notes are the warning.

### D12. Other decisions and assumptions
- **No active plan:** the import is refused ("Create a plan first, then import"), as for a typed session (coach). The plan's dates still stretch to fit the file (D2).
- **No new library:** CSV uses `commons-csv`, which the project already has. XLSX is read with the JDK's zip and XML parts: first sheet, header row; shared, inline and number cells. Formulas (their cached value is read), merged cells, other sheets, and the 1904 date system are not handled. Old `.xls` files are refused with a hint to save as .xlsx or CSV. XML external entities and DTDs are switched off, and the unzipped size is capped.
- **Limits against mistakes, not training numbers [configurable]:** 2 MB, 500 rows (a plan is a few months of one session a day), 20 MB unzipped. Constants in `PlanImportParser`.
- **Assumption:** the file is UTF-8 (Excel: "CSV UTF-8"); a file that is not is refused with that hint.

### Why the live holdout and the golden check are still unaffected
The import writes only `plan`, `planned_session` and `planned_session_revision`, through the same service as typed entry. No migration, nothing in `metrics/` or `ingestion/`, `day_features`, `FEATURE_VERSION` (still `f3`) or scoring. The golden check (ADR 0017) neither hashes nor replays these tables and still prints `0 differences`.

### Tests (import)
- `PlanImportParserTest`: CSV and XLSX parsing, the tolerances, every row message, column and file problems, limits, and that a CSV row and an XLSX row mean the same.
- `PlanImportIT`: preview saves nothing; commit saves through the same rules (stretched plan, default demand, workout step, one history row per session); XLSX; errors refuse the whole file and nothing is saved; same-date notes and alongside import; no active plan; wrong file; coach only.
- `PlanImportRollbackIT`: a failure on the third row leaves no session, no history and the plan's dates unchanged.
- Frontend `PlanImport.test.tsx`: preview before commit, row errors keep Import disabled. Screenshots: `docs/screenshots/2-1a/import-*.png`.
