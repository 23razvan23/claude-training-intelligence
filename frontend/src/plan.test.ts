import { describe, expect, it } from 'vitest';
import type { PlannedSession } from './api/types';
import { groupByWeek, mondayOf, parseNumber, shownRange, todayIn } from './plan';

const s = (id: number, date: string, over: Partial<PlannedSession> = {}): PlannedSession => ({
  id,
  athleteId: 1,
  planId: 1,
  date,
  sessionType: 'easy',
  demand: 'EASY',
  title: '',
  status: 'PLANNED',
  ...over,
});

describe('plan helpers', () => {
  it('finds the Monday of a week, Sunday included', () => {
    expect(mondayOf('2026-11-23')).toBe('2026-11-23'); // Monday
    expect(mondayOf('2026-11-29')).toBe('2026-11-23'); // Sunday
    expect(mondayOf('2026-11-18')).toBe('2026-11-16'); // Wednesday
  });

  it('shows this week and the next seven', () => {
    expect(shownRange('2026-11-18')).toEqual({ from: '2026-11-16', to: '2027-01-10' });
  });

  it("uses the athlete's timezone for today", () => {
    const lateEvening = new Date('2026-11-18T23:30:00Z');
    expect(todayIn('Europe/Bucharest', lateEvening)).toBe('2026-11-19');
    expect(todayIn('America/New_York', lateEvening)).toBe('2026-11-18');
  });

  it('groups by week in date order, lists empty weeks, and leaves skipped sessions out of the totals', () => {
    const weeks = groupByWeek(
      [
        s(3, '2026-11-25', { targetDistanceKm: 12.5, demand: 'KEY' }),
        s(1, '2026-11-23', { targetDistanceKm: 8 }),
        s(2, '2026-11-24', { targetDistanceKm: 10, demand: 'KEY', status: 'SKIPPED' }),
        s(4, '2026-12-07', { targetDistanceKm: 20 }),
      ],
      '2026-11-23',
      '2026-12-13',
    );
    expect(weeks.map((w) => w.monday)).toEqual(['2026-11-23', '2026-11-30', '2026-12-07']);
    expect(weeks[0].sessions.map((x) => x.id)).toEqual([1, 2, 3]);
    expect(weeks[0].km).toBe(20.5);
    expect(weeks[0].keyCount).toBe(1);
    expect(weeks[1].sessions).toEqual([]);
    expect(weeks[2].km).toBe(20);
  });

  it('reads numbers with a decimal comma and leaves empty fields empty', () => {
    expect(parseNumber('12,5')).toBe(12.5);
    expect(parseNumber(' ')).toBeUndefined();
    expect(parseNumber('abc')).toBeUndefined();
  });
});
