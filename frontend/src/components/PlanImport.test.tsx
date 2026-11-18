import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import i18n from '../i18n';
import type { PlanImportPreview } from '../api/types';
import { PlanImport } from './PlanImport';

// Plan import (stage 2-1a PR 2): preview first, nothing saved until Import. Synthetic rows, faked fetch.

const GOOD: PlanImportPreview = {
  canImport: true, fileErrors: [], rowCount: 2, errorCount: 0, warningCount: 1,
  planId: 7, planName: 'Autumn block', planStart: '2026-11-16', planEnd: '2026-11-22', newPlanStart: '2026-11-16', newPlanEnd: '2026-11-26',
  rows: [
    { row: 2, date: '2026-11-25', sessionType: 'threshold', demand: 'KEY', title: 'Tempo', workout: '3 km WU, 20 min @ T', distanceKm: 12.5, durationMin: 60, errors: [], warnings: ['this date already has a session (easy); the new one is added next to it'] },
    { row: 3, date: '2026-11-26', sessionType: 'rest', errors: [], warnings: [] },
  ],
};
const BAD: PlanImportPreview = {
  ...GOOD, canImport: false, errorCount: 1, warningCount: 0,
  rows: [GOOD.rows[1], { row: 7, date: '2026-11-27', sessionType: 'tempo', errors: ["row 7: unknown type 'tempo'"], warnings: [] }],
};

let calls: string[] = [];
function mockApi(preview: PlanImportPreview) {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { 'Content-Type': 'application/json' } });
    if (url === '/api/auth/csrf') return new Response(null, { status: 204 });
    calls.push(url);
    expect((init?.body as FormData).get('file')).toBeInstanceOf(File);
    if (url.endsWith('/preview')) return json(preview);
    if (url.endsWith('/commit')) return json({ imported: 2, planId: 7, stretchedPlan: true });
    return json({ detail: 'unexpected' }, 500);
  }));
}

function renderIt(onDone = () => {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={qc}><PlanImport athleteId={1} onDone={onDone} /></QueryClientProvider>);
  return view;
}

function pick(view: ReturnType<typeof render>) {
  const input = view.container.querySelector('input[type=file]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['date,type\n'], 'plan.csv', { type: 'text/csv' })] } });
}

describe('PlanImport', () => {
  beforeAll(async () => { await i18n.changeLanguage('en'); });
  afterEach(() => vi.unstubAllGlobals());

  it('shows the preview first and saves nothing until Import is pressed', async () => {
    mockApi(GOOD);
    const view = renderIt();
    pick(view);
    expect(await screen.findByText('2 sessions')).toBeTruthy();
    expect(screen.getByText(/1 row with a note/)).toBeTruthy();
    expect(screen.getByText('this date already has a session (easy); the new one is added next to it')).toBeTruthy();
    expect(screen.getByText(/will be stretched to/)).toBeTruthy();
    expect(calls).toEqual(['/api/athletes/1/plan-import/preview']);
    fireEvent.click(screen.getByRole('button', { name: 'Import 2 sessions' }));
    expect(await screen.findByText('Imported 2 sessions.')).toBeTruthy();
    expect(calls).toEqual(['/api/athletes/1/plan-import/preview', '/api/athletes/1/plan-import/commit']);
  });

  it('lists the row errors and keeps Import disabled', async () => {
    mockApi(BAD);
    const view = renderIt();
    pick(view);
    expect(await screen.findByText("row 7: unknown type 'tempo'")).toBeTruthy();
    expect(screen.getByText('1 row with a problem')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Import 2 sessions' }) as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect(calls).toEqual(['/api/athletes/1/plan-import/preview']));
  });
});
