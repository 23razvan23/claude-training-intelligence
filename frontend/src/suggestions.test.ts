import { describe, expect, it } from 'vitest';
import type { Suggestion } from './api/types';
import { describeSession, editedAfter, editFieldsOf, reasonText, sessionName } from './suggestions';

const t = (key: string, o?: Record<string, unknown>) => {
  if (o && 'defaultValue' in o) return String(o.defaultValue);
  if (key === 'plan.km') return `${o?.value} km`;
  if (key === 'plan.min') return `${o?.value} min`;
  return key.replace('plan.type.', '');
};

const SWAP: Suggestion = {
  id: 5, athleteId: 1, plannedSessionId: 2, counterpartSessionId: 3, forDate: '2026-11-16', action: 'SWAP',
  before: {
    session: { id: 2, athleteId: 1, date: '2026-11-16', sessionType: 'threshold', demand: 'KEY', title: '', targetDistanceKm: 10, targetDurationMin: 60, status: 'PLANNED' },
    counterpart: { id: 3, athleteId: 1, date: '2026-11-18', sessionType: 'easy', demand: 'EASY', title: 'Shakeout', status: 'PLANNED' },
  },
  after: {
    session: { date: '2026-11-18', sessionType: 'threshold', demand: 'KEY', targetDistanceKm: 10, targetDurationMin: 60, description: 'x' },
    counterpart: { date: '2026-11-16', sessionType: 'easy', demand: 'EASY', title: 'Shakeout' },
  },
  reasons: [{ code: 'red.recovery', text: 'Recovery is red today (28%).', params: { recoveryPct: 28 } }],
  confidence: 'HIGH', status: 'PENDING', rulesVersion: 'r1', createdAt: '2026-11-16T06:00:00Z',
};

describe('suggestions', () => {
  it('names a session by its title, else by its type', () => {
    expect(sessionName({ title: 'Tempo', sessionType: 'threshold' }, t)).toBe('Tempo');
    expect(sessionName({ title: ' ', sessionType: 'threshold' }, t)).toBe('threshold');
  });

  it('describes a session with its day, type, distance and time', () => {
    expect(describeSession(SWAP.before.session, t, 'en')).toBe('Mon, Nov 16 · threshold · 10 km · 60 min');
  });

  it('shows the server text when there is no translation for a reason', () => {
    expect(reasonText(SWAP.reasons[0], t)).toBe('Recovery is red today (28%).');
  });

  it('keeps everything as suggested except what the coach changed', () => {
    const f = { ...editFieldsOf(SWAP), km: '8,5', min: '', counterpartDate: '2026-11-17' };
    const after = editedAfter(SWAP, f);
    expect(after.session).toEqual({ ...SWAP.after.session, targetDistanceKm: 8.5, targetDurationMin: null });
    expect(after.counterpart).toEqual({ ...SWAP.after.counterpart, date: '2026-11-17' });
  });
});
