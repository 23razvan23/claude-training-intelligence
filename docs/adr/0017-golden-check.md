# ADR 0017: The local golden-file check: how a silent change to real scores is caught

- Status: accepted (review item R-14, 2026-11-17)
- Related: ADR 0008 (scoring rules, frozen during the holdout), ADR 0009 (live holdout), ADR 0010 (raw revisions), ADR 0012 (daily scoring storage), ADR 0015 (Garmin sync).

## In plain words
The live holdout (to 2027-02) only means something if scores are produced the same way every day. A code change that quietly moved your real numbers would mix two models in one verdict, and the small synthetic test files would not notice.

Real data can never be committed, so the check runs on your Mac:

- **`golden freeze`** takes a snapshot of 60 days: for each day, a hash of that day's features and of that day's score from the ACTIVE model, one hash per column. Nothing readable is stored, only hashes, plus what the numbers were built from.
- **`golden check`** calculates the same days again, from the raw data **as it was when you froze**, and compares. It prints only dates and column names, never a value. "0 differences" means nothing moved.

The check changes nothing you have stored. It does its calculation inside a database transaction that is always thrown away.

## Decisions

### D1. The check replays from the raw data as it was at freeze time
Raw data is append-only (ADR 0003, ADR 0010): a new export, a Garmin revision of a day, a sync or a new Whoop export only *adds* rows, each tagged with its import batch number. At freeze we record the highest batch number. At check time, inside the thrown-away transaction, rows of later batches are removed, and then exactly what **Recompute features** does is run (activity loads, day features, the ACTIVE model's scores). The result is compared with the frozen hashes.

So new data can never look like a code change. What is left after the replay is, by construction, a change in code or configuration: the calculation, a constant in `application.yml`, a SQL view, the scorer.

### D2. What legitimately changes a frozen day, and how the check tells
| What happened since the freeze | What the check says |
|---|---|
| New Garmin export, a day exported again, a morning sync, a new Whoop export | Nothing. Later batches are ignored by the replay. **0 differences** (exit 0). |
| Code or a constant in `metrics/` or `ingestion/`, a SQL view, `application.yml` | **CODE CHANGED**, with dates and column names (exit 1). |
| The athlete's profile changed (HR max, resting HR, sex, timezone) | **INPUTS CHANGED: profile** (exit 3). Day differences, if any, are listed with a warning that they may come from it. |
| A new ACTIVE model, or a model's coefficients changed | **INPUTS CHANGED: model** (exit 3). Features are still compared; scores are not. |
| Raw rows removed or edited in place (a migration, a manual correction) | **INPUTS CHANGED: raw data in <table>** (exit 3). |
| The feature version changed (`f3` to something else) | Cannot compare (exit 2): freeze again. It is a deliberate change. |

After a legitimate change, freeze again. Freezing never overwrites: it writes a new dated file and the check uses the newest.

Not detected by design: a change that is paired with a change of inputs at the same time. A day whose inputs changed cannot be attributed to code. Check right after the update, before anything else changes, and the two are never mixed.

### D3. Freeze checks itself first
Freeze refuses if an import or sync is running, or if the stored rows differ from a fresh calculation by the current code (typically: no Recompute features after the last update). Otherwise a check could never start clean. The message names the dates and columns.

### D4. Default window
60 days ending 2 days before the last day with features. The morning sync re-fetches the previous 2 days, so those are still being corrected and make a poor reference. The replay does not depend on this choice (D1); it only affects how representative the window is. Override with `--from` and `--to`.

### D5. What is hashed
Every column of `day_features` except `athlete_id`, `cycle_date`, `feature_version`, `computed_at`. Every column of the ACTIVE model's `derived_day_metrics` row except `athlete_id`, `cycle_date`, `model_version`, `computed_at`. The three JSON columns (`loads`, `contributors`, `data_quality`) are flattened, so a difference is named precisely, for example `contributors.terms.rhr_dev.points`. `data_quality.stale` is left out: it depends on when the row was written, not on the code. Numbers are rounded to 10 significant digits before hashing, so a float difference in the last digit (another CPU or JVM) is not a change.

### D6. The file holds hashes, but treat it like a backup
Each column's hash is keyed with a random salt stored in the same file. A value with only a few possibilities (a recovery between 0 and 100) could still be guessed by someone holding the file. It is health data in disguise. It lives in `./backups/golden/` (git-ignored with the rest of `./backups/`), is written readable only by its owner where the file system allows it, and is never to be shared, attached or committed.

### D7. Files and mounting
The `app` container mounts only `./backups/golden` (not the database dumps next to it) at `/backups/golden`. Create it once with `mkdir -p backups/golden`. The file also records the model version and a fingerprint of its coefficients, the feature version, the profile, the app version and, if the image was built with `GIT_COMMIT`, the commit. Without `GIT_COMMIT` the commit shows as "unknown": the check does not depend on it.

### D8. `--scores-only`
`golden freeze --scores-only` freezes and replays only the scoring, taking the stored `day_features` as given. It exists so CI can run the same code on the synthetic parity fixture (R-03), which has features but no raw data. It is also a way to ask "did the scorer move, apart from the features?". The check follows the mode recorded in the file.

## Consequences
- The check costs about as much as pressing Recompute features, and holds database locks until it ends: do not run it during an import or sync (it refuses if one is running).
- It needs no Recompute features first: it does not read the stored rows.
- It cannot see a change in something the replay does not run: the frontend, the display of Today. It guards the numbers.
- Tests (synthetic data only): `GoldenHasherTest`, `GoldenCliTest`, `GoldenIT` (clean freeze/check and nothing stored changes; a later import is not a code change; a changed view standing in for a code change names date and column; profile, model and raw-row changes are "inputs changed"; freeze refuses stale rows and running imports; the scoring-only mode on the parity fixture names the day and column of a changed feature, a scorer that gives other numbers for the same model, and a tampered score hash).
- A scoring constant (a coefficient) belongs to the model, so changing one is a new model, reported as inputs changed. A change in how the scorer *uses* the constants shows up as CODE CHANGED on the scores columns.
