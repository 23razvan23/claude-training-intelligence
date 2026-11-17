import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { GarminStatus } from '../api/types';
import { GarminCard } from './GarminCard';

// Connect, MFA in the same form, reconnect and not-configured states (ADR 0011), with a fake API
// and obviously fake credentials.

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown };

function mockApi(handler: Handler) {
  const calls: { url: string; init?: RequestInit }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url === '/api/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=test-csrf';
        return new Response(null, { status: 204 });
      }
      const r = handler(url, init);
      return new Response(r.body === undefined ? null : JSON.stringify(r.body), {
        status: r.status,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
  return calls;
}

function status(over: Partial<GarminStatus>): GarminStatus {
  return { state: 'NOT_CONNECTED', stored: false, ...over };
}

function renderCard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <GarminCard athleteId={1} />
    </QueryClientProvider>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage('en');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GarminCard', () => {
  it('connects with MFA in the same form and never puts secrets in a URL', async () => {
    let state = status({});
    const calls = mockApi((url, init) => {
      if (url === '/api/athletes/1/garmin' && (!init?.method || init.method === 'GET')) return { status: 200, body: state };
      if (url === '/api/athletes/1/garmin/connect') return { status: 200, body: { status: 'MFA_REQUIRED', mfaId: 'fake-mfa-id' } };
      if (url === '/api/athletes/1/garmin/mfa') {
        const body = JSON.parse(String(init?.body));
        if (body.code !== '123456') return { status: 400, body: { detail: 'x', code: 'MFA_INVALID' } };
        state = status({ state: 'CONNECTED', stored: true, connectedAt: '2026-11-16T07:00:00Z' });
        return { status: 200, body: { status: 'CONNECTED' } };
      }
      return { status: 404 };
    });
    renderCard();

    fireEvent.change(await screen.findByLabelText('Garmin e-mail'), { target: { value: 'test-user@example.invalid' } });
    fireEvent.change(screen.getByLabelText('Garmin password'), { target: { value: 'test-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));

    const codeInput = await screen.findByLabelText('Code from Garmin');
    expect(screen.queryByLabelText('Garmin password')).toBeNull();
    fireEvent.change(codeInput, { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('That code was not accepted. Check it and try again.')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Code from Garmin'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('Connected')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeTruthy();

    for (const c of calls) {
      for (const secret of ['test-password', '123456', 'test-user', 'fake-mfa-id']) expect(c.url).not.toContain(secret);
    }
    const connectBody = JSON.parse(String(calls.find((c) => c.url.endsWith('/connect'))?.init?.body));
    expect(connectBody).toEqual({ username: 'test-user@example.invalid', password: 'test-password' });
  });

  it('shows a plain message when Garmin refuses the password', async () => {
    mockApi((url) =>
      url.endsWith('/connect')
        ? { status: 400, body: { detail: 'x', code: 'AUTH_FAILED' } }
        : { status: 200, body: status({}) },
    );
    renderCard();
    fireEvent.change(await screen.findByLabelText('Garmin e-mail'), { target: { value: 'test-user@example.invalid' } });
    fireEvent.change(screen.getByLabelText('Garmin password'), { target: { value: 'test-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));
    expect(await screen.findByText('Garmin did not accept that e-mail and password.')).toBeTruthy();
    expect((screen.getByLabelText('Garmin password') as HTMLInputElement).value).toBe('');
  });

  it('asks to connect again when Garmin rejected the token', async () => {
    mockApi(() => ({ status: 200, body: status({ state: 'RECONNECT_NEEDED', stored: true, lastError: 'AUTH_REJECTED' }) }));
    renderCard();
    expect(await screen.findByText('Garmin needs you to connect again')).toBeTruthy();
    expect(screen.getByLabelText('Garmin password')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeTruthy();
  });

  it('explains a changed token key', async () => {
    mockApi(() => ({ status: 200, body: status({ state: 'RECONNECT_NEEDED', stored: true, lastError: 'KEY_CHANGED' }) }));
    renderCard();
    expect(await screen.findByText(/GARMIN_TOKEN_KEY has changed/)).toBeTruthy();
  });

  it('says how to set it up when the keys are missing, and still offers Disconnect for a stored token', async () => {
    mockApi(() => ({ status: 200, body: status({ state: 'NOT_CONFIGURED', stored: true }) }));
    renderCard();
    expect(await screen.findByText(/Garmin sync is not set up on this computer/)).toBeTruthy();
    expect(screen.queryByLabelText('Garmin password')).toBeNull();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeTruthy();
  });

  it('disconnects after confirmation', async () => {
    let state = status({ state: 'CONNECTED', stored: true, connectedAt: '2026-11-16T07:00:00Z' });
    const calls = mockApi((_url, init) => {
      if (init?.method === 'DELETE') {
        state = status({});
        return { status: 204 };
      }
      return { status: 200, body: state };
    });
    vi.stubGlobal('confirm', () => true);
    renderCard();
    fireEvent.click(await screen.findByRole('button', { name: 'Disconnect' }));
    await waitFor(() => expect(screen.getByLabelText('Garmin password')).toBeTruthy());
    expect(calls.some((c) => c.init?.method === 'DELETE' && c.url === '/api/athletes/1/garmin')).toBe(true);
  });
});

describe('GarminSync (inside the card)', () => {
  const connected = { state: 'CONNECTED', stored: true, connectedAt: '2026-11-16T07:00:00Z' };
  const run = (over: Record<string, unknown>) => ({
    id: 1, trigger: 'MANUAL', status: 'SUCCEEDED', attempt: 1, firstDay: '2026-11-15', lastDay: '2026-11-17',
    startedAt: '2026-11-17T05:00:00Z', finishedAt: '2026-11-17T05:01:00Z', recomputed: true, rowsInserted: 120, missing: [],
    ...over,
  });

  it('shows the last sync and starts a new one', async () => {
    let runs = [run({})];
    const calls = mockApi((url, init) => {
      if (url === '/api/athletes/1/garmin') return { status: 200, body: connected };
      if (url === '/api/athletes/1/garmin/sync/runs') return { status: 200, body: runs };
      if (url === '/api/athletes/1/garmin/sync' && init?.method === 'POST') {
        runs = [run({ id: 2, status: 'RUNNING', finishedAt: null }), ...runs];
        return { status: 202, body: runs[0] };
      }
      return { status: 404 };
    });
    renderCard();
    expect(await screen.findByText(/120 new rows; features and scores updated/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }));
    expect(await screen.findByRole('button', { name: 'Syncing…' })).toBeTruthy();
    expect(calls.some((c) => c.url === '/api/athletes/1/garmin/sync' && c.init?.method === 'POST')).toBe(true);
  });

  it('says when a sync waits to retry, or failed', async () => {
    mockApi((url) =>
      url.endsWith('/sync/runs')
        ? { status: 200, body: [run({ status: 'RETRY_WAIT', errorCode: 'GARMIN_UNAVAILABLE', nextRetryAt: '2026-11-17T05:10:00Z' })] }
        : { status: 200, body: connected },
    );
    renderCard();
    expect(await screen.findByText(/Garmin could not be reached\. It will try again at/)).toBeTruthy();
  });

  it('reports a failed sync in plain words', async () => {
    mockApi((url) =>
      url.endsWith('/sync/runs')
        ? { status: 200, body: [run({ status: 'FAILED', errorCode: 'SIDECAR_UNREACHABLE' })] }
        : { status: 200, body: connected },
    );
    renderCard();
    expect(await screen.findByText(/failed: The garmin-sync service is not running/)).toBeTruthy();
  });

  it('runs the check against an export and shows the outcome without values', async () => {
    mockApi((url) => {
      if (url.endsWith('/garmin/check')) {
        return {
          status: 200,
          body: {
            from: '2026-11-05', to: '2026-11-11', differencesUsedByScores: 1, missing: [], warnings: [],
            tables: { daily_wellness: { inserted: 1, duplicates: 13, skipped: 0 } },
            differences: [{ table: 'daily_wellness', day: '2026-11-07', record: 'uds', column: 'resting_hr', usedByScores: true, outcome: 'DIFFERENT' }],
            sleepFieldSearch: [
              { column: 'avg_respiration', nights: 6, candidates: [{ path: 'averageRespiration', matches: 6, nights: 6, meanAbsDiff: 0.01 }] },
              { column: 'avg_sleep_stress', nights: 6, candidates: [] },
            ],
          },
        };
      }
      if (url.endsWith('/sync/runs')) return { status: 200, body: [] };
      return { status: 200, body: connected };
    });
    renderCard();
    fireEvent.click(await screen.findByText('Check against export'));
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    expect(await screen.findByText(/1 stored value the scores use would change/)).toBeTruthy();
    expect(screen.getByText('resting_hr')).toBeTruthy();
    expect(screen.getByText('averageRespiration')).toBeTruthy();
    expect(screen.getAllByText('6 / 6').length).toBe(1);
    expect(screen.getByText('no field found')).toBeTruthy();
    expect(screen.getByText('0.01')).toBeTruthy();
  });
});
