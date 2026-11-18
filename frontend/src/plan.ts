import type { PlannedSession } from './api/types';
import { dayNumber, isoOf } from './trends';

// Pure helpers for the Plan screen (stage 2-1a, ADR 0019).

/** How many weeks ahead the Plan screen lists, counting this week. A display choice, not a training number. */
export const WEEKS_SHOWN = 8;

/** Today's date (YYYY-MM-DD) in the athlete's timezone. */
export function todayIn(timezone: string | undefined, now: Date = new Date()): string {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone: timezone || undefined, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
  } catch {
    return now.toISOString().slice(0, 10);
  }
}

/** The Monday of the week that holds {@code iso}. */
export function mondayOf(iso: string): string {
  const n = dayNumber(iso);
  const weekday = (new Date(n * 86400000).getUTCDay() + 6) % 7; // Monday = 0
  return isoOf(n - weekday);
}

export function addDays(iso: string, days: number): string {
  return isoOf(dayNumber(iso) + days);
}

/** The range the screen asks for: from this week's Monday to the Sunday {@code WEEKS_SHOWN} weeks on. */
export function shownRange(today: string): { from: string; to: string } {
  const from = mondayOf(today);
  return { from, to: addDays(from, WEEKS_SHOWN * 7 - 1) };
}

export interface Week {
  monday: string;
  sessions: PlannedSession[];
  /** Planned km of the sessions still counted (not skipped or replaced). */
  km: number;
  keyCount: number;
}

/** Groups sessions by week (Monday first). Every week in the range is listed, also empty ones. */
export function groupByWeek(sessions: PlannedSession[], from: string, to: string): Week[] {
  const weeks: Week[] = [];
  for (let m = mondayOf(from); m <= to; m = addDays(m, 7)) {
    const end = addDays(m, 6);
    const inWeek = sessions
      .filter((s) => s.date >= m && s.date <= end)
      .sort((a, b) => (a.date === b.date ? a.id - b.id : a.date < b.date ? -1 : 1));
    const counted = inWeek.filter((s) => s.status !== 'SKIPPED' && s.status !== 'REPLACED');
    weeks.push({
      monday: m,
      sessions: inWeek,
      km: Math.round(counted.reduce((sum, s) => sum + (s.targetDistanceKm ?? 0), 0) * 10) / 10,
      keyCount: counted.filter((s) => s.demand === 'KEY').length,
    });
  }
  return weeks;
}

/** "Mon 23 Nov" in the UI language. */
export function formatSessionDay(iso: string, lang: string): string {
  const d = new Date(`${iso}T12:00:00Z`);
  return new Intl.DateTimeFormat(lang, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(d);
}

/** Text field → number, or undefined when empty or not a number. Accepts a comma as decimal mark. */
export function parseNumber(v: string): number | undefined {
  const s = v.trim().replace(',', '.');
  if (s === '') return undefined;
  const n = Number(s);
  return Number.isFinite(n) ? n : undefined;
}
