import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Link } from 'react-router-dom';
import {
  useAllRecentCheckins,
  useAthletes,
  useCheckinOptions,
  useCreateCheckin,
  useMe,
  useRecentCheckins,
} from '../api/hooks';
import type { Checkin, CheckinOptions } from '../api/types';
import { useAthleteSelection } from '../athleteContext';
import {
  EMPTY_FORM,
  NOTE_MAX,
  hasAnswer,
  minusDays,
  prefill,
  sameScope,
  setLocation,
  setResolved,
  setSeverity,
  summarise,
  toInput,
  type FormState,
  type Scope,
} from '../checkin';
import { formatDay } from '../today';

// The 10-second check-in (stage 2-1b, ADR 0019). Every save adds a row; the newest answer for a day and scope counts.

const RPE = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const FIVE = [1, 2, 3, 4, 5];

function Choices({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <fieldset className="checkin-q">
      <legend>{label}</legend>
      {hint && <p className="muted small">{hint}</p>}
      {children}
    </fieldset>
  );
}

function NumberPad({
  values,
  value,
  onChange,
  label,
}: {
  values: number[];
  value: number | null;
  onChange: (v: number | null) => void;
  label: string;
}) {
  return (
    <div className="pad" role="group" aria-label={label}>
      {values.map((v) => (
        <button
          key={v}
          type="button"
          className={value === v ? 'pick on' : 'pick'}
          aria-pressed={value === v}
          onClick={() => onChange(value === v ? null : v)}
        >
          {v}
        </button>
      ))}
    </div>
  );
}

export function CheckinPage() {
  const { t } = useTranslation();
  const { athleteId } = useAthleteSelection();
  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  return <CheckinScreen athleteId={athleteId} />;
}

function CheckinScreen({ athleteId }: { athleteId: number }) {
  const { t, i18n } = useTranslation();
  const { data: me } = useMe();
  const { data: athletes } = useAthletes();
  const [date, setDate] = useState<string | undefined>(undefined);
  const [scope, setScope] = useState<Scope>({ kind: 'day' });
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saved, setSaved] = useState(false);
  const { data: options, error } = useCheckinOptions(athleteId, date);
  const create = useCreateCheckin(athleteId);
  const isCoach = me?.role === 'COACH';
  const onBehalf = isCoach && me?.athleteId !== athleteId;
  const athleteName = athletes?.find((a) => a.id === athleteId)?.name ?? '';

  // Start from the answer that already counts for this scope, so a correction keeps what was right.
  const { form: stored, existing } = prefill(options, scope);
  const storedId = existing?.id ?? null;
  useEffect(() => {
    setForm(stored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storedId, scope.kind, 'id' in scope ? scope.id : null, options?.date]);

  // A new athlete or day is a fresh form.
  useEffect(() => {
    setScope({ kind: 'day' });
    setSaved(false);
  }, [athleteId, date]);

  if (error) return <p className="warn">{t('common.error', { message: error.message })}</p>;
  if (!options) return <p className="muted">{t('common.loading')}</p>;

  const day = (iso: string) => formatDay(iso, i18n.language);
  const oldest = minusDays(options.today, options.maxBackdateDays);
  const pick = (s: Scope) => {
    setScope(s);
    setSaved(false);
  };
  const edit = (f: FormState) => {
    setForm(f);
    setSaved(false);
  };
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    create.mutate(toInput(form, scope, options.date), { onSuccess: () => setSaved(true) });
  };

  return (
    <section className="stack checkin" data-testid="checkin-page">
      <div className="row wrap">
        <h1>{t('checkin.title')}</h1>
        <span className="muted">
          <time dateTime={options.date}>{day(options.date)}</time>
        </span>
      </div>

      {onBehalf && (
        <p className="banner" role="status" data-testid="behalf-banner">
          {t('checkin.onBehalf', { name: athleteName })}
        </p>
      )}

      <form className="stack" onSubmit={submit}>
        <Choices label={t('checkin.about')}>
          <div className="chips">
            <button type="button" className={scope.kind === 'day' ? 'pick on' : 'pick'} aria-pressed={scope.kind === 'day'} onClick={() => pick({ kind: 'day' })}>
              {t('checkin.wholeDay')}
            </button>
            {options.sessions.map((s) => {
              const sc: Scope = { kind: 'session', id: s.id };
              return (
                <button key={`s${s.id}`} type="button" className={sameScope(scope, sc) ? 'pick on' : 'pick'} aria-pressed={sameScope(scope, sc)} onClick={() => pick(sc)}>
                  {t('checkin.plannedSession', { title: s.title })}
                </button>
              );
            })}
            {options.activities.map((a) => {
              const sc: Scope = { kind: 'activity', id: a.id };
              return (
                <button key={`a${a.id}`} type="button" className={sameScope(scope, sc) ? 'pick on' : 'pick'} aria-pressed={sameScope(scope, sc)} onClick={() => pick(sc)}>
                  {activityLabel(a, options, t, i18n.language)}
                </button>
              );
            })}
          </div>
          <label className="date-pick">
            {t('checkin.day')}
            <input
              type="date"
              value={options.date}
              min={oldest}
              max={options.today}
              onChange={(e) => setDate(e.target.value && e.target.value !== options.today ? e.target.value : undefined)}
            />
          </label>
        </Choices>

        {existing && (
          <p className="muted small" data-testid="existing-note">
            {t('checkin.updating', { time: timeOf(existing.createdAt, options.timezone, i18n.language) })}
          </p>
        )}

        <Choices label={t('checkin.rpe')} hint={t(scope.kind === 'day' ? 'checkin.rpeDayHint' : 'checkin.rpeHint')}>
          <NumberPad label={t('checkin.rpe')} values={RPE} value={form.rpe} onChange={(v) => edit({ ...form, rpe: v })} />
          <div className="row scale small muted">
            <span>{t('checkin.rpeLow')}</span>
            <span className="spacer" />
            <span>{t('checkin.rpeHigh')}</span>
          </div>
        </Choices>

        <Choices label={t('checkin.soreness')}>
          <NumberPad label={t('checkin.soreness')} values={FIVE} value={form.soreness} onChange={(v) => edit({ ...form, soreness: v })} />
          <div className="row scale small muted">
            <span>{t('checkin.sorenessLow')}</span>
            <span className="spacer" />
            <span>{t('checkin.sorenessHigh')}</span>
          </div>
        </Choices>

        <Choices label={t('checkin.niggle')} hint={t('checkin.niggleHint')}>
          <div className="chips" role="group" aria-label={t('checkin.niggle')}>
            {options.locations.map((l) => (
              <button
                key={l}
                type="button"
                className={form.niggleLocation === l ? 'pick on' : 'pick'}
                aria-pressed={form.niggleLocation === l}
                onClick={() => edit(setLocation(form, form.niggleLocation === l ? null : l))}
              >
                {t(`checkin.location.${l}`, { defaultValue: l })}
              </button>
            ))}
          </div>
          {form.niggleLocation && (
            <div className="stack niggle-detail">
              <NumberPad
                label={t('checkin.severity')}
                values={FIVE}
                value={form.niggleSeverity}
                onChange={(v) => edit(setSeverity(form, v))}
              />
              <div className="row scale small muted">
                <span>{t('checkin.severityLow')}</span>
                <span className="spacer" />
                <span>{t('checkin.severityHigh')}</span>
              </div>
              <button
                type="button"
                className={form.niggleResolved ? 'pick on' : 'pick'}
                aria-pressed={form.niggleResolved}
                onClick={() => edit(setResolved(form, !form.niggleResolved))}
              >
                {t('checkin.resolved')}
              </button>
            </div>
          )}
        </Choices>

        <Choices label={t('checkin.health')}>
          <div className="chips">
            <button type="button" className={form.injury ? 'pick on danger-pick' : 'pick'} aria-pressed={form.injury} onClick={() => edit({ ...form, injury: !form.injury })}>
              {t('checkin.injury')}
            </button>
            <button type="button" className={form.illness ? 'pick on danger-pick' : 'pick'} aria-pressed={form.illness} onClick={() => edit({ ...form, illness: !form.illness })}>
              {t('checkin.illness')}
            </button>
          </div>
        </Choices>

        <label>
          {t('checkin.note')}
          <textarea rows={2} maxLength={NOTE_MAX} value={form.note} onChange={(e) => edit({ ...form, note: e.target.value })} />
        </label>

        {create.error && <p className="error" role="alert">{t('common.error', { message: create.error.message })}</p>}
        {saved && (
          <p className="ok" role="status" data-testid="saved">
            {t('checkin.saved')} <Link to="/today">{t('checkin.backToToday')}</Link>
          </p>
        )}
        <button type="submit" className="save" disabled={!hasAnswer(form) || create.isPending}>
          {t(existing ? 'checkin.saveCorrection' : 'checkin.save')}
        </button>
      </form>

      <Recent athleteId={athleteId} timezone={options.timezone} />
      {isCoach && <Everyone timezone={options.timezone} />}
    </section>
  );
}

function activityLabel(a: CheckinOptions['activities'][number], o: CheckinOptions, t: TFunction, lang: string) {
  const sport = t(`checkin.sport.${a.sport}`, { defaultValue: a.sport });
  const km = a.distanceM ? ` · ${(a.distanceM / 1000).toFixed(1)} km` : '';
  return `${sport} ${timeOf(a.startAt, o.timezone, lang)}${km}`;
}

function timeOf(iso: string, timeZone: string, lang: string): string {
  try {
    return new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit', timeZone }).format(new Date(iso));
  } catch {
    return iso.slice(11, 16);
  }
}

function Recent({ athleteId, timezone }: { athleteId: number; timezone: string }) {
  const { t } = useTranslation();
  const { data } = useRecentCheckins(athleteId);
  return (
    <div className="stack">
      <h2>{t('checkin.recent')}</h2>
      {data && data.length === 0 && <p className="muted">{t('checkin.none')}</p>}
      {data && <CheckinList items={data} timezone={timezone} />}
    </div>
  );
}

function Everyone({ timezone }: { timezone: string }) {
  const { t } = useTranslation();
  const { data } = useAllRecentCheckins(true);
  return (
    <div className="stack" data-testid="everyone">
      <h2>{t('checkin.everyone')}</h2>
      {data && data.length === 0 && <p className="muted">{t('checkin.none')}</p>}
      {data && <CheckinList items={data} timezone={timezone} showAthlete />}
    </div>
  );
}

function CheckinList({ items, timezone, showAthlete }: { items: Checkin[]; timezone: string; showAthlete?: boolean }) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="list checkin-list">
      {items.map((c) => (
        <li key={c.id} className="card" data-testid="checkin-row">
          <div className="row wrap">
            <strong>{formatDay(c.date, i18n.language)}</strong>
            <span className="muted small">{t(`checkin.scope.${c.scope}`)}</span>
            {showAthlete && <span className="badge">{c.athleteName}</span>}
            <span className="spacer" />
            {c.onBehalf && <span className="badge">{t('checkin.byCoach')}</span>}
            {c.source === 'GARMIN_SEED' && <span className="badge">{t('checkin.seeded')}</span>}
            <span className="muted small">{timeOf(c.createdAt, timezone, i18n.language)}</span>
          </div>
          <p className="summary">{summarise(c, t)}</p>
          {c.note && <p className="muted small note-text">{c.note}</p>}
        </li>
      ))}
    </ul>
  );
}
