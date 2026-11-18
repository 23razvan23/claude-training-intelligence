import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { ToleranceView } from '../api/types';
import { ToleranceCard } from './ToleranceCard';

// The tolerance card on synthetic data: the number, its derivation, setting and clearing the override.

const computed: ToleranceView = {
  date: '2026-11-18',
  running: { shortWindowDays: 7, shortKm: 33, shortMinutes: 180, longWindowDays: 21, longKm: 90, longMinutes: 500, longWeeklyAvgKm: 30 },
  tolerance: {
    kmPerWeek: 30,
    source: 'computed',
    computedKmPerWeek: 30,
    peakKmPerWeek: 52.5,
    peakFrom: '2026-04-06',
    peakTo: '2026-05-03',
    cleanWindows: 300,
    flaggedWindows: 0,
    interruptedWeeks: 2,
    decayWeeks: 1,
    decayFactor: 0.9,
    decayedKmPerWeek: 47.3,
    chronicKmPerWeek: 30,
    capKmPerWeek: 30,
    capped: true,
  },
  overrides: [],
};

const overridden: ToleranceView = {
  ...computed,
  tolerance: { ...computed.tolerance!, kmPerWeek: 40, source: 'override', override: { kmPerWeek: 40, note: 'build block', since: '2026-11-18' } },
  overrides: [{ id: 1, kmPerWeek: 40, note: 'build block', createdBy: 'coach', createdAt: '2026-11-18T10:00:00Z' }],
};

function renderCard(first: ToleranceView) {
  const posts: unknown[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const json = (v: unknown) => new Response(JSON.stringify(v), { status: 200, headers: { 'Content-Type': 'application/json' } });
      if (url === '/api/auth/csrf') return json({ token: 't' });
      if (init?.method === 'POST') {
        expect(url).toBe('/api/athletes/7/tolerance/override');
        const body = JSON.parse(String(init.body));
        posts.push(body);
        return json(body.kmPerWeek == null ? computed : overridden);
      }
      expect(url).toBe('/api/athletes/7/tolerance');
      return json(first);
    }),
  );
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <ToleranceCard athleteId={7} athleteName="Ana" />
    </QueryClientProvider>,
  );
  return posts;
}

beforeAll(async () => {
  await i18n.changeLanguage('en');
});

describe('ToleranceCard', () => {
  it('shows the number and how it was worked out', async () => {
    renderCard(computed);
    expect((await screen.findByTestId('tolerance-value')).textContent).toContain('30.0 km/week');
    const steps = screen.getByTestId('tolerance-steps').textContent ?? '';
    expect(steps).toContain('52.5 km/week (2026-04-06 to 2026-05-03)');
    expect(steps).toContain('take 10% off: 47.3 km/week');
    expect(steps).toContain('Capped by the chronic running volume (30.0 km/week)');
    expect(screen.getByText(/110% of tolerance/)).toBeInTheDocument();
  });

  it('sets an override with a note, and clears it again', async () => {
    const posts = renderCard(computed);
    fireEvent.change(await screen.findByLabelText('Override (km/week)'), { target: { value: '40' } });
    fireEvent.change(screen.getByLabelText('Why (optional)'), { target: { value: 'build block' } });
    fireEvent.click(screen.getByRole('button', { name: 'Set override' }));
    await waitFor(() => expect(screen.getByTestId('tolerance-value').textContent).toContain('40.0 km/week'));
    expect(posts[0]).toEqual({ kmPerWeek: 40, note: 'build block' });
    expect(screen.getByTestId('tolerance-steps').textContent).toContain('Your override of 40.0 km/week counts since 2026-11-18');

    fireEvent.click(screen.getByRole('button', { name: 'Use the computed value' }));
    await waitFor(() => expect(screen.getByTestId('tolerance-value').textContent).toContain('30.0 km/week'));
    expect(posts[1]).toEqual({ kmPerWeek: null });
  });
});
