import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { Suggestion, SuggestionsToday } from '../api/types';
import { SuggestionsPanel } from './Suggestions';

// The suggestions card on Today (stage 2-3, ADR 0022) through the real component and a faked fetch. Synthetic data.

const REPLACE: Suggestion = {
  id: 41, athleteId: 1, plannedSessionId: 2, forDate: '2026-11-16', action: 'REPLACE',
  before: { session: { id: 2, athleteId: 1, date: '2026-11-16', sessionType: 'hills', demand: 'SUPPORTING', title: '', targetDistanceKm: 10, targetDurationMin: 60, status: 'PLANNED' } },
  after: { session: { date: '2026-11-16', sessionType: 'easy', demand: 'EASY', title: 'Easy run (instead of hill session)', targetDistanceKm: 6, targetDurationMin: 36, description: 'Engine: easy run' } },
  reasons: [
    { code: 'red.recovery', text: 'Recovery is red today (20%).', params: { recoveryPct: 20 } },
    { code: 'red.easy', text: 'No easy day in the next 3 days to swap with, so it becomes an easy run.' },
  ],
  confidence: 'HIGH', status: 'PENDING', rulesVersion: 'r1', createdAt: '2026-11-16T06:00:00Z',
};

const TODAY: SuggestionsToday = {
  date: '2026-11-16',
  pending: [REPLACE],
  healthMessages: [{ code: 'health.niggle', text: 'The knee niggle has lasted 3 days (since Saturday 14 Nov). If it does not settle, consider having it checked by a physio or doctor.' }],
};

let calls: { url: string; body: unknown }[] = [];

function mockApi(today: SuggestionsToday = TODAY) {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { 'Content-Type': 'application/json' } });
      if (url === '/api/auth/csrf') return new Response(null, { status: 204 });
      if (url === '/api/athletes/1/suggestions/today') return json(today);
      if (url === '/api/plan/session-types') return json([{ code: 'easy', defaultDemand: 'EASY' }, { code: 'hills', defaultDemand: 'SUPPORTING' }]);
      if (init?.method === 'POST') {
        calls.push({ url, body: init.body ? JSON.parse(String(init.body)) : null });
        if (url.endsWith('/run')) return json({ engineRunDate: '2026-11-16', rulesVersion: 'r1', created: 1, kept: 0, expired: 2, pending: [], healthMessages: [], notes: [] });
        return json({ suggestion: { ...REPLACE, status: url.endsWith('reject') ? 'REJECTED' : 'ACCEPTED' }, changed: [] });
      }
      return json({ detail: 'unexpected ' + url }, 500);
    }),
  );
}

function renderPanel(isCoach: boolean) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <SuggestionsPanel athleteId={1} isCoach={isCoach} />
    </QueryClientProvider>,
  );
}

describe('Suggestions on Today', () => {
  beforeAll(async () => {
    await i18n.changeLanguage('en');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('shows the suggestion with before, after, its reasons and confidence, and the health message', async () => {
    mockApi();
    renderPanel(true);
    const card = await screen.findByTestId('suggestion');
    expect(within(card).getByText('Replace')).toBeTruthy();
    expect(within(card).getByTestId('before').textContent).toBe('Mon, Nov 16 · Hills · 10 km · 60 min');
    expect(within(card).getByTestId('after').textContent).toBe('Mon, Nov 16 · Easy · 6 km · 36 min');
    expect(within(card).getByText('Recovery is red today (20%).')).toBeTruthy();
    expect(within(card).getByText('High confidence')).toBeTruthy();
    expect(screen.getByTestId('health-message').textContent).toContain('physio or doctor');
  });

  it('accepts with one tap', async () => {
    mockApi();
    renderPanel(true);
    fireEvent.click(await screen.findByTestId('accept'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].url).toBe('/api/suggestions/41/accept');
  });

  it('rejects with an optional note', async () => {
    mockApi();
    renderPanel(true);
    fireEvent.click(await screen.findByText('Reject'));
    fireEvent.change(screen.getByLabelText('Why not? (optional)'), { target: { value: 'Race-specific, keep it' } });
    fireEvent.click(screen.getByTestId('confirm-reject'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toEqual({ url: '/api/suggestions/41/reject', body: { note: 'Race-specific, keep it' } });
  });

  it('edits the suggested session before accepting it', async () => {
    mockApi();
    renderPanel(true);
    fireEvent.click(await screen.findByText('Edit'));
    fireEvent.change(screen.getByLabelText('Duration (min)'), { target: { value: '30' } });
    fireEvent.click(screen.getByTestId('accept-edited'));
    await waitFor(() => expect(calls).toHaveLength(1));
    const body = calls[0].body as { after: { session: { targetDurationMin: number; sessionType: string } } };
    expect(calls[0].url).toBe('/api/suggestions/41/accept');
    expect(body.after.session.targetDurationMin).toBe(30);
    expect(body.after.session.sessionType).toBe('easy');
  });

  it('lets the coach check the plan now and says what changed', async () => {
    mockApi();
    renderPanel(true);
    fireEvent.click(await screen.findByTestId('run-engine'));
    expect(await screen.findByText('Checked: 1 new, 2 out of date.')).toBeTruthy();
  });

  it('shows an athlete the suggestion without the buttons', async () => {
    mockApi();
    renderPanel(false);
    await screen.findByTestId('suggestion');
    expect(screen.queryByTestId('accept')).toBeNull();
    expect(screen.queryByTestId('run-engine')).toBeNull();
    expect(screen.getByText('Your coach decides on suggestions.')).toBeTruthy();
  });

  it('says so when there is nothing to change', async () => {
    mockApi({ date: '2026-11-16', pending: [], healthMessages: [] });
    renderPanel(true);
    expect(await screen.findByText('No changes suggested for the next 7 days.')).toBeTruthy();
  });
});
