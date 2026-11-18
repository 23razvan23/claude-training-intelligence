import { describe, expect, it } from 'vitest';
import type { Checkin, CheckinOptions } from './api/types';
import { EMPTY_FORM, hasAnswer, isForScope, minusDays, prefill, setLocation, setResolved, setSeverity, toInput } from './checkin';

const row = (over: Partial<Checkin>): Checkin => ({
  id: 1, athleteId: 1, athleteName: 'A', date: '2026-12-01', scope: 'day', plannedSessionId: null, activityId: null,
  rpe: null, soreness: null, niggleLocation: null, niggleSeverity: null, niggleResolved: false, injury: false,
  illness: false, note: null, source: 'MANUAL', onBehalf: false, createdAt: '2026-12-01T07:00:00Z', ...over,
});
const options = (current: Checkin[]): CheckinOptions => ({
  today: '2026-12-01', date: '2026-12-01', timezone: 'Europe/Bucharest', activities: [], sessions: [], current,
  locations: ['knee'], maxBackdateDays: 7,
});

describe('check-in form logic', () => {
  it('an empty form has no answer; any single answer counts', () => {
    expect(hasAnswer(EMPTY_FORM)).toBe(false);
    expect(hasAnswer({ ...EMPTY_FORM, note: '   ' })).toBe(false);
    expect(hasAnswer({ ...EMPTY_FORM, illness: true })).toBe(true);
    expect(hasAnswer({ ...EMPTY_FORM, rpe: 4 })).toBe(true);
  });

  it('a niggle keeps severity and "gone" coherent, and clearing the place clears both', () => {
    let f = setLocation(EMPTY_FORM, 'knee');
    f = setSeverity(f, 3);
    expect(f).toMatchObject({ niggleLocation: 'knee', niggleSeverity: 3, niggleResolved: false });
    f = setResolved(f, true);
    expect(f).toMatchObject({ niggleSeverity: null, niggleResolved: true });
    f = setSeverity(f, 2);
    expect(f.niggleResolved).toBe(false);
    expect(setLocation(f, null)).toMatchObject({ niggleLocation: null, niggleSeverity: null, niggleResolved: false });
  });

  it('sends only what was answered, and the scope in the right field', () => {
    const f = { ...EMPTY_FORM, rpe: 6, note: ' tired ' };
    expect(toInput(f, { kind: 'day' }, '2026-12-01')).toEqual({
      date: '2026-12-01', rpe: 6, niggleResolved: false, injury: false, illness: false, note: 'tired',
    });
    expect(toInput(f, { kind: 'activity', id: 9 }, '2026-12-01')).toMatchObject({ activityId: 9 });
    expect(toInput(f, { kind: 'activity', id: 9 }, '2026-12-01')).not.toHaveProperty('date');
    expect(toInput(f, { kind: 'session', id: 4 })).toMatchObject({ plannedSessionId: 4 });
  });

  it('a correction starts from the answer that counts for the same scope only', () => {
    const o = options([row({ id: 1, rpe: 5 }), row({ id: 2, scope: 'activity', activityId: 7, rpe: 8, soreness: 3 })]);
    expect(prefill(o, { kind: 'day' }).form.rpe).toBe(5);
    expect(prefill(o, { kind: 'activity', id: 7 })).toMatchObject({ form: { rpe: 8, soreness: 3 }, existing: { id: 2 } });
    expect(prefill(o, { kind: 'activity', id: 8 })).toEqual({ form: EMPTY_FORM, existing: null });
    expect(prefill(o, { kind: 'session', id: 7 }).existing).toBeNull();
  });

  it('matches the server scope rule: session, else activity, else day', () => {
    const both = row({ plannedSessionId: 3, activityId: 7 });
    expect(isForScope(both, { kind: 'session', id: 3 })).toBe(true);
    expect(isForScope(both, { kind: 'activity', id: 7 })).toBe(false);
    expect(isForScope(row({}), { kind: 'day' })).toBe(true);
  });

  it('counts back across a month end', () => {
    expect(minusDays('2026-12-03', 7)).toBe('2026-11-26');
  });
});
