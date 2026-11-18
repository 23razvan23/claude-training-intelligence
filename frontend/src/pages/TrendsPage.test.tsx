import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { StoredSignals, Trends, TrendsDay } from '../api/types';
import { dateGrid } from '../trends';
import { TrendsPage } from './TrendsPage';

// Trends on synthetic data, through the real page and fetch: range switching, gaps as gaps,
// lower-confidence strain, the HRV start note and the load lines.

vi.mock('../athleteContext', () => ({ useAthleteSelection: () => ({ athleteId: 1, setAthleteId: () => {} }) }));

const TO = '2026-11-16';
const GAP = '2026-11-10'; // one day with no HRV and no strain row at all
const LOW_COV = '2026-11-12';
const FIRST_HRV = '2026-10-01'; // inside the 3 and 12 month ranges, before the 4 week range

function makeTrends(opts: { from?: string; firstHrvDate?: string | null; loadDays?: number } = {}): Trends {
  const from = opts.from ?? '2025-08-18';
  const loadFrom = opts.loadDays != null ? dateGrid(from, TO).slice(-opts.loadDays)[0] : from;
  const days: TrendsDay[] = dateGrid(from, TO).map((date, i) => {
    const hasHrv = date >= FIRST_HRV && date !== GAP;
    return {
      date,
      rmssd: hasHrv ? 50 + (i % 7) : null,
      rhr: date === GAP ? null : 48 + (i % 3),
      sleepMinutes: date === GAP ? null : 420,
      dayLoadEdwards: date >= loadFrom ? (i % 7 === 0 ? 0 : 100 + (i % 5) * 10) : null,
      recoveryPct: date === GAP ? null : 40 + (i % 50),
      band: date === GAP ? null : i % 3 === 0 ? 'GREEN' : i % 3 === 1 ? 'YELLOW' : 'RED',
      strain: date === GAP ? null : 8 + (i % 6),
      strainLowCoverage: date === LOW_COV,
      sleepPerformancePct: date === GAP ? null : 90,
      sleepNeedMin: 450,
    };
  });
  return {
    modelVersion: 'm-synthetic',
    featureVersion: 'f3',
    from,
    to: TO,
    firstHrvDate: opts.firstHrvDate === undefined ? FIRST_HRV : opts.firstHrvDate,
    historyDays: 365,
    days,
  };
}

function renderPage(body: Trends, signals: StoredSignals[] = []) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const json = (v: unknown) => new Response(JSON.stringify(v), { status: 200, headers: { 'Content-Type': 'application/json' } });
      if (url.startsWith('/api/athletes/1/signals?')) {
        expect(url).toBe(`/api/athletes/1/signals?from=${dateGrid(body.from, body.to).slice(-365)[0]}&to=${body.to}`);
        return json(signals);
      }
      expect(url).toBe('/api/athletes/1/trends');
      return json(body);
    }),
  );
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <TrendsPage />
    </QueryClientProvider>,
  );
}

const chart = async (id: string) => within(await screen.findByTestId(`chart-${id}`));
const svgOf = (c: ReturnType<typeof within>) => c.getByRole('img');

beforeAll(async () => {
  await i18n.changeLanguage('en');
});

describe('Trends', () => {
  it('switches between 4 weeks, 3 months and 12 months', async () => {
    renderPage(makeTrends());
    const hrv = await chart('hrv');
    expect(svgOf(hrv)).toHaveAttribute('data-days', '91'); // 3 months by default
    expect(screen.getByRole('button', { name: '3 months' })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByRole('button', { name: '4 weeks' }));
    expect(svgOf(await chart('hrv'))).toHaveAttribute('data-days', '28');
    expect(svgOf(await chart('strain'))).toHaveAttribute('data-days', '28');
    expect(screen.getByRole('button', { name: '4 weeks' })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByRole('button', { name: '12 months' }));
    expect(svgOf(await chart('hrv'))).toHaveAttribute('data-days', '365');
  });

  it('draws a missing day as a gap, never as zero or an interpolated value', async () => {
    renderPage(makeTrends());
    const hrv = await chart('hrv');
    const series = hrv.getByRole('img').querySelector('[data-series="hrv"]')!;
    // HRV exists from 2026-10-01 (47 days to 11-16), one of them missing: two runs, 46 known points, none for the gap day.
    expect(series.getAttribute('data-runs')).toBe('2');
    expect(series.getAttribute('data-known')).toBe('46');

    // Walk the readout back from the newest day (2026-11-16) to the gap (2026-11-10): 6 steps.
    const svg = svgOf(hrv);
    for (let i = 0; i < 6; i++) fireEvent.keyDown(svg, { key: 'ArrowLeft' });
    const readout = hrv.getByTestId('readout');
    expect(readout).toHaveTextContent('No data');
    expect(readout).not.toHaveTextContent(/\b0 ms/);
    fireEvent.keyDown(svg, { key: 'ArrowRight' });
    expect(hrv.getByTestId('readout')).toHaveTextContent(/ms/);
  });

  it('says where overnight HRV starts when the range reaches back before it', async () => {
    renderPage(makeTrends());
    await chart('hrv');
    expect(screen.getByTestId('hrv-note')).toHaveTextContent('Overnight HRV exists only from 2026-10-01');
    expect(screen.getByTestId('marker')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '4 weeks' })); // starts 2026-10-20, after HRV began
    expect(screen.queryByTestId('hrv-note')).not.toBeInTheDocument();
  });

  it('marks a low-coverage strain day as lower confidence', async () => {
    renderPage(makeTrends());
    const strain = await chart('strain');
    const hollow = strain.getByRole('img').querySelectorAll('circle[data-hollow="true"]');
    expect(hollow).toHaveLength(1);
    // Newest day is 11-16; the low-coverage day 11-12 is four steps back.
    for (let i = 0; i < 4; i++) fireEvent.keyDown(strain.getByRole('img'), { key: 'ArrowLeft' });
    expect(strain.getByTestId('readout')).toHaveTextContent('lower confidence');
  });

  it('colours recovery by band', async () => {
    renderPage(makeTrends());
    const rec = await chart('recovery');
    const fills = Array.from(rec.getByRole('img').querySelectorAll('circle')).map((c) => (c as SVGElement).style.fill);
    expect(fills).toContain('var(--band-green)');
    expect(fills).toContain('var(--band-yellow)');
    expect(fills).toContain('var(--band-red)');
  });

  it('shows acute and chronic load as two lines and the ratio as one signal', async () => {
    renderPage(makeTrends());
    const load = await chart('acute');
    const svg = svgOf(load);
    expect(svg.querySelector('[data-series="acute"]')).toBeTruthy();
    expect(svg.querySelector('[data-series="chronic"]')).toBeTruthy();
    expect(screen.getByTestId('ratio')).toHaveTextContent(/Acute ÷ chronic: \d\.\d\d/);
    expect(screen.getByTestId('ratio')).toHaveTextContent('One signal, not a verdict');
    expect(screen.queryByTestId('chronic-hidden')).not.toBeInTheDocument();
  });

  it('hides the chronic line until 28 days of load exist', async () => {
    renderPage(makeTrends({ loadDays: 20 }));
    await chart('acute');
    expect(screen.getByTestId('chronic-hidden')).toHaveTextContent('28 days');
    expect(screen.queryByTestId('ratio')).not.toBeInTheDocument();
  });

  it('says so when there are no days at all', async () => {
    renderPage({ ...makeTrends(), days: [], modelVersion: null });
    expect(await screen.findByText('No data')).toBeInTheDocument();
  });

  it('shows the engine signals and running volume against tolerance (stage 2-2)', async () => {
    const row = (date: string, km: number): StoredSignals => ({
      date,
      signalsVersion: 's2',
      settingsVersion: 1,
      computedAt: `${date}T06:00:00Z`,
      signals: {
        load: { method: 'edwards', daily: 120, acute: 110, chronic: 100, acwr: 1.1, running: { acwr: 1.25 } },
        foster: { windowDays: 7, knownDays: 7, weekLoad: 700, monotony: 1.6, strain: 1120 },
        running: { shortWindowDays: 7, shortKm: km, shortMinutes: 250, longWindowDays: 21, longKm: 120, longMinutes: 700, longWeeklyAvgKm: 40 },
        readiness: { recoveryPct: 52, band: 'YELLOW', trendShort: -6.7, trendShortDays: 3, trendLong: -10, trendLongDays: 7, bandStreakDays: 2, nonGreenStreakDays: 2, beforeHrv: false },
        sleep: { windowDays: 7, knownNights: 7, debtMin: 420, nightsOfNeed: 0.88 },
        subjective: { checkins: 1, illness: false, injury: false, illnessDaysLast7: 0, injuryDaysLast7: 0, niggles: [{ location: 'calf', since: '2026-11-14', persistDays: 3 }], flagged: true },
        tolerance: { kmPerWeek: 40, source: 'computed', computedKmPerWeek: 40, peakKmPerWeek: 55, cleanWindows: 300, flaggedWindows: 4, interruptedWeeks: 0, decayWeeks: 0, decayFactor: 1, capKmPerWeek: 40, chronicKmPerWeek: 40, capped: true },
      },
    });
    renderPage(makeTrends(), [row('2026-11-15', 42), row(TO, 44)]);
    const card = within(await screen.findByTestId('signals-card'));
    expect(card.getByTestId('sig-readiness').textContent).toContain('Recovery 52% (Yellow)');
    expect(card.getByTestId('sig-readiness').textContent).toContain('−6.7');
    expect(card.getByTestId('sig-readiness').textContent).toContain('Not green for 2 days');
    expect(card.getByTestId('sig-sleep').textContent).toContain('7.0 h');
    expect(card.getByTestId('sig-acwr').textContent).toContain('1.10 overall, 1.25 running');
    expect(card.getByTestId('sig-volume').textContent).toContain('110% used');
    expect(card.getByTestId('sig-subjective').textContent).toContain('Calf niggle, day 3');
    expect(card.getByText(/first night with overnight HRV/)).toBeInTheDocument();
    expect(svgOf(await chart('km7'))).toBeInTheDocument();
  });

  it('says when there are no signals yet', async () => {
    renderPage(makeTrends(), []);
    expect(await screen.findByText(/No signals yet/)).toBeInTheDocument();
  });
});
