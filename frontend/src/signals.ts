// Adaptive engine signals (stage 2-2, ADR 0021): wording helpers. Pure; every number comes from the server.
import type { RunningSignal, StoredSignals, ToleranceSignal } from './api/types';

export interface Step {
  key: string;
  params?: Record<string, string | number>;
}

const km = (v: number | null | undefined) => (v == null ? '–' : v.toFixed(1));
const pct = (v: number) => Math.round(v * 100);

/** The derivation of the running tolerance, one plain sentence per step (ADR 0021 D11). */
export function toleranceSteps(t: ToleranceSignal): Step[] {
  const steps: Step[] = [];
  if (t.peakKmPerWeek == null) {
    steps.push({ key: 'tolerance.steps.none' });
  } else {
    steps.push({ key: 'tolerance.steps.peak', params: { km: km(t.peakKmPerWeek), from: t.peakFrom ?? '', to: t.peakTo ?? '' } });
    if (t.flaggedWindows > 0) steps.push({ key: 'tolerance.steps.flagged', params: { n: t.flaggedWindows } });
    if (t.decayWeeks > 0)
      steps.push({
        key: 'tolerance.steps.decay',
        params: { weeks: t.interruptedWeeks, decayWeeks: t.decayWeeks, pct: pct(1 - t.decayFactor), km: km(t.decayedKmPerWeek) },
      });
    else steps.push({ key: 'tolerance.steps.noDecay', params: { weeks: t.interruptedWeeks } });
    if (t.capKmPerWeek == null) steps.push({ key: 'tolerance.steps.noCap' });
    else if (t.capped) steps.push({ key: 'tolerance.steps.capped', params: { km: km(t.capKmPerWeek), chronic: km(t.chronicKmPerWeek) } });
    else steps.push({ key: 'tolerance.steps.underCap', params: { km: km(t.capKmPerWeek) } });
  }
  if (t.source === 'override' && t.override?.kmPerWeek != null)
    steps.push({
      key: 'tolerance.steps.override',
      params: { km: km(t.override.kmPerWeek), computed: km(t.computedKmPerWeek), since: t.override.since ?? '' },
    });
  return steps;
}

/** This week's running km as a share of the tolerance, in %; null without a tolerance above 0. */
export function shareOfTolerance(running: RunningSignal | null | undefined, t: ToleranceSignal | null | undefined): number | null {
  if (!running || !t || t.kmPerWeek == null || t.kmPerWeek <= 0) return null;
  return Math.round((running.shortKm / t.kmPerWeek) * 100);
}

/** The newest stored day, or null. */
export function latest(rows: StoredSignals[] | undefined): StoredSignals | null {
  return rows && rows.length > 0 ? rows[rows.length - 1] : null;
}

/** Values per date of `dates` (null where no stored day), for charts. */
export function byDate(rows: StoredSignals[], dates: string[], pick: (s: StoredSignals) => number | null | undefined): (number | null)[] {
  const m = new Map(rows.map((r) => [r.date, r]));
  return dates.map((d) => {
    const r = m.get(d);
    return r ? (pick(r) ?? null) : null;
  });
}

/** Signed number for a trend, e.g. "+4.0" or "−6.7". */
export function signed(v: number): string {
  return (v > 0 ? '+' : v < 0 ? '−' : '±') + Math.abs(v).toFixed(1);
}
