import type { TFunction } from 'i18next';
import type { Checkin, CheckinInput, CheckinOptions } from './api/types';

// Pure helpers for the Check-in form (stage 2-1b, ADR 0020): the form state, what is sent, what is prefilled.

/** What the athlete is answering about: the whole day, one activity or one planned session. */
export type Scope = { kind: 'day' } | { kind: 'activity'; id: number } | { kind: 'session'; id: number };

export interface FormState {
  rpe: number | null;
  soreness: number | null;
  niggleLocation: string | null;
  niggleSeverity: number | null;
  niggleResolved: boolean;
  injury: boolean;
  illness: boolean;
  note: string;
}

export const EMPTY_FORM: FormState = {
  rpe: null,
  soreness: null,
  niggleLocation: null,
  niggleSeverity: null,
  niggleResolved: false,
  injury: false,
  illness: false,
  note: '',
};

export const NOTE_MAX = 500;

export function sameScope(a: Scope, b: Scope): boolean {
  return a.kind === b.kind && (a.kind === 'day' || a.id === (b as { id: number }).id);
}

/** True when a stored check-in is about this scope. */
export function isForScope(c: Checkin, s: Scope): boolean {
  if (s.kind === 'session') return c.plannedSessionId === s.id;
  if (s.kind === 'activity') return c.plannedSessionId == null && c.activityId === s.id;
  return c.plannedSessionId == null && c.activityId == null;
}

/** The answers already given for this scope today (the one that counts), so a correction starts from them. */
export function prefill(options: CheckinOptions | undefined, scope: Scope): { form: FormState; existing: Checkin | null } {
  const existing = options?.current.find((c) => isForScope(c, scope)) ?? null;
  if (!existing) return { form: EMPTY_FORM, existing: null };
  return {
    existing,
    form: {
      rpe: existing.rpe,
      soreness: existing.soreness,
      niggleLocation: existing.niggleLocation,
      niggleSeverity: existing.niggleSeverity,
      niggleResolved: existing.niggleResolved,
      injury: existing.injury,
      illness: existing.illness,
      note: existing.note ?? '',
    },
  };
}

/** A niggle's severity and "it's gone" answer only make sense with a location; this keeps the form coherent. */
export function setLocation(f: FormState, location: string | null): FormState {
  if (location === null) return { ...f, niggleLocation: null, niggleSeverity: null, niggleResolved: false };
  return { ...f, niggleLocation: location };
}

export function setSeverity(f: FormState, severity: number | null): FormState {
  return { ...f, niggleSeverity: severity, niggleResolved: severity !== null ? false : f.niggleResolved };
}

export function setResolved(f: FormState, resolved: boolean): FormState {
  return { ...f, niggleResolved: resolved, niggleSeverity: resolved ? null : f.niggleSeverity };
}

/** Something has been answered (the server refuses an empty check-in). */
export function hasAnswer(f: FormState): boolean {
  return (
    f.rpe !== null || f.soreness !== null || f.niggleLocation !== null || f.injury || f.illness || f.note.trim() !== ''
  );
}

export function toInput(f: FormState, scope: Scope, date?: string): CheckinInput {
  const note = f.note.trim();
  return {
    ...(scope.kind === 'day' && date ? { date } : {}),
    ...(scope.kind === 'session' ? { plannedSessionId: scope.id } : {}),
    ...(scope.kind === 'activity' ? { activityId: scope.id } : {}),
    ...(f.rpe !== null ? { rpe: f.rpe } : {}),
    ...(f.soreness !== null ? { soreness: f.soreness } : {}),
    ...(f.niggleLocation !== null ? { niggleLocation: f.niggleLocation } : {}),
    ...(f.niggleSeverity !== null ? { niggleSeverity: f.niggleSeverity } : {}),
    niggleResolved: f.niggleResolved,
    injury: f.injury,
    illness: f.illness,
    ...(note ? { note } : {}),
  };
}

/** Days between two ISO dates (b - a). */
export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86400000);
}

/** The ISO date n days before iso. */
export function minusDays(iso: string, n: number): string {
  return new Date(Date.parse(`${iso}T00:00:00Z`) - n * 86400000).toISOString().slice(0, 10);
}

/** The answers of one check-in in a line of plain words. */
export function summarise(c: Checkin, t: TFunction): string {
  const parts: string[] = [];
  if (c.rpe != null) parts.push(t('checkin.sumRpe', { n: c.rpe }));
  if (c.soreness != null) parts.push(t('checkin.sumSoreness', { n: c.soreness }));
  if (c.niggleLocation) {
    const where = t(`checkin.location.${c.niggleLocation}`, { defaultValue: c.niggleLocation });
    parts.push(
      c.niggleResolved
        ? t('checkin.sumNiggleGone', { where })
        : c.niggleSeverity != null
          ? t('checkin.sumNiggle', { where, n: c.niggleSeverity })
          : t('checkin.sumNiggleNoSeverity', { where }),
    );
  }
  if (c.injury) parts.push(t('checkin.injury'));
  if (c.illness) parts.push(t('checkin.illness'));
  return parts.join(' · ') || '–';
}
