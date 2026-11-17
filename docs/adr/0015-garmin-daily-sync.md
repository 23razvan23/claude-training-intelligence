# ADR 0015: Garmin daily sync: library, sign-in flow, what is fetched, the morning run

- Status: accepted (stage 1b-4, 2026-11-16; the coach agreed to the plan and to questions 1–3 the same day). Built in three PRs: **PR 1** (sign-in: Connect, MFA, Disconnect), **PR 2** (fetch and import, Sync now), **PR 3** (morning run, last-sync indicator, Today banner). Sections marked *(PR 3)* describe the agreed design, implemented in that PR; PR 1 and PR 2 are implemented.
- Implements: ADR 0011 (credentials, unchanged except the amendment at its end), ADR 0010 (a re-fetched day is a revision).
- Does not change: `day_features`, `FEATURE_VERSION`, or how scores are computed (ADR 0008, frozen while the live holdout runs, ADR 0009 H8).

## In plain words
The app signs in to Garmin once with your password, keeps only an encrypted token, and then fetches each morning's data itself. That data goes into the same tables as your Garmin exports, through the same code, so scores and the Whoop comparison cannot tell a synced day from an exported one. If a sync fails, or Garmin wants you to sign in again, the screen says so, and exports keep working as before.

## Decisions

### D1. Library: `garminconnect` 0.3.20 alone; `garth` is no longer used (coach, question 1)
- `garth`, named in ADR 0011, announced in 2026 that it is deprecated and no longer maintained ("Garmin changed their auth"). `garminconnect` 0.3.x dropped it and signs in itself (Garmin's mobile sign-in, then access + refresh tokens that renew automatically).
- The sidecar pins `garminconnect==0.3.20` and every dependency (`garmin-sync/requirements.txt`). Updates are deliberate: one version at a time, with the tests. `tests/test_library_contract.py` fails if an update changes a part the sidecar relies on (the version, the MFA return, in-memory tokens, exception names).
- **No token file, ever.** The library normally saves tokens to `~/.garminconnect`. The sidecar never gives it a path: it reads tokens from the request body (`login(tokenstore=<JSON text>)`) and returns them with `client.dumps()`; `GARMINTOKENS` is removed from the environment at startup. A test checks that no token path is set. The throwaway folder proposed in the plan turned out to be unnecessary.

### D2. Sidecar endpoints (PR 1)
All except `GET /health` need `X-Sync-Secret` (ADR 0011 D4): wrong → 401 `BAD_SECRET`; secret unset → 503 `NOT_CONFIGURED`. Bodies are JSON, at most 64 KB.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /auth/login` | `username`, `password` | `{status: CONNECTED, tokens}` or `{status: MFA_REQUIRED, mfaId}` |
| `POST /auth/mfa` | `mfaId`, `code` | `{status: CONNECTED, tokens}` |
| `POST /sync/days` | `tokens`, `dates` (1–31 [configurable]) | `{tokens, days: [{date, summary, sleep, hrv, wellnessFit}], activities: [{summary, fit}], missing}` |

Errors are fixed codes only, never the library's message or a Garmin response: `AUTH_FAILED` (401), `MFA_INVALID` (401), `MFA_EXPIRED` (410), `RATE_LIMITED` (429), `GARMIN_UNAVAILABLE` (502), `BAD_REQUEST` (400), `INTERNAL` (500). The library's own loggers are silenced (they can quote Garmin responses).

### D3. MFA retries (amends ADR 0011 D5)
ADR 0011 D5 deleted the waiting login on any failure. With the current library a mistyped code leaves Garmin's half-finished login usable, so the sidecar allows **3 tries** [configurable: `MFA_MAX_ATTEMPTS`; a mistyped code shouldn't force retyping the password, and more tries invite guessing and could get the account locked] within the **5 minutes** [configurable: `MFA_TTL_SECONDS`, ADR 0011 D5]. After the third try, on any other error, on success or on expiry, the waiting login is deleted. At most 10 logins wait at once (oldest dropped). The password and username are cleared from the waiting login before it is kept.

### D4. App endpoints and states (PR 1)
Coach only (`@PreAuthorize`, then AccessPolicy):
- `GET /api/athletes/{id}/garmin` → `state` (`NOT_CONFIGURED`, `NOT_CONNECTED`, `CONNECTED`, `RECONNECT_NEEDED`), `stored`, `connectedAt`, `tokenRefreshedAt`, `lastError` (a fixed code). A row locked with another key reads as `RECONNECT_NEEDED` / `KEY_CHANGED` (ADR 0011 D7).
- `POST …/garmin/connect` {username, password} and `POST …/garmin/mfa` {mfaId, code} → `{status, mfaId?}`. Errors carry a `code` property (`AUTH_FAILED`, `MFA_INVALID`, `MFA_EXPIRED`, `RATE_LIMITED`, `GARMIN_UNAVAILABLE`, `SIDECAR_UNREACHABLE`, `SECRET_MISMATCH`, `NOT_CONFIGURED`). A wrong Garmin password is **400**, not 401, so the browser does not mistake it for the end of the coach's own session.
- `DELETE …/garmin` → 204; works even while sync is not configured.
- A body that is not valid JSON is answered "Invalid request body" by the app's own handler, so Spring never logs the parser's message, which can quote part of the body (ADR 0011 D9).
- The Connect form is a card on the **Imports** page. It warns when the page is not opened on the Mac itself (ADR 0011 D1 network caveat).

### D5. What is fetched, and how it is stored
For each day: daily summary, sleep, HRV, the activity list with each activity's **original FIT file**, and the day's **all-day heart-rate (monitoring) FIT files**, the same files the Garmin export contains, not Garmin's coarser heart-rate summary. The app stores them as an import of kind `GARMIN_SYNC` through the same `RawWriter` as the export: same tables, same duplicate checks, same revision rule (ADR 0010), one import at a time per athlete. FIT files go through the existing FIT parsers, so heart rate and activities are identical by construction. Summary, sleep and HRV arrive with different field names than the export; a mapping turns them into the same raw rows, with a test that runs one synthetic day through both paths and requires identical rows. Verification on the coach's data: re-syncing days already imported from an export must add no revision rows (D10).

Implementation (PR 2):
- One request per sync: the sidecar signs in once with the stored token, then fetches each day's summary (`get_user_summary`), sleep (`get_sleep_data`, only `dailySleepDTO`), HRV (`get_hrv_data`, only `hrvSummary`) and monitoring FIT ZIP (`/download-service/files/wellness/{date}`), then the activity list for the range and each activity's original FIT ZIP. Files travel base64-encoded. A part Garmin has no data for (404) is left out and listed in `missing`; any other failure stops the whole fetch, so a half-fetched morning is never imported.
- Java: `GarminApiShapes` turns the live records into export records (only adding export names that are missing, so a live field that already has its export name passes through); `GarminExportImporter.importSync` writes them with the export's own handlers, in the export's order. The import is an `import_batch` of kind `GARMIN_SYNC`, in one transaction (ADR 0006).
- **Assumption (flagged):** the live summary's `restingHeartRate` is the day's own value, i.e. the export's `currentDayRestingHeartRate` (the export's `restingHeartRate` is smoothed). The other live names come from garminconnect's typed models. Both are checked on the coach's data with D10 before any synced day is kept.
- The live HRV status uses another vocabulary (BALANCED, LOW) than the export's health status (IN_RANGE); no score reads it, so the sync leaves it empty. The first sync of a day already imported from an export therefore stores one revision of its HRV record that differs only in that empty status; the merged view keeps the export's status (newest non-empty value), so nothing a score reads changes.
- Test: `GarminSyncIT.syncedDaysLandExactlyLikeExportedDays` imports three synthetic days through the export path for one athlete and through the sync path for another, and requires identical rows in every column the features read (and the rest, the HRV status aside).

### D6. Days fetched, retries
- Today plus the **previous 2 days** [configurable: covers a watch that syncs late and Garmin re-processing a night's sleep].
- Garmin unavailable or rate limited: retry after **10 and 30 minutes** [configurable: often enough to be ready by 08:00, rare enough not to get the account blocked], then the run is `FAILED` and the UI says so.
- Garmin rejects the token: no retry; `RECONNECT_NEEDED` (ADR 0011 D6).
- A run history table, `garmin_sync_run` (V20: when, trigger, attempt, result, fixed error code, days, import batch, rows added, whether it recomputed, the parts Garmin had no data for). A partial unique index allows one running or waiting run per athlete; **Sync now** replaces a run that waits to retry. A run left running by a restart is marked `INTERRUPTED`.
- Retries are picked up by a once-a-minute background check (`GarminSyncScheduler`), off for command-line runs. Other failures are final: `AUTH_REJECTED` and `KEY_CHANGED` (credential → RECONNECT_NEEDED), `SECRET_MISMATCH`, `IMPORT_FAILED`, `RECOMPUTE_FAILED` (data imported; press Recompute features), `BAD_RESPONSE`.
- App endpoints (coach only): `POST /api/athletes/{id}/garmin/sync` (202, the run), `GET …/garmin/sync/runs` (latest 10), `POST …/garmin/check` (D10).

### D7. After a sync: the same recompute as the button
After a sync that added rows, the app runs exactly what **Recompute features** runs (all features, then the ACTIVE model's scores). It takes about a minute on the coach's data (coach, 2026-11-16), and using the same code is the safest choice for the holdout. The code is shared (`FeatureRecomputeService`, used by the button too). A sync that added no row does not recompute, so a second sync of the same morning changes no score. Each recompute is still written to `feature_recompute_log`, with the new column `source = 'garmin-sync'` (V20; the coach's runs are `manual`).

### D8. Holdout markers (coach, question 3)
The Holdout tab marks Recompute features runs inside the window (ADR 0013 D6). A morning sync would add one every day, so runs with `source = 'garmin-sync'` are left out of those markers (and of the decision report's count). Only the coach's own runs stay marked. Display only: no score, rule or count changes.

### D9. The morning run *(PR 3)*
At **07:00 in the athlete's timezone** [configurable: an hour of slack before 08:00, the spec's deadline]. If the Mac was asleep at 07:00, the run starts as soon as it is awake again that day. The README gives the one Terminal command that wakes the Mac at 06:55 (coach, question 2). Plus **Sync now** on the Imports page. A "Last sync" line on Today and Imports; the Today banner tells apart "sync failed", "Garmin needs you to connect again" and "no data yet" (updates ADR 0014's wording).

### D10. Check against export (verification before syncing)
`POST …/garmin/check {from, to}` (at most 14 days [configurable], not in the future): fetches the days like a sync, imports them in a transaction that is **always rolled back**, and reports, per table, how many rows the sync would add or finds already stored, plus every stored value it would change: table, day, record, column, whether a score reads that column, and how it differs (different, missing in the live data, only in the live data, record not stored yet). It shows names and counts, never values, and keeps nothing, so scores and the holdout are untouched. On days fully covered by an export, the expected result is: no row added in any table, and no difference in a column the scores use. This is how the translation in D5 is verified on the coach's real data; the coach reports the result before relying on synced days.

### D11. Sleep averages: never the summary's whole numbers (bug found on the coach's data, 2026-11-17)
- **What happened:** the coach pressed Sync now (15–17 Nov), then ran D10 on 7–12 Nov. Everything matched the export exactly (activities, streams, all-day HR, resting HR, HRV, Body Battery, sleep times, stages and score) except three nightly averages on all 6 nights: breathing rate and SpO2 (both recovery inputs) and sleep stress (unused). Read-only SQL showed why: the live `dailySleepDTO` gives them as whole numbers (export 12.4 / 96.8 / 15.29, sync 12.0 / 97.0 / 16.00), while 407 of the 726 exported nights have a decimal breathing rate.
- **Fix:**
  - The sidecar now sends the whole live sleep answer, not only its summary.
  - `GarminApiShapes.sleep` never uses the summary's `averageRespirationValue`, `averageSpO2Value`, `avgSpO2` or `avgSleepStress`.
  - It takes the precise values from fields with the export's own names elsewhere in the answer: a number named `averageRespiration`, an object holding `averageSPO2`/`lowestSPO2` (the export's `spo2SleepSummary` has exactly these), a second `avgSleepStress`.
  - If none is there, the value is **left empty**: the day shows that input as missing (ADR 0008 reasons) instead of a rounded value that would move recovery without saying so. Lowest SpO2 is a whole number on both sides, so the summary's `lowestSpO2Value` is used when no precise object exists.
- **Which field holds the precise value is not documented.** D10 now also searches every number in the live answer (and the mean of every numeric list field) for the one that equals the stored export value, night by night, and lists the best field paths with their match counts (names and counts only). The coach sends that table; the translation is then pinned to the confirmed field in a follow-up change, with a test. Until then, synced nights may lack breathing rate and SpO2, which shows as a missing input, never as a wrong number.
- **First search result (coach, 2026-11-17, nights 7–12 Nov):** lowest SpO2 matched 6/6 (`dailySleepDTO.lowestSpO2Value` and `wellnessSpO2SleepSummaryDTO.lowestSPO2`); breathing rate, average SpO2 and sleep stress matched in **no** field and no whole-night list mean. The live answer is fetched with an hour of awake time before and after the night, so the search now also averages each timed list over the time asleep only, and shows for every candidate how far off it is on average (not only exact matches), to tell a near miss from a different quantity. If no field reproduces the export, which value synced nights use for breathing rate and SpO2 is the coach's decision (they are recovery inputs; the scoring rules stay frozen during the holdout).
- **Cleanup, V21:** deletes every sleep row written by a Garmin sync before this fix (all came from the rounded summary). An exported night (15 and 16 Nov) then counts with its export version again; a night only synced (17 Nov) returns with the next sync. Activities, heart rate and daily summaries from those syncs matched the export and stay. Without this, a corrected sync of an exported night would equal the stored export version and be skipped as a duplicate (ADR 0010 D1), leaving the rounded row the newest. Like V12, it removes rows that should never have been stored. After the update the coach presses Recompute features once.
- **Holdout:** the affected days (15–17 Nov) had no Whoop data imported yet, so no compared day ever used the rounded values. Feature definitions, `FEATURE_VERSION` and scoring are unchanged.

## Consequences
- After PR 1 merges, the coach adds `GARMIN_TOKEN_KEY` and `GARMIN_SYNC_SECRET` to `.env` and connects (README section 8).
- After PR 2 merges, the coach runs Check against export on days covered by the last export, reports the result, and only then uses Sync now.
- After the D11 fix merges: update, press Recompute features, run Check against export again and send its field-search table; Sync now waits until the translation is pinned to the confirmed fields.
- A library update is a deliberate PR that changes `requirements.txt` and passes `test_library_contract.py`.
