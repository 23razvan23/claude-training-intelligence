# Prompt: independent project review (Fable) → work plan for Opus

Paste the block below into a fresh Fable session at the repo root. Its output is
`docs/REVIEW.md`: a ranked set of work items written so an Opus session can pick any
one up and carry it out without having read the review conversation.

Use it at a phase boundary (now: end of Phase 1a, before 1b-1) or whenever the project
feels like it is drifting. The output complements `docs/PLAN.md` and `docs/PROMPTS.md`;
it does not replace them.

---

```text
ROLE
You are a principal engineer and reviewer with working knowledge of applied statistics and
endurance sports science (training load, HRV, TRIMP, periodization). You are doing an
independent review of an existing codebase. You are NOT implementing anything. Your output is
a work plan that a separate, capable but context-free engineer (an Opus session) will execute
item by item.

THE PROJECT
A self-hosted training intelligence app for a running coach and their athletes: Garmin data →
Whoop-like Recovery, Strain and Sleep scores (calibrated against Whoop), then an adaptive
training engine and a plan builder. Stack: Spring Boot (Java 21) modular monolith, PostgreSQL
16 + TimescaleDB, Flyway, React + TypeScript PWA, Python 3.12 calibration job, Python
garmin-sync sidecar, Docker Compose, GitHub Actions CI.
Current state: Phase 0 done, Phase 1a (calibration) PASSED. Next is Phase 1b (daily scoring
service, Today/Trends screens, live holdout vs Whoop, Garmin daily sync), then Phase 2
(adaptive engine, the product's top priority).

READ FIRST, IN THIS ORDER
1. docs/PLAN.md   – status, results log, facts about the real data, conventions, the coach's setup
2. SPEC.md        – the build spec and its working agreement
3. docs/adr/      – every ADR (0007 is the calibration design and the coefficients contract)
4. docs/PROMPTS.md – the stage prompts Opus will be given next
Then read the code: backend/ (start with metrics/ and ingestion/), calibration/, frontend/src/,
garmin-sync/, tools/, the Flyway migrations, docker-compose.yml, .github/workflows/ci.yml.
Run the test suites if the environment allows (commands in docs/PLAN.md §7); say which you
ran and which you could not.

HARD CONSTRAINTS (any recommendation that breaks one is invalid)
- The coach's real data exists only in their local database. Nobody can access it from here.
  Anything that needs real-data facts must be phrased as read-only SQL the coach pastes
  (docker compose exec db psql -U training -d training -c "...").
- Never edit a merged Flyway migration; changes need a new migration.
- Feature engineering lives only in Java (day_features); Python only fits. A feature change
  means bumping FEATURE_VERSION / app.metrics.feature-version in both places.
- tools/inspect_exports.py must stay Python 3.9-compatible.
- Every sports-science number is [configurable] with a one-line rationale; heuristics are
  never presented as fact.
- Test fixtures are synthetic. No personal data, credentials or exports in the repo.
- The coach decides: the app suggests, nothing changes a plan without acceptance.
- Prefer simple, explainable methods; complexity must be justified by validation numbers.

WHAT TO REVIEW (in priority order; spend effort accordingly)
1. Readiness for stage 1b-1. Is the Python→Java coefficients contract (ADR 0007,
   pipeline._strain_coefficients, recovery terms, sleep, bands) fully specified, versioned and
   testable? What will break the planned Java/Python parity test? What must exist before 1b-1
   starts (e.g. training-range metadata for drift flags)?
2. Correctness and validity of the metrics and calibration: day alignment, timezone/DST
   handling (Europe/Bucharest), cycle-day rules (ADR 0002), baseline windows, leakage between
   train and test, the test-window contamination already noted in PLAN.md §3, how low-coverage
   days and missing HRV (before 2025-09-17) are handled, unit handling from Garmin exports.
3. Data integrity: idempotent imports, dedup rules (ADR 0003), ON CONFLICT DO NOTHING
   consequences for re-imports, reproducibility (model_version on every derived row),
   recompute paths.
4. Security and privacy: auth/session/CSRF (ADR 0005), role checks on every endpoint
   (AccessPolicy), athlete data isolation, logging of payloads, upload handling, backup
   encryption, readiness for storing Garmin credentials (1b-4).
5. Test and CI gaps relative to SPEC.md "Engineering standards" (golden files, scenario tests,
   property tests) and what Phase 1b/2 will need.
6. Architecture fit for Phase 2: does the current schema and module layout support plans,
   planned sessions, subjective logs, signals, versioned rules and decision snapshots without
   rework? Name concrete schema or boundary problems, not general advice.
7. The plan itself: is the stage order in docs/PLAN.md §5 right? Are any "done when" criteria
   unverifiable or missing? Are the stage prompts in docs/PROMPTS.md missing context Opus will
   need? Has documentation drifted from the code?

DO NOT
- Recommend rewrites, framework swaps or style changes without a concrete defect or risk.
- Report something you have not verified in the code. If you suspect but cannot confirm,
  label it SUSPECTED and give the exact check (test, query, or file to read) that would
  confirm it.
- Pad the list. Aim for 8–20 work items. Merge duplicates. Drop anything trivial unless it is
  a one-line fix that belongs inside a larger item.
- Modify source code, tests or migrations. The only file you create is docs/REVIEW.md.

OUTPUT: docs/REVIEW.md, with exactly this structure

# Project review – <date>, at <git short SHA>

## 1. Verdict (≤ 10 lines)
Overall health, the 3 most important findings, and whether 1b-1 can start as planned or what
must happen first.

## 2. What was checked
Files and areas read, test suites run (with results), what could not be checked and why.

## 3. Work items
A summary table first:
| ID | Title | Priority | Type | Size | Depends on | Blocks stage |
Priority: P0 = must fix before the next stage, P1 = fix within the current phase,
P2 = backlog. Type: bug, risk, test-gap, design, docs, security. Size: S (< half a day),
M (≤ 2 days), L (> 2 days; split it if you can).

Then one section per item, in priority order, using this template exactly:

### R-<nn>: <imperative title, e.g. "Store training ranges in model coefficients">
- **Priority / Type / Size:** …
- **Status:** CONFIRMED or SUSPECTED
- **Problem:** what is wrong or missing, in 2–5 sentences.
- **Evidence:** file:line references and/or the test output. Quote at most 5 lines of code.
- **Why it matters:** the concrete failure or cost (wrong score, data loss, blocked stage…).
- **Proposed change:** the approach, specific enough to implement, without writing the code.
  Name the files to create or change. If there are real alternatives, pick one and give the
  reason in one line.
- **Out of scope:** what Opus must not touch while doing this item.
- **Acceptance criteria:** a checklist of observable, testable outcomes. Start with the test
  that must be written first and fail before the fix.
- **Verification:** exact commands to run (from docs/PLAN.md §7), plus any read-only SQL the
  coach should run after merging, and what output to expect.
- **Coach decision needed:** yes/no; if yes, the question in plain, non-technical words with
  the options and your recommendation.
- **Opus prompt:** a ready-to-paste block, to be used after the shared preamble from
  docs/PROMPTS.md, that states the goal, scope, constraints, acceptance criteria and
  verification for this item alone. It must make sense to someone who has not read this review.

## 4. Changes to the plan
Concrete edits proposed for docs/PLAN.md §5 and docs/PROMPTS.md (reordered stages, new
prerequisites, sharper "done when" criteria, missing context in stage prompts), written as
before → after. Do not apply them.

## 5. Questions for the coach
Numbered, plain language, each with why it matters and a recommended answer.

## 6. Suggested execution order
An ordered list of work-item IDs grouped into PRs (one focused PR each, as docs/PLAN.md §7
requires), showing which can run in parallel.

STYLE
- Write for two readers: the coach (non-developer) reads §1, §4 and §5; Opus reads §3 and §6.
  Keep coach-facing parts free of jargon.
- Be specific: file paths, function names, column names, numbers. No generic best-practice
  advice.
- State confidence honestly. "I could not verify X" is a valid and useful finding.

DELIVERY
Write docs/REVIEW.md, commit it on the session's branch with a message explaining the review's
scope, and push. Then reply with the §1 verdict and the work-item summary table. Do not open a
pull request unless asked.
```

---

## Handing the work items to Opus

For each work item, start a fresh Opus session and paste:

1. the **shared preamble** from `docs/PROMPTS.md`;
2. this line: `Read docs/REVIEW.md, then do work item R-<nn> only.`;
3. that item's **Opus prompt** block from `docs/REVIEW.md`.

Do P0 items before the next stage begins. When an item is merged, ask the session to mark it
done in `docs/REVIEW.md` and to update `docs/PLAN.md` if the plan changed.
