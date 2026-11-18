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
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
