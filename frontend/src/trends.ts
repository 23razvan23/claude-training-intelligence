// Trends maths (stage 1b-2b, ADR 0015). Display only: nothing here is stored or feeds a score.
// Rule for everything below: a day without data is `null`, never 0 and never filled in.

/** [configurable] Acute window, days: about one training week. EWMA decay α = 2 / (span + 1). */
export const ACUTE_DAYS = 7;
/** [configurable] Chronic window, days: about one training block (four weeks). */
export const CHRONIC_DAYS = 28;
/**
 * [configurable] A load line is drawn across at most this many unknown days in a row; a longer
 * run is a visible gap. A weekly average changes slowly, so a short hole does not hide a change.
 */
export const LOAD_MAX_UNKNOWN_RUN = 7;

export type Range = '4w' | '3m' | '12m';
/** [configurable] The ranges, in days. */
export const RANGE_DAYS: Record<Range, number> = { '4w': 28, '3m': 91, '12m': 365 };
export const RANGES: Range[] = ['4w', '3m', '12m'];

const DAY_MS = 86_400_000;

export function dayNumber(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number);
  return Math.round(Date.UTC(y, m - 1, d) / DAY_MS);
}

export function isoOf(dayNo: number): string {
  return new Date(dayNo * DAY_MS).toISOString().slice(0, 10);
}

/** Every date from `from` to `to`, inclusive. */
export function dateGrid(from: string, to: string): string[] {
  const out: string[] = [];
  for (let n = dayNumber(from); n <= dayNumber(to); n++) out.push(isoOf(n));
  return out;
}

/** Puts values on a full date grid; a date with no value stays null. */
export function align<R extends { date: string }, T>(
  dates: string[],
  rows: R[],
  pick: (r: R) => T | null | undefined,
): (T | null)[] {
  const byDate = new Map(rows.map((r) => [r.date, r]));
  return dates.map((d) => {
    const r = byDate.get(d);
    return (r ? pick(r) : null) ?? null;
  });
}

/**
 * Exponentially weighted moving average of a daily series with unknown days.
 * - null = unknown: it is skipped, never counted as 0 (a rest day is a real 0).
 * - With α = 2/(span+1), a known day after `g` days since the last known one moves the average by
 *   1 − (1 − α)^g of the way to the new value; for g = 1 that is exactly α. So the average fades by
 *   the real number of days that passed, not by the number of known days.
 * - Seeded with the first known value. Shown as null until `span` known days have been seen,
 *   because before that the average mostly reflects the seed.
 */
export function ewma(series: (number | null)[], span: number): (number | null)[] {
  const alpha = 2 / (span + 1);
  const out: (number | null)[] = [];
  let avg: number | null = null;
  let last = -1;
  let known = 0;
  series.forEach((x, i) => {
    if (x == null) {
      out.push(null);
      return;
    }
    if (avg == null) avg = x;
    else {
      const w = 1 - Math.pow(1 - alpha, i - last);
      avg = avg + w * (x - avg);
    }
    last = i;
    known += 1;
    out.push(known >= span ? avg : null);
  });
  return out;
}

/** Acute ÷ chronic, only where both exist and chronic is above zero. One signal, not a verdict. */
export function loadRatio(acute: (number | null)[], chronic: (number | null)[]): (number | null)[] {
  return acute.map((a, i) => {
    const c = chronic[i];
    return a != null && c != null && c > 0 ? a / c : null;
  });
}

/** The last index where the series has a value, or -1. */
export function lastKnown(series: (number | null)[]): number {
  for (let i = series.length - 1; i >= 0; i--) if (series[i] != null) return i;
  return -1;
}

/**
 * Splits a series into runs that are drawn as one line. A run ends where the unknown days
 * between two known days are more than `maxUnknownRun` (0 = any unknown day is a gap).
 * A run of one point is a lone dot.
 */
export function segments(series: (number | null)[], maxUnknownRun: number): { index: number; value: number }[][] {
  const runs: { index: number; value: number }[][] = [];
  let current: { index: number; value: number }[] = [];
  let prev = -1;
  series.forEach((v, i) => {
    if (v == null) return;
    if (current.length > 0 && i - prev - 1 > maxUnknownRun) {
      runs.push(current);
      current = [];
    }
    current.push({ index: i, value: v });
    prev = i;
  });
  if (current.length > 0) runs.push(current);
  return runs;
}

/** The last `days` entries of every aligned array (the end of the range is the newest day). */
export function tail<T>(arr: T[], days: number): T[] {
  return arr.slice(Math.max(0, arr.length - days));
}
