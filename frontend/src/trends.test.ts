import { describe, expect, it } from 'vitest';
import { align, dateGrid, ewma, lastKnown, loadRatio, segments, tail } from './trends';

// Synthetic series only.

describe('ewma', () => {
  it('matches a hand calculation (span 3, α = 0.5)', () => {
    // seed 10; then 0 → 5; then 0 → 2.5; then 20 → 11.25
    const out = ewma([10, 0, 0, 20], 3);
    expect(out[0]).toBeNull(); // fewer than `span` known days: not shown yet
    expect(out[1]).toBeNull();
    expect(out[2]).toBeCloseTo(2.5, 10);
    expect(out[3]).toBeCloseTo(11.25, 10);
  });

  it('a rest day (0) pulls the average down; an unknown day (null) does not', () => {
    const withRest = ewma([10, 10, 10, 0, 10], 3);
    const withUnknown = ewma([10, 10, 10, null, 10], 3);
    expect(withRest[4]!).toBeLessThan(10);
    expect(withUnknown[4]).toBeCloseTo(10, 10);
    expect(withUnknown[3]).toBeNull(); // the unknown day itself stays a gap
  });

  it('fades by the real days that passed across an unknown stretch', () => {
    // known 10 on day 0..2, unknown on 3,4, then 0 on day 5 (g = 3 since day 2): w = 1 - 0.5^3 = 0.875
    const out = ewma([10, 10, 10, null, null, 0], 3);
    expect(out[5]).toBeCloseTo(10 * (1 - 0.875), 10);
  });

  it('is hidden until span known days exist (28-day chronic needs 28 days)', () => {
    const out = ewma(new Array(30).fill(5), 28);
    expect(out.slice(0, 27).every((v) => v === null)).toBe(true);
    expect(out[27]).toBeCloseTo(5, 10);
  });

  it('acute (7 d) reacts faster than chronic (28 d) to a hard week', () => {
    const series = [...new Array(35).fill(50), ...new Array(7).fill(150)];
    const acute = ewma(series, 7);
    const chronic = ewma(series, 28);
    const i = series.length - 1;
    expect(acute[i]!).toBeGreaterThan(chronic[i]!);
    expect(loadRatio(acute, chronic)[i]!).toBeGreaterThan(1);
  });
});

describe('loadRatio', () => {
  it('is blank unless both exist and chronic is above zero', () => {
    expect(loadRatio([10, null, 5, 5], [5, 5, null, 0])).toEqual([2, null, null, null]);
  });
});

describe('segments (gaps are gaps)', () => {
  it('breaks the line at any unknown day when no unknown run is allowed', () => {
    const runs = segments([1, 2, null, 4, 5], 0);
    expect(runs.map((r) => r.map((p) => p.index))).toEqual([[0, 1], [3, 4]]);
  });

  it('keeps a lone point as its own run (it is drawn as a dot)', () => {
    expect(segments([null, 3, null, null, 7], 0).map((r) => r.length)).toEqual([1, 1]);
  });

  it('connects across short unknown runs and breaks across long ones', () => {
    const s = [1, null, null, 4, ...new Array(8).fill(null), 9];
    const runs = segments(s, 7);
    expect(runs.map((r) => r.map((p) => p.index))).toEqual([[0, 3], [12]]);
  });

  it('never turns an unknown day into a value', () => {
    const all = segments([1, null, 3], 7).flat();
    expect(all.map((p) => p.index)).toEqual([0, 2]);
  });
});

describe('grid helpers', () => {
  it('lists every date and leaves absent days null', () => {
    const dates = dateGrid('2026-02-27', '2026-03-02');
    expect(dates).toEqual(['2026-02-27', '2026-02-28', '2026-03-01', '2026-03-02']);
    const out = align(dates, [{ date: '2026-02-28', v: 5 }], (r) => r.v);
    expect(out).toEqual([null, 5, null, null]);
  });

  it('tail keeps the newest days; lastKnown finds the newest value', () => {
    expect(tail([1, 2, 3, 4], 2)).toEqual([3, 4]);
    expect(lastKnown([1, null, 3, null])).toBe(2);
    expect(lastKnown([null])).toBe(-1);
  });
});
