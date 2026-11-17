import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { Trends, TrendsDay } from '../api/types';
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

function renderPage(body: Trends) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      expect(url).toBe('/api/athletes/1/trends');
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
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
});
