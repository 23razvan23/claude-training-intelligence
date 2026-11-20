import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { Plan, PlannedSession, Suggestion } from '../api/types';
import { PlanPage } from './PlanPage';

// The Plan screen (stage 2-1a) on synthetic data, through the real page and a faked fetch.

vi.mock('../athleteContext', () => ({ useAthleteSelection: () => ({ athleteId: 1, setAthleteId: () => {} }) }));

const PLAN: Plan = { id: 7, athleteId: 1, name: 'Autumn block', origin: 'MANUAL', status: 'ACTIVE', startDate: '2026-11-16', endDate: '2026-12-13' };
const TYPES = [
  { code: 'easy', defaultDemand: 'EASY' },
  { code: 'long', defaultDemand: 'KEY' },
  { code: 'threshold', defaultDemand: 'KEY' },
];
const SESSIONS: PlannedSession[] = [
  { id: 1, athleteId: 1, planId: 7, date: '2026-11-17', sessionType: 'easy', demand: 'EASY', title: '', targetDistanceKm: 8, status: 'DONE' },
  { id: 2, athleteId: 1, planId: 7, date: '2026-11-19', sessionType: 'threshold', demand: 'KEY', title: 'Tempo', description: '3 km WU, 20 min @ T', targetDistanceKm: 12.5, targetDurationMin: 60, status: 'PLANNED' },
  { id: 3, athleteId: 1, planId: 7, date: '2026-11-21', sessionType: 'long', demand: 'KEY', title: '', targetDistanceKm: 24, status: 'SKIPPED' },
];

let posted: unknown[] = [];

function mockApi(role: 'COACH' | 'ATHLETE', plans: Plan[] = [PLAN], pending: Suggestion[] = []) {
  posted = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { 'Content-Type': 'application/json' } });
      if (url === '/api/auth/me') return json({ username: 'u', role, athleteId: 1 });
      if (url === '/api/auth/csrf') return new Response(null, { status: 204 });
      if (url === '/api/athletes') return json([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest' }]);
      if (url === '/api/athletes/1/plans') return json(plans);
      if (url === '/api/plan/session-types') return json(TYPES);
      if (url.startsWith('/api/athletes/1/suggestions?status=PENDING')) return json(pending);
      if (url.startsWith('/api/athletes/1/sessions') && (init?.method ?? 'GET') === 'GET') {
        expect(url).toBe('/api/athletes/1/sessions?from=2026-11-16&to=2027-01-10');
        return json(SESSIONS);
      }
      if (url === '/api/athletes/1/sessions' && init?.method === 'POST') {
        posted.push(JSON.parse(String(init.body)));
        return json({ ...SESSIONS[0], id: 9 }, 201);
      }
      return json({ detail: 'unexpected ' + url }, 500);
    }),
  );
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PlanPage />
    </QueryClientProvider>,
  );
}

describe('PlanPage', () => {
  beforeAll(async () => {
    await i18n.changeLanguage('en');
  });
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-18T09:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('lists this week by day with demand, status and the week totals', async () => {
    mockApi('COACH');
    renderPage();
    const week = (await screen.findByText('Week of Mon, Nov 16')).closest('.week-block') as HTMLElement;
    expect(within(week).getByText('3 sessions · 20.5 km · 1 key')).toBeTruthy(); // the skipped long run is not counted
    expect(within(week).getByText('Tempo')).toBeTruthy();
    expect(within(week).getByText('3 km WU, 20 min @ T')).toBeTruthy();
    expect(within(week).getAllByText('Key')).toHaveLength(2);
    expect(within(week).getByText('Skipped')).toBeTruthy();
    expect(within(week).getByText('Long run')).toBeTruthy(); // no title: the type's name
    expect(screen.getAllByText('Nothing planned.')).toHaveLength(7);
  });

  it('lets the coach add a session; the demand follows the type until changed by hand', async () => {
    mockApi('COACH');
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Add session' }));
    const form = await screen.findByRole('form', { name: 'Add session' });
    await waitFor(() => expect(within(form).getAllByRole('option').length).toBeGreaterThan(3));
    fireEvent.change(within(form).getByLabelText('Type'), { target: { value: 'long' } });
    expect((within(form).getByLabelText('Demand') as HTMLSelectElement).value).toBe('KEY');
    fireEvent.change(within(form).getByLabelText('Distance (km)'), { target: { value: '22,5' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({ date: '2026-11-18', sessionType: 'long', demand: 'KEY', targetDistanceKm: 22.5, status: 'PLANNED' });
  });

  it('shows a pending suggestion on the session it changes, and the coach can mark a session as taper', async () => {
    const suggestion: Suggestion = {
      id: 8, athleteId: 1, plannedSessionId: 2, forDate: '2026-11-19', action: 'REDUCE',
      before: { session: SESSIONS[1] },
      after: { session: { date: '2026-11-19', sessionType: 'threshold', demand: 'KEY', title: 'Tempo', targetDistanceKm: 9.4, targetDurationMin: 45 } },
      reasons: [{ code: 'yellow.streak', text: 'Recovery has not been green for 2 days in a row (yellow today, 52%).' }],
      confidence: 'MEDIUM', status: 'PENDING', rulesVersion: 'r1', createdAt: '2026-11-18T06:00:00Z',
    };
    mockApi('COACH', [PLAN], [suggestion]);
    renderPage();
    const badge = await screen.findByTestId('suggestion-badge');
    const card = badge.closest('li') as HTMLElement;
    expect(within(card).getAllByText('Tempo', { selector: 'strong' })).toHaveLength(2); // the session and its suggestion
    expect(within(card).getByText(/not been green for 2 days/)).toBeTruthy();
    expect(within(card).getByTestId('after').textContent).toBe('Thu, Nov 19 · Threshold / tempo · 9.4 km · 45 min');

    fireEvent.click(screen.getByRole('button', { name: 'Add session' }));
    const form = await screen.findByRole('form', { name: 'Add session' });
    fireEvent.click(within(form).getByLabelText(/Part of a taper/));
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({ taper: true });
  });

  it('shows an athlete their plan read-only', async () => {
    mockApi('ATHLETE');
    renderPage();
    await screen.findByText('Tempo');
    expect(screen.queryByRole('button', { name: 'Add session' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.getByText(/Your coach plans these sessions/)).toBeTruthy();
  });

  it('asks the coach to create a plan first', async () => {
    mockApi('COACH', []);
    renderPage();
    expect(await screen.findByText(/No active plan yet/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'New plan' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add session' })).toBeNull();
  });
});
