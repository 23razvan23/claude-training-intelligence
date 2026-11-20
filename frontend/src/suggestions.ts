// Pure helpers for the engine's suggestions (stage 2-3, ADR 0022): plain summaries and the coach's edited version.
import type { PlannedSession, SessionChange, Suggestion, SuggestionReason } from './api/types';
import { formatSessionDay } from './plan';

type T = (key: string, options?: Record<string, unknown>) => string;

/** A session's name: its title, or its type's name. */
export function sessionName(s: { title?: string | null; sessionType: string }, t: T): string {
  return s.title?.trim() ? s.title : t(`plan.type.${s.sessionType}`);
}

/** "Wed 18 Nov · Threshold / tempo · 10 km · 60 min" */
export function describeSession(s: PlannedSession | SessionChange, t: T, lang: string): string {
  const parts = [formatSessionDay(s.date, lang), t(`plan.type.${s.sessionType}`)];
  if (s.targetDistanceKm != null) parts.push(t('plan.km', { value: s.targetDistanceKm }));
  if (s.targetDurationMin != null) parts.push(t('plan.min', { value: s.targetDurationMin }));
  return parts.join(' · ');
}

/** A reason in the current language: a translation of its code with its numbers, else the server's English text. */
export function reasonText(r: SuggestionReason, t: T): string {
  return t(`suggestions.reason.${r.code}`, { ...(r.params ?? {}), defaultValue: r.text });
}

/** The fields the coach may change before accepting. */
export interface EditFields {
  date: string;
  sessionType: string;
  km: string;
  min: string;
  description: string;
  counterpartDate: string;
}

export function editFieldsOf(s: Suggestion): EditFields {
  const a = s.after.session;
  return {
    date: a.date,
    sessionType: a.sessionType,
    km: a.targetDistanceKm != null ? String(a.targetDistanceKm) : '',
    min: a.targetDurationMin != null ? String(a.targetDurationMin) : '',
    description: a.description ?? '',
    counterpartDate: s.after.counterpart?.date ?? '',
  };
}

function num(v: string): number | null {
  const x = v.trim().replace(',', '.');
  if (x === '') return null;
  const n = Number(x);
  return Number.isFinite(n) ? n : null;
}

/** The suggestion's "after" with the coach's changes; everything else stays as suggested. */
export function editedAfter(s: Suggestion, f: EditFields): Suggestion['after'] {
  const session: SessionChange = {
    ...s.after.session,
    date: f.date,
    sessionType: f.sessionType,
    targetDistanceKm: num(f.km),
    targetDurationMin: num(f.min),
    description: f.description,
  };
  return s.after.counterpart ? { session, counterpart: { ...s.after.counterpart, date: f.counterpartDate } } : { session };
}
