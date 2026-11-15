# ADR 0012: Daily scoring: what is stored, when it is recomputed, and the data-quality flags

- Status: accepted (stage 1b-1, 2026-11-15; coach decisions D1–D4 confirmed the same day)
- Numbered 0012 because `docs/REVIEW.md` reserves 0009 (live holdout), 0010 (raw revisions) and 0011 (Garmin credentials).
- Related: ADR 0008 (the scoring rules S1–S8; this ADR changes none of them), ADR 0007 (calibration), ADR 0003 (derived data).

## In plain words
ADR 0008 says how a day's numbers are calculated. This ADR says where they are kept, when they are refreshed, and which warnings ("flags") are stored with them. None of it changes a number.

## Decisions

### D1. One service, three callers
`ScoringService` (backend, `metrics` package) is the only code that writes `derived_day_metrics`. It is called by:
- `POST /api/athletes/{id}/scores/recompute?from=&to=` (coach only; both dates optional);
- the command line: `docker compose run --rm app score --athlete 1 [--from …] [--to …]`;
- **Recompute features** (after the features, in the same transaction);
- later, the morning sync (stage 1b-4).

The arithmetic itself is `DailyScorer`, pure code with no database, so the parity test runs it on the committed fixtures.

### D2. Always the whole grid, then only the requested dates are replaced
A day needs yesterday's strain and the previous week's sleep (ADR 0008 S1, S3, S4). So the service always scores the athlete's whole day grid for the ACTIVE model's feature version, then deletes and rewrites only the requested dates. With no dates given, all of that model's rows are replaced.

### D3. Rows of other models are kept (coach, 2026-11-15)
The primary key is (athlete, date, model version). Rescoring with m15 replaces only m15's rows. Rows from earlier models (such as m13) stay as history and are never read by the app. A new `feature_version` column (V14) records which `day_features` version each row was computed from (REVIEW R-15).

### D4. Which days get a row (coach, 2026-11-15)
- Every grid day gets a row, from the athlete's first to last `day_features` date. That includes days with no features row and days without a recovery; for those, the scores are blank and the reasons are stored.
- **Days after the last data date get no row** (ADR 0008 C2). The app then says "last data from <date>". Every recompute returns `lastDataDate`.

### D5. Scoring needs a usable ACTIVE model
Nothing is scored, and the reason is given in plain words, when:
- there is no ACTIVE model;
- its feature version is not the app's;
- its coefficients are not contract `schema_version` 2;
- the coefficients are otherwise unreadable: an unknown strain form, load column or recovery input, a missing block, a scale ≤ 0 or a wrong number of zone weights.

The REST endpoint answers 409. The CLI exits with code 2. Recompute features still recomputes the features and reports `scores.skipped`.

### D6. What each row stores
| Column | Content |
|---|---|
| `recovery_pct`, `recovery_band`, `strain`, `sleep_need_min`, `sleep_performance_pct` | ADR 0008 S7 (rounded half-up; band from the unrounded recovery). |
| `contributors` | `intercept`, and `terms`: for each recovery input, its `value` and `points` (unrounded, null when blank). Also `recovery_raw`, before clipping. Intercept + points = `recovery_raw`. |
| `loads` | The strain inputs used, depending on the form. Load form: `load_column`, `workout_load`, `background_load`, `combined_load`. Zones form: `zone_minutes`, `weighted_zone_minutes`. Also: `monitored_minutes`, `strain_raw`, unrounded `strain`, `prev_strain`, `shortfall`, `sleep_need`, `sleep_performance`. |
| `data_quality` | See D7. |
| `model_version`, `feature_version` | The model's version string and its feature version. |

The unrounded values are kept so that a score can be explained and reproduced later. The coach's database never leaves the coach's machine.

### D7. Data-quality flags
| Key | Meaning |
|---|---|
| `has_features` | false for a grid day without a `day_features` row. |
| `strain_reason` | Why strain is blank (ADR 0008 S2): `NO_DAY_DATA`, `MISSING_WORKOUT_HR`, `NO_ALL_DAY_HR`, `NO_ZONE_MINUTES`. |
| `low_coverage` | Under `min_monitored_minutes` of all-day HR (600 **[configurable]**, stored in the coefficients: below 10 h, background load is partial). This day's strain is lower confidence. |
| `sleep_performance_reason` | `NO_SLEEP` or `NO_DAY_DATA` when sleep performance is blank. |
| `recovery_reasons` | `[{reason, detail}]` in ADR 0008 S6 order; empty when recovery was computed. |
| `recovery_lower_confidence`, `lower_confidence_reasons` | S8: yesterday was low coverage (`LOW_COVERAGE_YESTERDAY`). |
| `drift` | `[{part, input, value, p01, p99}]`: an input outside the training spread stored in the coefficients (`training_ranges`). |
| `profile_changed`, `profile` | The model was built with a different HR max or HR rest; `profile` holds both pairs of values. |
| `stale` | New data was imported after this day's features were computed. |

How the flags are decided:
- **Drift** is checked only for the model parts that produced a value that day: strain inputs when strain was scored; sleep inputs (`prev_strain`, `shortfall`) always, because sleep need is never blank; recovery inputs when recovery was scored. A blank input is never "drift": it already has a reason. p01/p99 are used rather than min/max because the extreme 1% of training days are too few to trust (REVIEW R-02).
- **profile_changed** compares the HR max and HR rest the app would use today with the model's `feature_config`. HR rest is the profile value, or the median Garmin resting HR when the profile has none, which is the same rule the loads use. A value the model did not record (null) is not compared. The flag is the same for every row of a run: it describes today's profile, not the day.
- **stale** is true when the day's `day_features.computed_at` is before the newest successful import's `finished_at`. In other words, Recompute features is needed. Days with no features row are never stale. Because Recompute features rescores straight after recomputing, this mainly shows up when scoring is run on its own (CLI, REST, later the sync) after an import.

## Accepted deviations
- **SPEC "every derived row stores `model_version` and `rules_version`":** a daily score has no separate rules version. Its rules are ADR 0008, which is tied to the contract `schema_version`, and that is tied to the model. Changing a rule means changing Python, recalibrating and getting a new model version (ADR 0008). `rules_version` arrives with the Phase 2 rules engine (`adaptation_suggestion`).
- **The command line skips import housekeeping.** On start-up, the app marks imports that were running as failed, assuming the app crashed. A CLI run next to the running app sets `app.command-line=true` and skips this, so it cannot fail an import that is really still running.

## Consequences
- After every import or profile change, **Recompute features** refreshes both features and scores.
- Activating a new model does not rescore by itself: press Recompute features (or run the CLI) after activating.
- R-14 (golden-file check) can reuse the CLI mode and `ScoringService`.

## Appendix: checking the scores on real data (read-only SQL)
Run in `~/claude-training-intelligence`. These queries only read; they print dates and scores, never notes.

**1. Coverage:** every paired day has a row from the ACTIVE model. `paired_with_score_row` must equal `paired_days`, and `feature_days_with_row` must equal `feature_days`. `paired_with_recovery` is smaller by the days whose recovery has a named reason (query 3).
```bash
docker compose exec db psql -U training -d training -c "SELECT mv.version AS active_model, count(*) FILTER (WHERE f.has_garmin_core AND f.whoop_recovery IS NOT NULL AND f.whoop_strain IS NOT NULL) AS paired_days, count(d.cycle_date) FILTER (WHERE f.has_garmin_core AND f.whoop_recovery IS NOT NULL AND f.whoop_strain IS NOT NULL) AS paired_with_score_row, count(d.recovery_pct) FILTER (WHERE f.has_garmin_core AND f.whoop_recovery IS NOT NULL AND f.whoop_strain IS NOT NULL) AS paired_with_recovery, count(*) AS feature_days, count(d.cycle_date) AS feature_days_with_row FROM day_features f JOIN model_version mv ON mv.athlete_id = f.athlete_id AND mv.status = 'ACTIVE' LEFT JOIN derived_day_metrics d ON d.athlete_id = f.athlete_id AND d.cycle_date = f.cycle_date AND d.model_version = mv.version WHERE f.athlete_id = 1 AND f.feature_version = mv.feature_version GROUP BY mv.version;"
```

**2. Spot check, the last two weeks of data:** our recovery and band next to Whoop's (Whoop's band uses the model's own thresholds).
```bash
docker compose exec db psql -U training -d training -c "SELECT d.cycle_date, d.model_version, d.recovery_pct AS ours, d.recovery_band AS our_band, f.whoop_recovery AS whoop, CASE WHEN f.whoop_recovery IS NULL THEN NULL WHEN f.whoop_recovery >= (mv.coefficients->'bands'->>'green_min')::numeric THEN 'GREEN' WHEN f.whoop_recovery <= (mv.coefficients->'bands'->>'red_max')::numeric THEN 'RED' ELSE 'YELLOW' END AS whoop_band, d.strain AS our_strain, f.whoop_strain, d.data_quality->'recovery_reasons'->0->>'reason' AS why_no_recovery, d.data_quality->>'recovery_lower_confidence' AS lower_conf FROM derived_day_metrics d JOIN model_version mv ON mv.athlete_id = d.athlete_id AND mv.status = 'ACTIVE' AND mv.version = d.model_version LEFT JOIN day_features f ON f.athlete_id = d.athlete_id AND f.cycle_date = d.cycle_date AND f.feature_version = d.feature_version WHERE d.athlete_id = 1 AND d.cycle_date > (SELECT max(cycle_date) FROM derived_day_metrics x WHERE x.athlete_id = 1 AND x.model_version = mv.version) - 14 ORDER BY d.cycle_date;"
```

**3. Why recovery is blank,** by reason:
```bash
docker compose exec db psql -U training -d training -c "SELECT r->>'reason' AS reason, r->>'detail' AS detail, count(*) AS days FROM derived_day_metrics d JOIN model_version mv ON mv.athlete_id = d.athlete_id AND mv.status = 'ACTIVE' AND mv.version = d.model_version CROSS JOIN LATERAL jsonb_array_elements(d.data_quality->'recovery_reasons') r WHERE d.athlete_id = 1 AND d.recovery_pct IS NULL GROUP BY 1, 2 ORDER BY 3 DESC;"
```
