# ADR 0022: The rules engine and its suggestions (stage 2-3)

- Status: accepted (stage 2-3 PR 1, 2026-11-20). The coach answered the plan's ten questions (Q1–Q10) with "Yes", which I took as accepting every recommendation. **Flagged:** if any answer below is not what you meant, say so; each one is a setting or a small code change.
- Related: SPEC.md Module 3 ("Decision approach", "Starting rules", "Guardrails", "Output per suggestion"); ADR 0018 (D3 `dedup_key`, D4 what a suggestion records, D5 `changed_by_suggestion_id` and revisions); ADR 0019 (plan entry); ADR 0020 (check-ins); ADR 0021 (signals, settings store and its 2026-11-19 amendment); ADR 0008 (scoring, **unchanged**).
- Delivered in two PRs. **PR 1** (this one): the engine, its rules and guardrails, the tests, storing suggestions, when it runs, the API and the `suggest` command. **PR 2**: accept / edit / reject (accepting changes the plan), pending suggestions on Today and per session on Plan, and the "Taper" checkbox.

## In plain words
Every morning, after the Garmin sync, the engine looks at the next 7 days of the plan and at the day's signals:
- recovery and how long it has been yellow or red;
- sleep debt;
- the check-in (illness, niggles);
- running volume against tolerance, and the running ACWR.

When one of the spec's seven starting rules applies, it proposes a change: keep, reduce, swap, replace, move or add. Each proposal comes with one to three plain reasons with the numbers behind them, and a confidence level. Nothing in the plan changes until you accept a suggestion (PR 2).

The engine is a fixed set of rules, not a learned model. The same inputs always give the same suggestions. Every number it uses is a setting with a one-line reason, and every suggestion records which rules, settings and signals produced it.

## Decisions (the coach's answers to Q1–Q10)

### Q1. Race week and taper
- **Race week** = the 7 days ending on a session of type *race* (`engine.raceWeekDays` = 7).
- **Taper** = a session marked taper. The column (`planned_session.is_taper`) exists already, and the engine reads it now. The checkbox to set it comes with PR 2, so until then only race week applies.
- The race session itself is never changed by the engine: racing is the coach's call.

### Q2. Which days each rule looks at
Recovery is only known for today. So the rules that read today's body run on today's session only: red, yellow, sleep debt and illness. Tomorrow's run will look at tomorrow's session. The rules about the week cover all 7 days: running volume, niggle and restoring volume.

### Q3. A "hard" session
A hard session is one marked **key** or **supporting**. A rest day never is.

### Q4. How a session is reduced
The workout is free text, so the engine cannot rewrite "6×1 km". It cuts the distance and time by **25 %** (`engine.reduce.share`, the middle of the spec's 20–30 %). It also puts a note in front of the workout: "cut reps or time at intensity by 25 %; keep the warm-up and the session's purpose". You adjust the details with Edit.

The estimated strain is not scaled with the distance (it is a log scale), so it stays as you entered it. A replacement session starts without one.

### Q5. Running volume and ACWR
- **Volume:** for each of the next 7 days, the engine adds up the km run and the km planned in the 7 days ending on that day. Today counts once: the larger of the km already run and the km planned, because a run this morning may not be marked done yet. If that is above tolerance + 10 %, it trims:
  - the easy runs first, each by at most 30 % (`maxEasyCutShare`);
  - then the long run, by at most 20 % (`maxLongCutShare`).
  - Confidence is medium, or high when the running ACWR is above 1.3 too.
- **ACWR alone:** the running ACWR above 1.3 with the volume in range gives only a light trim of easy runs (20 %, `acwrOnlyEasyCutShare`). The confidence is **low**, and the reason says "one signal among several, disputed". ACWR never decides on its own.
- The engine uses **yesterday's** running ACWR, because today's load is not finished yet (ADR 0021 D5).
- Planned runs without a distance can't be counted; the reason says so.
- **Heads-up from the plan:** with your tolerance of about 37.6 km/week, the line is about 41.4 km in any 7 days, so this rule will fire often while the tolerance cap stays at 1.0.

### Q6. Restoring missed volume
- **Conditions:**
  - recovery is green today;
  - in each of the last **2** full Monday–Sunday weeks, you ran less than **80 %** of the planned km (`restore.underShare`);
  - no niggle, no recent illness, and the volume rule is not trimming.
- **Action:** the easy runs of the next 7 days get **10 %** of that week's planned km more in total (`restore.stepShare`), spread over them in proportion. The total never goes above tolerance. This is the "ADD" action on an existing session: the session gets longer; no new session is created.
- **One step at a time:** no new step within 7 days of an accepted one.

### Q7. Red day: where a hard session goes
In this order:
1. **Swap** with the next easy or rest session at most 3 days later (`red.swapWindowDays`), on a day with nothing hard. The same Monday–Sunday week comes first.
2. A **key** session with no easy day to swap with **moves** to a free day of the same week. A free day has no other session (a rest day is fine), no key session the day before or after, and is not in race week or taper.
3. Then a swap or a move into next week, still within the 3 days.
4. Otherwise it becomes an **easy run**: 60 % of its distance and time (`replace.easyShare`). For a key session the confidence is medium, and the reason says there was no room to move it.

### Q8. Illness and the return
- Illness reported **today or yesterday** counts as current (`illness.activeDays` = 2), because athletes don't check in every day. Today's session becomes **rest** ("rest, or very easy movement by feel"). A key session first tries to move to a free day of the same week after the return days.
- **Return day 1:** a hard session becomes an easy run (or a key session moves).
- **Return day 2:** a hard session is reduced by 25 % (`illness.returnDays` = 2).

### Q9. Several rules on one session
One suggestion per session per run. The most conservative proposal wins. The engine compares, in order:
1. how hard the session becomes;
2. what is left on its day;
3. its time;
4. the rule order: illness, niggle, red, sleep, yellow, volume, restore.

The losing rules are named in a reason: "Other signals point elsewhere (…); the more conservative option was chosen." When a check-in rule (illness, niggle) wins on a green day, the reason says: "Recovery is green, but the check-in says otherwise; the check-in comes first." That is the spec's "subjective overrides physiology". A session is in at most one suggestion; a swap today claims its partner. A suggestion has at most 3 reasons.

### Q10. "Keep" suggestions
Not stored. A day without suggestions shows "no changes suggested" (PR 2), so the log holds only real proposals.

## The rules in short (rules version `r1`)
| Spec rule | When (settings) | Suggestion |
|---|---|---|
| Red + hard session | today red | swap → move (key) → easy (Q7); in race week or taper only the easy run |
| Yellow 2+ days + hard | today yellow, non-green streak ≥ 2 (`yellow.streakDays`); a red day before counts too (the cautious reading) | reduce 25 % (Q4); medium confidence |
| Volume / ACWR | 7-day km > tolerance × 1.1, or running ACWR > 1.3 | trim easy, then long (Q5) |
| Sleep debt | debt > 1 night's need (`sleep.debtNights`), VO2max today | threshold, same time, if green or unknown; easy if yellow (red is left to the red rule); medium confidence with fewer than 5 recorded nights (`sleep.minKnownNights`) |
| Niggle | a niggle episode open today (ADR 0021 D11) | every session at intensity in the 7 days (threshold, VO2max, race pace, hills, fast finish, strides) → an easy run of the same time "on flat ground, no downhill; cross-training is fine instead". Not moved: a later day does not help a niggle. **Health message** on Today when it has lasted 3+ days (`niggle.healthMessageDays`) |
| Illness | reported today or yesterday | rest; then day 1 easy, day 2 reduced (Q8) |
| Green + under plan | Q6 | add one step on the easy runs |

## Guardrails
- **Never more load on a red day:** any suggestion that adds load (ADD, a harder or longer session), or brings a hard session onto today, is dropped on a red day.
- **Protect key sessions by moving them:** the red and illness rules move a key session before turning it easy (Q7, Q8). The volume rule shortens the long run last and least.
- **Hold weekly targets unless recovery forces otherwise:** swaps and moves stay in the same week first, so the week's km don't change. Only recovery, sleep, a check-in or the volume rule lower them.
- **Conflicts:** the conservative option, said in the reasons (Q9).
- **Race week and taper get only reductions:** a swap, move or add touching a race-week or taper session (or moving a session into race week) is dropped. The red rule offers the easy run instead.
- A dropped suggestion is listed in the run's notes, so nothing disappears silently.

## What a suggestion records (ADR 0018 D4)
- **Action**, the session (and the swap partner in `counterpart_session_id`), and `for_date` (the session's date).
- **before:** `{"session": …, "counterpart": …}`, the sessions as the Plan API shows them.
- **after:** the same shape, as the Plan API takes a session (date, type, demand, title, workout, km, minutes). Accepting is then one call to the plan code that typed sessions use, so the same checks apply and a history row is written (PR 2).
- **Reasons:** a list of `{code, text, params}`: the English text, plus a code and the numbers, so a Romanian translation can be added later.
- **Confidence** (low / medium / high), and the **inputs snapshot:**
  - the rule;
  - its numbers;
  - today's and yesterday's signals as the rules read them;
  - the run's notes.
- **Versions:** `rules_version` (`r1`), `settings_version`, `signals_version` (`s3`), `model_version` (the ACTIVE model, where readiness came from), `engine_run_date`.

## Re-runs (ADR 0018 D3)
- The engine runs inside a per-athlete database lock, so two runs never interleave (the morning sync, a check-in and your button can coincide).
- **The same suggestion with exactly the same content** (inputs, before/after, reasons): kept as it is.
- **The same suggestion with fresher inputs** (e.g. a later sync changed this morning's score): the old one is marked **EXPIRED** first, then the new one is stored.
- **A pending suggestion the engine no longer makes** (the niggle is gone, the day has passed): EXPIRED.
- The database refuses a second PENDING copy (`dedup_key`).
- **A session with a suggestion you already decided today** (accepted, edited or rejected) is left alone until tomorrow. Otherwise a reduction accepted at 08:00 could be reduced again at 09:00, and a rejected suggestion would come straight back.

## When it runs
- After **every successful morning Garmin sync**, even with no new data: a new day is reason enough. A failure is logged and never fails the sync.
- **After a check-in**, so an illness or niggle reported now updates the suggestions at once. **Assumption, flagged:** the prompt named the sync and on demand only; this costs nothing and makes the Today card current.
- **On demand:** `POST /api/athletes/{id}/suggestions/run` (coach; recomputes the signals first; PR 2 adds the button), and `docker compose run --rm app suggest --athlete 1`, which prints the pending suggestions, health messages and notes.
- **Read:** `GET /api/athletes/{id}/suggestions?status=PENDING&from=&to=` (AccessPolicy: an athlete sees only their own; default from today, 14 days).

## Why the live holdout and the golden check are unaffected
- **No migration:** every column used exists since V22. New settings (`engine.*`) are saved as a new settings version on first read (ADR 0021 D2).
- **What the engine reads:** `day_signals`, `planned_session`, the running km (as the signals read them) and the ACTIVE model's version.
- **What it writes:** only `adaptation_suggestion` (PR 2 adds `planned_session` and its history through the plan code).
- Nothing in `metrics/` or `ingestion/` changed. `day_features`, `FEATURE_VERSION` (`f3`) and scoring are untouched.
- The sync calls the engine **after** the run is recorded as succeeded, outside `FeatureRecomputeService.recompute`, which is what the golden check (ADR 0017) replays. So it still prints **0 differences**.

## Tests
- `StartingRulesTest`: one nested group per starting rule, each "given (signals) → expect (suggestion + reason)", with the exact reason texts and the cases where a rule must not fire. 25 scenarios.
- `GuardrailsTest`: one or more per guardrail:
  - no added load on a red day;
  - a key session moved, not replaced, while there is room, and the reason when there is none;
  - the swap stays in the week with unchanged km;
  - check-in beats green recovery and the reason says so;
  - the conservative option wins and names the other signal;
  - one suggestion per session, at most 3 reasons;
  - race week and taper get reductions only;
  - races are untouched;
  - ACWR alone stays low confidence;
  - determinism;
  - a session decided today is skipped;
  - notes when signals are missing.
- `SuggestionIT` (real database):
  - every version on the row, and before/after shaped like the plan API;
  - the same inputs are kept, fresher inputs expire the old suggestion and store a new one, a suggestion no longer made expires;
  - the duplicate guard;
  - nothing re-proposed after a rejection the same day;
  - access, and 404 for an unknown athlete;
  - an illness check-in through the API produces the rest suggestion at once.
- `SuggestCliTest`.

## PR 2: deciding on suggestions, and the screens (2026-11-20)

### In plain words
Pending suggestions now show on **Today**, below the morning numbers, with any health message above them. On **Plan**, each one shows on the session it would change. As coach you tap:
- **Accept:** the plan changes as suggested;
- **Edit:** change the date, type, distance, time or workout first, then "Accept my version";
- **Reject:** with an optional note.

Every decision is kept: who made it, when, and your note. "Check the plan now" on Today runs the engine at once, with fresh signals. An athlete sees their own suggestions and health messages, without the buttons.

### D1. Accepting goes through the plan code
- Accepting calls `PlanService`, the same code a typed session goes through, so the same checks and messages apply. A bad edit (e.g. an unknown type) is refused, and nothing changes.
- **Reduce, add and move:** one session edited.
- **Swap:** both sessions edited.
- **Replace:** a new session in the same plan with `replaces_session_id` pointing at the old one. The old one is set to REPLACED, not deleted, so its history stays (ADR 0018 D5).
- Every changed session gets `changed_by_suggestion_id`, and a `planned_session_revision` row that names the suggestion. Everything happens in one transaction: the plan and the decision change together, or not at all.

### D2. Edited or accepted
- The suggestion becomes **EDITED** when the version you accept differs from the suggested one, and **ACCEPTED** otherwise.
- The comparison is on the session itself, so 8 and 8.0 km count as the same, and so do an empty note and none.
- The suggestion row keeps what the engine proposed. The version actually applied is in the history rows linked to it. That pair is the training data for stage 4-3. No migration was needed.

### D3. Out-of-date suggestions are refused
- If a session was edited after the suggestion was made (its last-changed time differs), or was deleted, accepting is refused: "The session was changed after this suggestion was made. Run the check again."
- A suggestion that was already decided or has expired can't be decided again.

### D4. Taper
- The session form has a **"Part of a taper"** checkbox; Plan shows a Taper badge.
- The plan API takes an optional `taper`: left out, a new session is not taper and an edit keeps the current value. So the spreadsheet import and older screens are unaffected.
- The engine has read this flag since PR 1 (Q1).

### D5. Screens and API
- **Today:** the Suggestions section with health messages, one card per pending suggestion (action, now → suggested, swap partner, reasons, confidence, Accept / Edit / Reject) and "No changes suggested for the next 7 days" when there are none.
- **Plan:** a "Suggestion" badge and the card on the session it changes, and a "Suggestions" link on every session for its history, with each decision.
- **Display:** 390 px without sideways scrolling, light and dark, every string an i18n key. A reason is shown through its code's translation when one exists, otherwise the English text. Screenshots: `docs/screenshots/2-3/`.
- **API:**
  - `POST /api/suggestions/{id}/accept` (body optional: `after`, `note`) and `POST …/reject` (`note`), coach only;
  - `GET /api/sessions/{id}/suggestions` and `GET /api/athletes/{id}/suggestions/today` (AccessPolicy).

### Tests (PR 2)
- `DecisionIT`:
  - accepting a swap (both sessions moved and linked, two history rows);
  - accepting a replacement (new session, old one REPLACED, CREATE and EDIT rows);
  - edit → EDITED with the coach's version; an unchanged version → ACCEPTED;
  - reject with a note leaves the plan alone, and a second decision is refused;
  - an outdated suggestion is refused; a bad edit changes nothing;
  - only the coach decides, athletes read their own;
  - the taper flag is saved, kept on an edit, and guards the session.
- Frontend: `suggestions.test.ts`, `Suggestions.test.tsx` (card, accept, reject with note, edit, check now, athlete view, nothing to change), and a `PlanPage.test.tsx` addition (badge and card on the session, taper checkbox).

### Golden check
- PR 2 writes `planned_session`, `planned_session_revision` and `adaptation_suggestion`, through the plan code.
- No migration, nothing in `metrics/` or `ingestion/`; `FEATURE_VERSION` and scoring unchanged. So the golden check still prints **0 differences**.
