import type { TFunction } from 'i18next';
import type { TodayContribution, TodayReason } from './api/types';

// Plain-words wording for the Today screen (stage 1b-2a, ADR 0014). Pure functions: easy to test.

/** "+6", "−3", "0": recovery points as whole numbers with a sign. */
export function formatPoints(points?: number | null): string {
  if (points == null) return '–';
  const r = Math.round(points);
  if (r === 0) return '0';
  return r > 0 ? `+${r}` : `−${Math.abs(r)}`;
}

/** A date as "Mon, 16 Nov" in the UI language; dates are calendar days, so no timezone shift. */
export function formatDay(iso: string, lang: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(lang, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}

/** Minutes as "7 h 30 min". */
export function formatMinutes(min: number, t: TFunction): string {
  return t('today.hoursMinutes', { h: Math.floor(min / 60), m: Math.round(min % 60) });
}

const one = (v: number) => Math.abs(v).toFixed(1);

/** One recovery input in plain words, without its points ("HRV 54 ms, 8% above your 30-day baseline (50 ms)"). */
export function describeContribution(c: TodayContribution, t: TFunction): string {
  const v = c.value;
  switch (c.feature) {
    case 'ln_rmssd_z': {
      if (c.percentVsBaseline == null || c.measured == null || c.baseline == null) return t('today.contrib.hrvPlain');
      const pct = Math.round(c.percentVsBaseline);
      const args = { ms: Math.round(c.measured), base: Math.round(c.baseline), pct: Math.abs(pct) };
      if (pct === 0) return t('today.contrib.hrvSame', args);
      return t(pct > 0 ? 'today.contrib.hrvAbove' : 'today.contrib.hrvBelow', args);
    }
    case 'rhr_dev':
      if (v == null || Math.abs(v) < 0.5) return t('today.contrib.rhrSame');
      return t(v > 0 ? 'today.contrib.rhrAbove' : 'today.contrib.rhrBelow', { v: Math.round(Math.abs(v)) });
    case 'resp_dev':
      if (v == null || Math.abs(v) < 0.05) return t('today.contrib.respSame');
      return t(v > 0 ? 'today.contrib.respAbove' : 'today.contrib.respBelow', { v: one(v) });
    case 'sleep_perf':
      return t('today.contrib.sleep', { v: v == null ? '–' : Math.round(v) });
    case 'prev_strain':
      return c.typical == null
        ? t('today.contrib.prevStrain', { v: v == null ? '–' : v.toFixed(1) })
        : t('today.contrib.prevStrainTypical', { v: v == null ? '–' : v.toFixed(1), typical: c.typical.toFixed(1) });
    case 'sleep_spo2':
      return t('today.contrib.spo2', { v: v == null ? '–' : v.toFixed(1) });
    case 'body_battery_high':
      return t('today.contrib.bodyBattery', { v: v == null ? '–' : Math.round(v) });
    default:
      return c.feature;
  }
}

/** Why recovery is missing, e.g. "Yesterday's strain is unknown: the watch recorded no all-day heart rate yesterday." */
export function describeReason(r: TodayReason, t: TFunction): string {
  const main = t(`today.reason.${r.reason}`, { defaultValue: r.reason });
  if (r.reason === 'NO_STRAIN_YESTERDAY' && r.detail) {
    return t('today.reasonWithDetail', { main, detail: t(`today.yesterday.${r.detail}`, { defaultValue: r.detail }) });
  }
  return main;
}
