// Today, Trends and Plan screenshots at phone width (390 px), light and dark, on synthetic data only.
// Usage: npm run build && npx vite preview --port 4173 &  then  node scripts/screenshots.cjs <outDir>
// The API is faked in the browser: nothing here talks to a backend or reads real data.
const { chromium } = require('playwright');
const path = require('path');

const out = process.argv[2] || 'screenshots';
const BASE = process.env.BASE_URL || 'http://localhost:4173';
const TODAY = '2026-11-16';

const day = (over = {}) => ({
  date: TODAY,
  recoveryPct: 72,
  band: 'GREEN',
  recoveryLowerConfidence: false,
  recoveryReasons: [],
  strain: 4.2,
  strainLowCoverage: false,
  strainReason: null,
  strainTarget: { low: 12.1, high: 15.4, band: 'GREEN', basisDays: 26, windowDays: 28, lowPercentile: 50, highPercentile: 75 },
  sleepPerformancePct: 94,
  sleepNeedMin: 455,
  sleepReason: null,
  contributions: [
    { feature: 'ln_rmssd_z', value: 0.6, points: 6.2, measured: 54, baseline: 50, percentVsBaseline: 8, typical: 0.1 },
    { feature: 'prev_strain', value: 13.6, points: -2.8, typical: 9.3 },
    { feature: 'rhr_dev', value: -2.4, points: 1.6, typical: -0.2 },
    { feature: 'sleep_perf', value: 94, points: 1.1, typical: 90 },
    { feature: 'resp_dev', value: 0.1, points: -0.3, typical: 0 },
  ],
  needsRecompute: false,
  drift: false,
  profileChanged: false,
  ...over,
});

// Synthetic Trends data: 14 months, a week with the watch off, HRV only from 2025-09-17, a few low-coverage days.
function trendsBody() {
  const DAY = 86400000;
  const iso = (n) => new Date(n * DAY).toISOString().slice(0, 10);
  const num = (s) => Math.round(Date.parse(s + 'T00:00:00Z') / DAY);
  const to = num(TODAY);
  const from = to - 455;
  const days = [];
  for (let n = from; n <= to; n++) {
    const date = iso(n);
    const i = n - from;
    const off = date >= '2026-10-20' && date <= '2026-10-26'; // watch off for a week: real gaps
    if (off) continue;
    const band = ['GREEN', 'YELLOW', 'RED'][(i * 7) % 11 < 5 ? 0 : (i * 7) % 11 < 9 ? 1 : 2];
    const week = Math.floor(i / 7) % 4; // a 4-week build with an easier week
    days.push({
      date,
      rmssd: date >= '2025-09-17' ? Math.round(48 + 8 * Math.sin(i / 6) + (i % 5)) : null,
      rhr: 47 + Math.round(2 * Math.sin(i / 9)) + (i % 3 === 0 ? 1 : 0),
      sleepMinutes: 400 + ((i * 13) % 70),
      dayLoadEdwards: i % 7 === 6 ? 0 : [90, 120, 150, 70][week] + ((i * 17) % 40),
      recoveryPct: 35 + ((i * 7) % 60),
      band,
      strain: Math.round((6 + ((i * 5) % 9) + week) * 10) / 10,
      strainLowCoverage: i % 23 === 0,
      sleepPerformancePct: 80 + ((i * 3) % 20),
      sleepNeedMin: 450,
    });
  }
  return { modelVersion: 'm-synthetic', featureVersion: 'f3', from: iso(from), to: TODAY, firstHrvDate: '2025-09-17', historyDays: 365, days };
}

const scenarios = {
  fresh: { today: TODAY, timezone: 'Europe/Bucharest', state: 'FRESH', modelVersion: 'm-synthetic', lastDataDate: TODAY, day: day() },
  stale: {
    today: TODAY,
    timezone: 'Europe/Bucharest',
    state: 'STALE',
    modelVersion: 'm-synthetic',
    lastDataDate: '2026-11-08',
    lastScored: day({
      date: '2026-11-08',
      recoveryPct: 48,
      band: 'YELLOW',
      strain: 11.8,
      strainLowCoverage: true,
      strainTarget: { low: 8.4, high: 12.1, band: 'YELLOW', basisDays: 25, windowDays: 28, lowPercentile: 25, highPercentile: 50 },
    }),
  },
  missing: {
    today: TODAY,
    timezone: 'Europe/Bucharest',
    state: 'MISSING',
    modelVersion: 'm-synthetic',
    lastDataDate: TODAY,
    day: day({
      recoveryPct: null,
      band: null,
      recoveryReasons: [{ reason: 'NO_STRAIN_YESTERDAY', detail: 'NO_ALL_DAY_HR' }],
      strainTarget: null,
      contributions: [],
    }),
  },
};

// Synthetic engine signals (stage 2-2, ADR 0021) for the last 365 days of the trends window.
function signalsBody() {
  const DAY = 86400000;
  const iso = (n) => new Date(n * DAY).toISOString().slice(0, 10);
  const to = Math.round(Date.parse(TODAY + 'T00:00:00Z') / DAY);
  const rows = [];
  for (let n = to - 364; n <= to; n++) {
    const date = iso(n);
    if (date >= '2026-10-20' && date <= '2026-10-26') continue;
    const i = n - (to - 364);
    const km7 = Math.round((38 + 14 * Math.sin(i / 20) + (i % 5)) * 10) / 10;
    const tol = Math.round((44 + 6 * Math.sin(i / 60)) * 10) / 10;
    rows.push({
      date,
      signalsVersion: 's2',
      settingsVersion: 2,
      computedAt: `${date}T05:05:00Z`,
      signals: {
        load: { method: 'edwards', daily: 120, acute: 112.4, chronic: 101.9, acwr: 1.103, running: { daily: 95, acute: 88.1, chronic: 70.2, acwr: 1.255 } },
        foster: { windowDays: 7, knownDays: 7, weekLoad: 742, mean: 106, sd: 61.2, monotony: 1.732, strain: 1285 },
        running: { shortWindowDays: 7, shortKm: km7, shortMinutes: km7 * 5.6, longWindowDays: 21, longKm: km7 * 3, longMinutes: km7 * 17, longWeeklyAvgKm: km7 },
        readiness: { recoveryPct: 54, band: 'YELLOW', trendShort: -7.3, trendShortDays: 3, trendLong: -11, trendLongDays: 7, bandStreakDays: 2, nonGreenStreakDays: 2, beforeHrv: false },
        sleep: { windowDays: 7, knownNights: 7, debtMin: 312, nightsOfNeed: 0.69 },
        subjective: { checkins: 1, illness: false, injury: false, illnessDaysLast7: 0, injuryDaysLast7: 0, niggles: [{ location: 'calf', since: '2026-11-14', persistDays: 3, severity: 2 }], flagged: true },
        tolerance: { kmPerWeek: tol, source: 'computed', computedKmPerWeek: tol, peakKmPerWeek: 61.3, peakFrom: '2026-04-13', peakTo: '2026-05-10', cleanWindows: 330, flaggedWindows: 4, interruptedWeeks: 2, decayWeeks: 1, decayFactor: 0.9, decayedKmPerWeek: 55.2, chronicKmPerWeek: tol, capKmPerWeek: tol, capped: true },
      },
    });
  }
  return rows;
}

const toleranceBody = (override) => {
  const last = signalsBody().slice(-1)[0];
  const t = { ...last.signals.tolerance };
  if (override) Object.assign(t, { kmPerWeek: 50, source: 'override', override: { kmPerWeek: 50, note: 'marathon build, no niggles', since: '2026-11-16' } });
  return {
    date: last.date,
    tolerance: t,
    running: last.signals.running,
    overrides: override ? [{ id: 1, kmPerWeek: 50, note: 'marathon build, no niggles', createdBy: 'coach', createdAt: '2026-11-16T08:00:00Z' }] : [],
  };
};

// Garmin sync states on Today (ADR 0015 D9): the same stale day, three different reasons.
const synced = (over) => ({
  connection: 'CONNECTED',
  lastSuccessAt: '2026-11-15T05:01:00Z',
  lastSuccessThrough: '2026-11-15',
  latestStatus: 'SUCCEEDED',
  latestAt: '2026-11-15T05:01:00Z',
  morningEnabled: true,
  morningTime: '07:00:00',
  ...over,
});
const syncStatus = {
  fresh: synced({ lastSuccessAt: `${TODAY}T05:01:00Z`, latestAt: `${TODAY}T05:01:00Z` }),
  'sync-failed': synced({ latestStatus: 'FAILED', latestErrorCode: 'GARMIN_UNAVAILABLE', latestAt: `${TODAY}T05:41:00Z` }),
  'sync-reconnect': synced({ connection: 'RECONNECT_NEEDED', latestStatus: 'FAILED', latestErrorCode: 'AUTH_REJECTED' }),
  'sync-nodata': synced({ lastSuccessAt: `${TODAY}T05:01:00Z`, latestAt: `${TODAY}T05:01:00Z` }),
};
scenarios['sync-failed'] = scenarios.stale;
scenarios['sync-reconnect'] = scenarios.stale;
scenarios['sync-nodata'] = scenarios.stale;


// Synthetic Plan (stage 2-1a): this week and next, a skipped session, an empty later week.
const PLAN_NOW = '2026-11-18T08:00:00Z';
const planBody = [{ id: 7, athleteId: 1, name: 'Autumn block', origin: 'MANUAL', status: 'ACTIVE', startDate: '2026-11-16', endDate: '2026-12-13' }];
const sess = (id, date, sessionType, demand, over = {}) => ({ id, athleteId: 1, planId: 7, date, sessionType, demand, title: '', status: 'PLANNED', ...over });
const sessionsBody = [
  sess(1, '2026-11-16', 'rest', 'EASY', { status: 'DONE' }),
  sess(2, '2026-11-17', 'easy', 'EASY', { targetDistanceKm: 10, targetDurationMin: 55, status: 'DONE', description: 'Z2, relaxed' }),
  sess(3, '2026-11-18', 'vo2max', 'KEY', { title: 'Track 5 × 1 km', targetDistanceKm: 12, targetDurationMin: 65, description: '3 km warm-up, 5 × 1 km @ 3:35 /km with 2 min jog, 2 km cool-down' }),
  sess(4, '2026-11-19', 'recovery', 'EASY', { targetDistanceKm: 6, description: 'Very easy, HR under Z2' }),
  sess(5, '2026-11-20', 'strides', 'EASY', { title: 'Easy + strides', targetDistanceKm: 8, description: '6 × 20 s strides at the end' }),
  sess(6, '2026-11-22', 'long', 'KEY', { targetDistanceKm: 24, targetDurationMin: 125, description: 'Last 5 km at marathon effort', notes: 'Take a gel at 60 min' }),
  sess(7, '2026-11-24', 'easy', 'EASY', { targetDistanceKm: 10 }),
  sess(8, '2026-11-25', 'threshold', 'KEY', { title: 'Tempo', targetDistanceKm: 13, description: '3 km WU, 2 × 15 min @ T, 2 km CD' }),
  sess(9, '2026-11-26', 'hills', 'SUPPORTING', { targetDistanceKm: 10, status: 'SKIPPED' }),
  sess(10, '2026-11-29', 'long', 'KEY', { targetDistanceKm: 26 }),
];
const planTypes = ['easy', 'recovery', 'long', 'long_fast_finish', 'threshold', 'vo2max', 'race_pace', 'hills', 'strides', 'race', 'cross', 'rest'].map((code) => ({
  code,
  defaultDemand: ['long', 'long_fast_finish', 'threshold', 'vo2max', 'race_pace', 'race'].includes(code) ? 'KEY' : code === 'hills' ? 'SUPPORTING' : 'EASY',
}));

(async () => {
  const browser = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    for (const [name, body] of Object.entries(scenarios)) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: scheme });
      const page = await ctx.newPage();
      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url()).pathname;
        const json = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
        if (url === '/api/auth/me') return json({ username: 'coach', role: 'COACH', athleteId: 1 });
        if (url === '/api/athletes') return json([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest' }]);
        if (url === '/api/athletes/1/today') return json(body);
        if (url === '/api/athletes/1/garmin/sync/status' && syncStatus[name]) return json(syncStatus[name]);
        if (url === '/api/athletes/1/trends') return json(trendsBody());
        return route.fulfill({ status: 404, body: '' });
      });
      await page.goto(`${BASE}/today`);
      await page.waitForSelector('section.today');
      await page.waitForTimeout(150);
      const width = await page.evaluate(() => document.documentElement.scrollWidth);
      if (width > 390) throw new Error(`${name}/${scheme}: horizontal scroll (${width}px wide)`);
      await page.screenshot({ path: path.join(out, `today-${name}-${scheme}.png`), fullPage: true });
      console.log(`today-${name}-${scheme}.png (scrollWidth ${width})`);
      await ctx.close();
    }
  }
  for (const scheme of ['light', 'dark']) {
    for (const range of ['3 months', '12 months']) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: scheme });
      const page = await ctx.newPage();
      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url()).pathname;
        const json = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
        if (url === '/api/auth/me') return json({ username: 'coach', role: 'COACH', athleteId: 1 });
        if (url === '/api/athletes') return json([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest' }]);
        if (url === '/api/athletes/1/trends') return json(trendsBody());
        if (url === '/api/athletes/1/signals') return json(signalsBody());
        return route.fulfill({ status: 404, body: '' });
      });
      await page.goto(`${BASE}/trends`);
      await page.waitForSelector('section.trends svg');
      await page.getByRole('button', { name: range }).click();
      await page.waitForTimeout(150);
      const width = await page.evaluate(() => document.documentElement.scrollWidth);
      const name = `trends-${range.replace(' ', '')}-${scheme}.png`;
      if (width > 390) throw new Error(`${name}: horizontal scroll (${width}px wide)`);
      await page.screenshot({ path: path.join(out, name), fullPage: true });
      console.log(`${name} (scrollWidth ${width})`);
      await ctx.close();
    }
  }
  for (const scheme of ['light', 'dark']) {
    for (const view of ['coach', 'edit', 'athlete']) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: scheme });
      const page = await ctx.newPage();
      await page.clock.setFixedTime(new Date(PLAN_NOW));
      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url()).pathname;
        const json = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
        const role = view === 'athlete' ? 'ATHLETE' : 'COACH';
        if (url === '/api/auth/me') return json({ username: role.toLowerCase(), role, athleteId: 1 });
        if (url === '/api/athletes') return json([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest' }]);
        if (url === '/api/athletes/1/plans') return json(planBody);
        if (url === '/api/athletes/1/sessions') return json(sessionsBody);
        if (url === '/api/plan/session-types') return json(planTypes);
        return route.fulfill({ status: 404, body: '' });
      });
      await page.goto(`${BASE}/plan`);
      await page.waitForSelector('section.plan .session');
      if (view === 'edit') await page.getByRole('button', { name: 'Edit', exact: true }).nth(2).click();
      await page.waitForTimeout(150);
      const width = await page.evaluate(() => document.documentElement.scrollWidth);
      const name = `plan-${view}-${scheme}.png`;
      if (width > 390) throw new Error(`${name}: horizontal scroll (${width}px wide)`);
      await page.screenshot({ path: path.join(out, name), fullPage: true });
      console.log(`${name} (scrollWidth ${width})`);
      await ctx.close();
    }
  }
  // Check-in (stage 2-1b): the empty form, a filled form with a niggle, and the coach's view with everyone's list.
  const checkinOptions = (current) => ({
    today: TODAY, date: TODAY, timezone: 'Europe/Bucharest',
    locations: ['foot', 'ankle', 'calf', 'shin', 'knee', 'hamstring', 'quad', 'hip', 'glute', 'back', 'other'],
    maxBackdateDays: 7,
    activities: [{ id: 7, sport: 'running', startAt: `${TODAY}T05:30:00Z`, durationS: 3600, distanceM: 10200 }],
    sessions: [{ id: 3, title: 'Tempo 3 x 10 min', sessionType: 'threshold', demand: 'KEY', status: 'PLANNED' }],
    current,
  });
  const ck = (over) => ({
    id: 1, athleteId: 1, athleteName: 'Synthetic Runner', date: TODAY, scope: 'day', plannedSessionId: null, activityId: null,
    rpe: null, soreness: null, niggleLocation: null, niggleSeverity: null, niggleResolved: false, injury: false, illness: false,
    note: null, source: 'MANUAL', onBehalf: false, createdAt: `${TODAY}T06:10:00Z`, ...over,
  });
  const recent = [
    ck({ id: 4, rpe: 7, soreness: 3, niggleLocation: 'calf', niggleSeverity: 2, note: 'Left calf tight after the hills' }),
    ck({ id: 3, date: '2026-11-15', scope: 'activity', activityId: 6, rpe: 8 }),
    ck({ id: 2, date: '2026-11-14', soreness: 2, niggleLocation: 'calf', niggleResolved: true, onBehalf: true }),
    ck({ id: 1, date: '2026-11-13', illness: true, note: 'Sore throat', source: 'GARMIN_SEED' }),
  ];
  const everyone = [...recent.slice(0, 2), ck({ id: 9, athleteId: 2, athleteName: 'Second Athlete', rpe: 5, soreness: 1 })];
  for (const scheme of ['light', 'dark']) {
    for (const name of ['checkin-empty', 'checkin-filled', 'checkin-coach']) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: scheme });
      const page = await ctx.newPage();
      const coach = name === 'checkin-coach';
      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url()).pathname;
        const json = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
        if (url === '/api/auth/me') return json({ id: 1, username: coach ? 'coach' : 'runner', role: coach ? 'COACH' : 'ATHLETE', athleteId: coach ? undefined : 1 });
        if (url === '/api/athletes') return json([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest' }]);
        if (url === '/api/athletes/1/checkins/options') return json(checkinOptions(name === 'checkin-empty' ? [] : [ck({ id: 4, rpe: 7, soreness: 3, niggleLocation: 'calf', niggleSeverity: 2 })]));
        if (url === '/api/athletes/1/checkins/recent') return json(name === 'checkin-empty' ? recent.slice(1) : recent);
        if (url === '/api/checkins/recent') return json(everyone);
        return route.fulfill({ status: 404, body: '' });
      });
      await page.goto(`${BASE}/checkin`);
      await page.waitForSelector('[data-testid="checkin-page"]');
      await page.waitForTimeout(250);
      const width = await page.evaluate(() => document.documentElement.scrollWidth);
      const file = `${name}-${scheme}.png`;
      if (width > 390) throw new Error(`${file}: horizontal scroll (${width}px wide)`);
      await page.screenshot({ path: path.join(out, file), fullPage: true });
      console.log(`${file} (scrollWidth ${width})`);
      await ctx.close();
    }
  }
  // Athletes: running tolerance with its derivation and the override (stage 2-2, ADR 0021 D11).
  for (const scheme of ['light', 'dark']) {
    for (const override of [false, true]) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: scheme });
      const page = await ctx.newPage();
      await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url()).pathname;
        const json = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
        if (url === '/api/auth/me') return json({ username: 'coach', role: 'COACH' });
        if (url === '/api/athletes') return json([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest', hrMax: 184 }]);
        if (url === '/api/athletes/1/tolerance') return json(toleranceBody(override));
        if (url === '/api/users') return json([]);
        return route.fulfill({ status: 404, body: '' });
      });
      await page.goto(`${BASE}/athletes`);
      await page.waitForSelector('[data-testid="tolerance-card"]');
      if (override) await page.getByText('Override history').click();
      const width = await page.evaluate(() => document.documentElement.scrollWidth);
      const file = `tolerance-${override ? 'override' : 'computed'}-${scheme}.png`;
      if (width > 390) throw new Error(`${file}: horizontal scroll (${width}px wide)`);
      await page.screenshot({ path: path.join(out, file), fullPage: true });
      console.log(`${file} (scrollWidth ${width})`);
      await ctx.close();
    }
  }
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
