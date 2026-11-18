import { describe, expect, it } from 'vitest';
import { byDate, latest, shareOfTolerance, signed, toleranceSteps } from './signals';
import type { StoredSignals, ToleranceSignal } from './api/types';

// Synthetic values only.
const base: ToleranceSignal = {
  kmPerWeek: 42,
  source: 'computed',
  computedKmPerWeek: 42,
  peakKmPerWeek: 60,
  peakFrom: '2026-03-02',
  peakTo: '2026-03-29',
  cleanWindows: 300,
  flaggedWindows: 0,
  interruptedWeeks: 3,
  decayWeeks: 2,
  decayFactor: 0.8,
  decayedKmPerWeek: 48,
  chronicKmPerWeek: 42,
  capKmPerWeek: 42,
  capped: true,
};

describe('toleranceSteps', () => {
  it('explains peak, decay and cap in order', () => {
    const keys = toleranceSteps(base).map((s) => s.key);
    expect(keys).toEqual(['tolerance.steps.peak', 'tolerance.steps.decay', 'tolerance.steps.capped']);
    expect(toleranceSteps(base)[1].params).toMatchObject({ weeks: 3, decayWeeks: 2, pct: 20, km: '48.0' });
  });

  it('mentions flagged windows, no decay, no cap yet, and an override', () => {
    const t: ToleranceSignal = {
      ...base,
      flaggedWindows: 5,
      decayWeeks: 0,
      interruptedWeeks: 1,
      capKmPerWeek: null,
      capped: false,
      source: 'override',
      kmPerWeek: 35,
      override: { kmPerWeek: 35, since: '2026-11-18' },
    };
    expect(toleranceSteps(t).map((s) => s.key)).toEqual([
      'tolerance.steps.peak',
      'tolerance.steps.flagged',
      'tolerance.steps.noDecay',
      'tolerance.steps.noCap',
      'tolerance.steps.override',
    ]);
  });

  it('says so when there was no running', () => {
    expect(toleranceSteps({ ...base, peakKmPerWeek: null, source: 'none', kmPerWeek: null }).map((s) => s.key)).toEqual([
      'tolerance.steps.none',
    ]);
  });
});

describe('shareOfTolerance', () => {
  const running = { shortWindowDays: 7, shortKm: 46.2, shortMinutes: 250, longWindowDays: 21, longKm: 120, longMinutes: 700, longWeeklyAvgKm: 40 };
  it('is this week as a share of tolerance', () => {
    expect(shareOfTolerance(running, base)).toBe(110);
  });
  it('is blank without a tolerance', () => {
    expect(shareOfTolerance(running, { ...base, kmPerWeek: null })).toBeNull();
    expect(shareOfTolerance(null, base)).toBeNull();
  });
});

describe('byDate and latest', () => {
  const row = (date: string, km: number) => ({ date, signals: { running: { shortKm: km } } }) as unknown as StoredSignals;
  it('lines values up by date and leaves gaps empty', () => {
    const rows = [row('2026-11-01', 10), row('2026-11-03', 30)];
    expect(byDate(rows, ['2026-11-01', '2026-11-02', '2026-11-03'], (r) => r.signals.running.shortKm)).toEqual([10, null, 30]);
    expect(latest(rows)?.date).toBe('2026-11-03');
    expect(latest([])).toBeNull();
  });
});

describe('signed', () => {
  it('shows the sign', () => {
    expect(signed(4)).toBe('+4.0');
    expect(signed(-6.66)).toBe('−6.7');
    expect(signed(0)).toBe('±0.0');
  });
});
