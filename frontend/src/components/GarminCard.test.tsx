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
