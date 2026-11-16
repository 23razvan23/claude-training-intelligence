import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { Today, TodayDay } from '../api/types';
import { formatDay } from '../today';
import { TodayPage } from './TodayPage';

// The three states of Today (ADR 0008 C1/C2, ADR 0014) on synthetic data, through the real page and fetch.

vi.mock('../athleteContext', () => ({ useAthleteSelection: () => ({ athleteId: 1, setAthleteId: () => {} }) }));

const TODAY = '2026-11-16';

function day(over: Partial<TodayDay> = {}): TodayDay {
  return {
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
      { feature: 'rhr_dev', value: -2.4, points: 1.6, typical: -0.2 },
      { feature: 'prev_strain', value: 13.6, points: -2.8, typical: 9.3 },
    ],
    needsRecompute: false,
    drift: false,
    profileChanged: false,
    ...over,
  };
}

function mockToday(body: Today) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      expect(url).toBe('/api/athletes/1/today');
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <TodayPage />
    </QueryClientProvider>,
  );
}

const fmt = (iso: string) => formatDay(iso, 'en');

beforeAll(async () => {
  await i18n.changeLanguage('en');
});
afterEach(() => vi.unstubAllGlobals());

describe('Today: fresh', () => {
  it("shows today's recovery dial, band, strain vs target, sleep and contributions in plain words", async () => {
    mockToday({ today: TODAY, timezone: 'Europe/Bucharest', state: 'FRESH', modelVersion: 'm-test', lastDataDate: TODAY, day: day() });
    renderPage();

    const dial = await screen.findByTestId('today-recovery');
    expect(dial).toHaveAttribute('data-band', 'GREEN');
    expect(dial).toHaveTextContent('72%');
    expect(screen.getByText('Green')).toBeInTheDocument();
    expect(screen.queryByTestId('stale-banner')).not.toBeInTheDocument();

    expect(screen.getByText('Strain so far')).toBeInTheDocument();
    expect(screen.getByTestId('strain-value')).toHaveTextContent('4.2');
    expect(screen.getByTestId('strain-target')).toHaveTextContent('Target on a green day: 12.1–15.4');

    expect(screen.getByText('94%')).toBeInTheDocument();
    expect(screen.getByText('7 h 35 min')).toBeInTheDocument();

    const hrv = screen.getByText('HRV 54 ms, 8% above your 30-day baseline (50 ms)').closest('li')!;
    expect(within(hrv).getByText('+6')).toBeInTheDocument();
    expect(screen.getByText('Resting HR 2 bpm below your 30-day average')).toBeInTheDocument();
    const prev = screen.getByText("Yesterday's strain 13.6 (your usual 9.3)").closest('li')!;
    expect(within(prev).getByText('−3')).toBeInTheDocument();
  });

  it('labels lower confidence: strain on a low-coverage day, recovery after one', async () => {
    mockToday({
      today: TODAY,
      timezone: 'Europe/Bucharest',
      state: 'FRESH',
      lastDataDate: TODAY,
      day: day({ strainLowCoverage: true, recoveryLowerConfidence: true }),
    });
    renderPage();
    expect(await screen.findByTestId('strain-lowconf')).toHaveTextContent('only part of this day');
    expect(screen.getByTestId('recovery-lowconf')).toHaveTextContent('only part of the day before');
  });

  it('has no low-confidence labels on a full-coverage day', async () => {
    mockToday({ today: TODAY, timezone: 'Europe/Bucharest', state: 'FRESH', lastDataDate: TODAY, day: day() });
    renderPage();
    await screen.findByTestId('today-recovery');
    expect(screen.queryByTestId('strain-lowconf')).not.toBeInTheDocument();
    expect(screen.queryByTestId('recovery-lowconf')).not.toBeInTheDocument();
  });
});

describe('Today: stale', () => {
  it('says "last data from <date>" and shows the last scored day only under its own date', async () => {
    const yesterday = '2026-11-15';
    mockToday({
      today: TODAY,
      timezone: 'Europe/Bucharest',
      state: 'STALE',
      lastDataDate: yesterday,
      lastScored: day({ date: yesterday, recoveryPct: 81 }),
    });
    renderPage();

    const banner = await screen.findByTestId('stale-banner');
    expect(banner).toHaveTextContent('No data for today yet');
    expect(banner).toHaveTextContent(`Last data from ${fmt(yesterday)}`);

    const past = screen.getByTestId('last-scored');
    expect(past).toHaveTextContent(`Last scored day: ${fmt(yesterday)}`);
    expect(past).toHaveTextContent("This is not today's recovery.");
    expect(within(past).getByTestId('past-recovery')).toHaveTextContent(`Recovery on ${fmt(yesterday)}`);
    expect(within(past).getByText('Strain')).toBeInTheDocument();
    expect(within(past).queryByText('Strain so far')).not.toBeInTheDocument();
  });
});

describe('never yesterday as today', () => {
  it('shows no today dial when the newest score is from an earlier day', async () => {
    const yesterday = '2026-11-15';
    mockToday({
      today: TODAY,
      timezone: 'Europe/Bucharest',
      state: 'STALE',
      lastDataDate: yesterday,
      lastScored: day({ date: yesterday, recoveryPct: 81 }),
    });
    renderPage();
    await screen.findByTestId('stale-banner');
    expect(screen.queryByTestId('today-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('today-recovery')).not.toBeInTheDocument();
    expect(screen.queryByText('Recovery')).not.toBeInTheDocument();
  });

  it("ignores a 'today' card whose date is not today, even if the server sent one", async () => {
    // Defence in depth: the server only ever sends today's row as `day`, but the page checks the date too.
    mockToday({
      today: TODAY,
      timezone: 'Europe/Bucharest',
      state: 'FRESH',
      lastDataDate: '2026-11-15',
      day: day({ date: '2026-11-15' }),
    });
    renderPage();
    await screen.findByRole('heading', { name: 'Today' });
    expect(screen.queryByTestId('today-recovery')).not.toBeInTheDocument();
    expect(screen.queryByTestId('today-card')).not.toBeInTheDocument();
  });
});

describe('Today: missing with a reason', () => {
  it('says recovery is not available today and gives the stored reason in plain words', async () => {
    mockToday({
      today: TODAY,
      timezone: 'Europe/Bucharest',
      state: 'MISSING',
      lastDataDate: TODAY,
      day: day({
        recoveryPct: null,
        band: null,
        recoveryReasons: [{ reason: 'NO_STRAIN_YESTERDAY', detail: 'NO_ALL_DAY_HR' }],
        strainTarget: null,
        contributions: [],
      }),
    });
    renderPage();

    const missing = await screen.findByTestId('recovery-missing');
    expect(missing).toHaveTextContent('Recovery not available today');
    expect(missing).toHaveTextContent("Yesterday's strain is unknown: the watch recorded no all-day heart rate");
    expect(screen.queryByTestId('today-recovery')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stale-banner')).not.toBeInTheDocument();
    // Strain and sleep are still shown.
    expect(screen.getByTestId('strain-value')).toHaveTextContent('4.2');
    expect(screen.getByText('No target without a recovery band.')).toBeInTheDocument();
  });
});

describe('Today: no model, no data', () => {
  it('explains what to do', async () => {
    mockToday({ today: TODAY, timezone: 'Europe/Bucharest', state: 'NO_DATA', modelVersion: 'm-test' });
    renderPage();
    expect(await screen.findByText(/No daily scores yet/)).toBeInTheDocument();
  });
});
