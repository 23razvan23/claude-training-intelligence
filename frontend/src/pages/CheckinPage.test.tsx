import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { Checkin, CheckinOptions } from '../api/types';
import { CheckinPage } from './CheckinPage';

// The check-in form through the real page and fetch, on synthetic data.

let me = { id: 1, username: 'me', role: 'ATHLETE', athleteId: 1 };
vi.mock('../athleteContext', () => ({ useAthleteSelection: () => ({ athleteId: 1, setAthleteId: () => {} }) }));

const options = (over: Partial<CheckinOptions> = {}): CheckinOptions => ({
  today: '2026-12-01', date: '2026-12-01', timezone: 'Europe/Bucharest', locations: ['foot', 'knee', 'other'],
  maxBackdateDays: 7, activities: [{ id: 7, sport: 'running', startAt: '2026-12-01T05:30:00Z', durationS: 3600, distanceM: 10200 }],
  sessions: [], current: [], ...over,
});
const stored = (over: Partial<Checkin> = {}): Checkin => ({
  id: 5, athleteId: 1, athleteName: 'Synthetic Runner', date: '2026-12-01', scope: 'day', plannedSessionId: null,
  activityId: null, rpe: 5, soreness: null, niggleLocation: null, niggleSeverity: null, niggleResolved: false,
  injury: false, illness: false, note: null, source: 'MANUAL', onBehalf: false, createdAt: '2026-12-01T06:00:00Z', ...over,
});

const posts: unknown[] = [];
function mockApi(opts: CheckinOptions, recent: Checkin[] = []) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const ok = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { 'Content-Type': 'application/json' } });
    if (url === '/api/auth/me') return ok(me);
    if (url === '/api/athletes') return ok([{ id: 1, name: 'Synthetic Runner', timezone: 'Europe/Bucharest' }]);
    if (url.startsWith('/api/athletes/1/checkins/options')) return ok(opts);
    if (url === '/api/athletes/1/checkins/recent') return ok(recent);
    if (url === '/api/checkins/recent') return ok(recent);
    if (url === '/api/auth/csrf') return ok({});
    if (url === '/api/athletes/1/checkins' && init?.method === 'POST') {
      posts.push(JSON.parse(String(init.body)));
      return ok(stored(), 201);
    }
    return new Response('', { status: 404 });
  }));
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <CheckinPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage('en');
});
afterEach(() => {
  vi.unstubAllGlobals();
  posts.length = 0;
  me = { id: 1, username: 'me', role: 'ATHLETE', athleteId: 1 };
});

describe('Check-in page', () => {
  it('cannot be saved empty; one tap on RPE sends a whole-day check-in', async () => {
    mockApi(options());
    renderPage();
    const save = await screen.findByRole('button', { name: 'Save check-in' });
    expect(save).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '6' }));
    expect(save).toBeEnabled();
    fireEvent.click(save);
    await waitFor(() => expect(screen.getByTestId('saved')).toBeInTheDocument());
    expect(posts).toEqual([{ date: '2026-12-01', rpe: 6, niggleResolved: false, injury: false, illness: false }]);
  });

  it('offers the day, and a Garmin activity; choosing the activity sends its id, not a date', async () => {
    mockApi(options());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /^Run .*10\.2 km/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Illness' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save check-in' }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({ activityId: 7, illness: true });
    expect(posts[0]).not.toHaveProperty('date');
  });

  it('shows no planned-session choice when there is none', async () => {
    mockApi(options());
    renderPage();
    await screen.findByRole('button', { name: 'Whole day' });
    expect(screen.queryByRole('button', { name: /^Planned:/ })).not.toBeInTheDocument();
  });

  it('a niggle asks where, then how bad, and can be marked gone (severity then cleared)', async () => {
    mockApi(options());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Knee' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'How bad?' })).getByRole('button', { name: '3' }));
    fireEvent.click(screen.getByRole('button', { name: "It's gone" }));
    fireEvent.click(screen.getByRole('button', { name: 'Save check-in' }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({ niggleLocation: 'knee', niggleResolved: true });
    expect(posts[0]).not.toHaveProperty('niggleSeverity');
  });

  it('starts from the answer that already counts and offers a correction', async () => {
    mockApi(options({ current: [stored({ rpe: 5, soreness: 2 })] }));
    renderPage();
    expect(await screen.findByTestId('existing-note')).toHaveTextContent('the newest answer counts');
    expect(screen.getByRole('button', { name: '5', pressed: true })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save correction' })).toBeEnabled();
  });

  it("the coach sees a banner when entering for an athlete, and everyone's check-ins", async () => {
    me = { id: 9, username: 'coach', role: 'COACH', athleteId: 2 };
    mockApi(options(), [stored({ onBehalf: true })]);
    renderPage();
    expect(await screen.findByTestId('behalf-banner')).toHaveTextContent('entering this for Synthetic Runner');
    expect(await screen.findByTestId('everyone')).toBeInTheDocument();
    expect((await screen.findAllByText('by coach')).length).toBeGreaterThan(0);
  });

  it('an athlete does not get the coach overview or the banner', async () => {
    mockApi(options());
    renderPage();
    await screen.findByRole('button', { name: 'Whole day' });
    expect(screen.queryByTestId('everyone')).not.toBeInTheDocument();
    expect(screen.queryByTestId('behalf-banner')).not.toBeInTheDocument();
  });
});
