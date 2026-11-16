// Today screen screenshots at phone width (390 px), light and dark, on synthetic data only.
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
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
