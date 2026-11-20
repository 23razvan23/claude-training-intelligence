import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useAthletes,
  useDeleteSession,
  useMe,
  usePendingSuggestions,
  usePlans,
  useSavePlan,
  useSaveSession,
  useSessionRevisions,
  useSessions,
  useSessionTypes,
} from '../api/hooks';
import type { Demand, Plan, PlannedSession, SessionInput, SessionStatus, Suggestion } from '../api/types';
import { PlanImport } from '../components/PlanImport';
import { SessionSuggestions, SuggestionCard } from '../components/Suggestions';
import { useAthleteSelection } from '../athleteContext';
import { addDays, formatSessionDay, groupByWeek, parseNumber, shownRange, todayIn } from '../plan';

// The Plan screen (stage 2-1a, ADR 0019): upcoming sessions by week. The coach edits; an athlete reads their own.

const DEMANDS: Demand[] = ['KEY', 'SUPPORTING', 'EASY'];
const STATUSES: SessionStatus[] = ['PLANNED', 'DONE', 'SKIPPED', 'REPLACED'];

export function PlanPage() {
  const { t, i18n } = useTranslation();
  const { athleteId } = useAthleteSelection();
  const { data: me } = useMe();
  const { data: athletes } = useAthletes();
  const timezone = athletes?.find((a) => a.id === athleteId)?.timezone;
  const today = todayIn(timezone);
  const { from, to } = shownRange(today);
  const plans = usePlans(athleteId);
  const sessions = useSessions(athleteId, from, to);
  const pending = usePendingSuggestions(athleteId, today, to);
  const [editing, setEditing] = useState<PlannedSession | 'new' | null>(null);
  const [importing, setImporting] = useState(false);
  const [planForm, setPlanForm] = useState<Plan | 'new' | null>(null);
  const isCoach = me?.role === 'COACH';

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  if (plans.isLoading || sessions.isLoading) return <p className="muted">{t('common.loading')}</p>;
  const error = plans.error ?? sessions.error;
  if (error) return <p className="error">{t('common.error', { message: error.message })}</p>;

  const active = plans.data?.find((p) => p.status === 'ACTIVE');
  const weeks = groupByWeek(sessions.data ?? [], from, to);
  // A pending suggestion is shown on the session it changes (and on a swap's partner).
  const bySession = new Map<number, Suggestion>();
  for (const s of pending.data ?? []) {
    if (s.plannedSessionId != null) bySession.set(s.plannedSessionId, s);
    if (s.counterpartSessionId != null && !bySession.has(s.counterpartSessionId)) bySession.set(s.counterpartSessionId, s);
  }

  return (
    <section className="stack plan">
      <div className="row wrap">
        <h1>{t('plan.title')}</h1>
        <span className="spacer" />
        {isCoach && active && (
          <button onClick={() => setEditing('new')} disabled={editing === 'new'}>
            {t('plan.addSession')}
          </button>
        )}
        {isCoach && active && (
          <button className="ghost" onClick={() => setImporting(true)} disabled={importing}>
            {t('plan.import.button')}
          </button>
        )}
      </div>

      {active ? (
        <div className="row wrap small">
          <strong>{active.name}</strong>
          <span className="muted">
            {formatSessionDay(active.startDate, i18n.language)} – {formatSessionDay(active.endDate, i18n.language)}
          </span>
          <span className="spacer" />
          {isCoach && (
            <button className="ghost" onClick={() => setPlanForm(active)}>
              {t('plan.editPlan')}
            </button>
          )}
        </div>
      ) : (
        <div className="card stack">
          <p className="muted">{isCoach ? t('plan.noPlanCoach') : t('plan.noPlanAthlete')}</p>
          {isCoach && !planForm && (
            <div>
              <button onClick={() => setPlanForm('new')}>{t('plan.newPlan')}</button>
            </div>
          )}
        </div>
      )}

      {isCoach && planForm && (
        <PlanForm
          key={planForm === 'new' ? 'new' : planForm.id}
          athleteId={athleteId}
          plan={planForm === 'new' ? undefined : planForm}
          today={today}
          onDone={() => setPlanForm(null)}
        />
      )}

      {isCoach && active && importing && <PlanImport athleteId={athleteId} onDone={() => setImporting(false)} />}

      {isCoach && editing === 'new' && active && (
        <SessionForm athleteId={athleteId} defaultDate={today < from ? from : today} onDone={() => setEditing(null)} />
      )}

      {!isCoach && <p className="muted small">{t('plan.readOnly')}</p>}

      {weeks.map((w) => (
        <div key={w.monday} className="stack week-block">
          <h2 className="week-head">
            <span>{t('plan.weekOf', { date: formatSessionDay(w.monday, i18n.language) })}</span>
            <span className="muted small">
              {t('plan.weekSummary', { count: w.sessions.length, km: w.km, key: w.keyCount })}
            </span>
          </h2>
          {w.sessions.length === 0 && <p className="muted small">{t('plan.emptyWeek')}</p>}
          <ul className="list">
            {w.sessions.map((s) =>
              isCoach && editing !== 'new' && editing?.id === s.id ? (
                <li key={s.id}>
                  <SessionForm athleteId={athleteId} session={s} defaultDate={s.date} onDone={() => setEditing(null)} />
                </li>
              ) : (
                <SessionCard
                  key={s.id}
                  s={s}
                  past={s.date < today}
                  suggestion={bySession.get(s.id)}
                  isCoach={isCoach}
                  onEdit={isCoach ? () => setEditing(s) : undefined}
                />
              ),
            )}
          </ul>
        </div>
      ))}
    </section>
  );
}

function SessionCard({
  s,
  past,
  suggestion,
  isCoach,
  onEdit,
}: {
  s: PlannedSession;
  past: boolean;
  suggestion?: Suggestion;
  isCoach: boolean;
  onEdit?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const [showSuggestions, setShowSuggestions] = useState(false);
  const struck = s.status === 'SKIPPED' || s.status === 'REPLACED';
  const facts = [
    s.targetDistanceKm != null && t('plan.km', { value: s.targetDistanceKm }),
    s.targetDurationMin != null && t('plan.min', { value: s.targetDurationMin }),
    s.estStrain != null && t('plan.strain', { value: s.estStrain }),
  ].filter(Boolean);
  return (
    <li className={`card session demand-${s.demand.toLowerCase()}${past ? ' past-day' : ''}`}>
      <div className="row wrap">
        <span className="session-day">{formatSessionDay(s.date, i18n.language)}</span>
        <span className={`badge demand ${s.demand.toLowerCase()}`}>{t(`plan.demand.${s.demand}`)}</span>
        {s.status !== 'PLANNED' && <span className={`badge status ${s.status.toLowerCase()}`}>{t(`plan.status.${s.status}`)}</span>}
        {s.taper && <span className="badge taper">{t('plan.taper')}</span>}
        {suggestion && (
          <span className="badge pending" data-testid="suggestion-badge">
            {t('suggestions.pendingBadge')}
          </span>
        )}
        <span className="spacer" />
        {onEdit && (
          <button className="ghost" onClick={onEdit}>
            {t('common.edit')}
          </button>
        )}
      </div>
      <div className={struck ? 'struck' : undefined}>
        <strong>{s.title || t(`plan.type.${s.sessionType}`)}</strong>
        {s.title && <span className="muted small"> · {t(`plan.type.${s.sessionType}`)}</span>}
      </div>
      {facts.length > 0 && <div className="small">{facts.join(' · ')}</div>}
      {s.description && <div className="muted small pre">{s.description}</div>}
      {s.notes && <div className="muted small pre">{s.notes}</div>}
      {suggestion && !showSuggestions && <SuggestionCard s={suggestion} isCoach={isCoach} compact />}
      <div>
        <button className="link small" onClick={() => setShowSuggestions((v) => !v)} aria-expanded={showSuggestions}>
          {t('suggestions.history')}
        </button>
      </div>
      {showSuggestions && <SessionSuggestions sessionId={s.id} isCoach={isCoach} />}
    </li>
  );
}

interface SessionFields {
  date: string;
  sessionType: string;
  demand: Demand;
  demandTouched: boolean;
  title: string;
  description: string;
  km: string;
  min: string;
  strain: string;
  status: SessionStatus;
  notes: string;
  taper: boolean;
}

function fieldsOf(s: PlannedSession | undefined, date: string, defaultDemand: (type: string) => Demand): SessionFields {
  return {
    date: s?.date ?? date,
    sessionType: s?.sessionType ?? 'easy',
    demand: s?.demand ?? defaultDemand('easy'),
    demandTouched: s != null,
    title: s?.title ?? '',
    description: s?.description ?? '',
    km: s?.targetDistanceKm != null ? String(s.targetDistanceKm) : '',
    min: s?.targetDurationMin != null ? String(s.targetDurationMin) : '',
    strain: s?.estStrain != null ? String(s.estStrain) : '',
    status: s?.status ?? 'PLANNED',
    notes: s?.notes ?? '',
    taper: s?.taper ?? false,
  };
}

function SessionForm({
  athleteId,
  session,
  defaultDate,
  onDone,
}: {
  athleteId: number;
  session?: PlannedSession;
  defaultDate: string;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const types = useSessionTypes();
  const save = useSaveSession(athleteId);
  const del = useDeleteSession();
  const defaultDemand = (code: string): Demand => types.data?.find((x) => x.code === code)?.defaultDemand ?? 'EASY';
  const [f, setF] = useState<SessionFields>(() => fieldsOf(session, defaultDate, defaultDemand));
  const [showHistory, setShowHistory] = useState(false);
  const set = <K extends keyof SessionFields>(k: K, v: SessionFields[K]) => setF((x) => ({ ...x, [k]: v }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const input: SessionInput = {
      planId: session?.planId ?? undefined,
      date: f.date,
      sessionType: f.sessionType,
      demand: f.demand,
      title: f.title,
      description: f.description,
      targetDistanceKm: parseNumber(f.km),
      targetDurationMin: parseNumber(f.min),
      estStrain: parseNumber(f.strain),
      status: f.status,
      notes: f.notes,
      taper: f.taper,
    };
    save.mutate({ id: session?.id, input }, { onSuccess: onDone });
  };

  const markStatus = (status: SessionStatus) => set('status', status);

  const remove = () => {
    if (session && window.confirm(t('plan.confirmDelete'))) del.mutate(session.id, { onSuccess: onDone });
  };

  return (
    <form className="card stack session-form" onSubmit={submit} aria-label={session ? t('plan.editSession') : t('plan.addSession')}>
      <strong>{session ? t('plan.editSession') : t('plan.addSession')}</strong>
      <div className="grid">
        <label>
          {t('plan.field.date')}
          <input type="date" value={f.date} onChange={(e) => set('date', e.target.value)} required />
        </label>
        <label>
          {t('plan.field.type')}
          <select
            value={f.sessionType}
            onChange={(e) => {
              const code = e.target.value;
              setF((x) => ({ ...x, sessionType: code, demand: x.demandTouched ? x.demand : defaultDemand(code) }));
            }}
          >
            {(types.data ?? [{ code: f.sessionType, defaultDemand: f.demand }]).map((x) => (
              <option key={x.code} value={x.code}>
                {t(`plan.type.${x.code}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('plan.field.demand')}
          <select value={f.demand} onChange={(e) => setF((x) => ({ ...x, demand: e.target.value as Demand, demandTouched: true }))}>
            {DEMANDS.map((d) => (
              <option key={d} value={d}>
                {t(`plan.demand.${d}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('plan.field.title')}
          <input value={f.title} onChange={(e) => set('title', e.target.value)} maxLength={200} placeholder={t(`plan.type.${f.sessionType}`)} />
        </label>
      </div>
      <label>
        {t('plan.field.description')}
        <textarea value={f.description} onChange={(e) => set('description', e.target.value)} maxLength={2000} rows={3} placeholder={t('plan.field.descriptionHint')} />
      </label>
      <div className="grid">
        <label>
          {t('plan.field.km')}
          <input inputMode="decimal" value={f.km} onChange={(e) => set('km', e.target.value)} />
        </label>
        <label>
          {t('plan.field.min')}
          <input inputMode="numeric" value={f.min} onChange={(e) => set('min', e.target.value)} />
        </label>
        <label>
          {t('plan.field.strain')}
          <input inputMode="decimal" value={f.strain} onChange={(e) => set('strain', e.target.value)} placeholder={t('plan.field.strainHint')} />
        </label>
        {session && (
          <label>
            {t('plan.field.status')}
            <select value={f.status} onChange={(e) => markStatus(e.target.value as SessionStatus)}>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {t(`plan.status.${s}`)}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      <label className="check">
        <input type="checkbox" checked={f.taper} onChange={(e) => set('taper', e.target.checked)} />
        {t('plan.field.taper')}
      </label>
      <label>
        {t('plan.field.notes')}
        <textarea value={f.notes} onChange={(e) => set('notes', e.target.value)} maxLength={2000} rows={2} />
      </label>
      {save.error && <p className="error">{save.error.message}</p>}
      {del.error && <p className="error">{del.error.message}</p>}
      <div className="row wrap">
        <button type="submit" disabled={save.isPending}>
          {t('common.save')}
        </button>
        <button type="button" className="ghost" onClick={onDone}>
          {t('common.cancel')}
        </button>
        <span className="spacer" />
        {session && (
          <>
            <button type="button" className="ghost" onClick={() => setShowHistory((v) => !v)}>
              {t('plan.history')}
            </button>
            <button type="button" className="ghost danger" onClick={remove}>
              {t('common.delete')}
            </button>
          </>
        )}
      </div>
      {session && <p className="muted small">{t('plan.preferSkip')}</p>}
      {session && showHistory && <History sessionId={session.id} />}
    </form>
  );
}

function History({ sessionId }: { sessionId: number }) {
  const { t, i18n } = useTranslation();
  const { data, isLoading } = useSessionRevisions(sessionId);
  if (isLoading) return <p className="muted small">{t('common.loading')}</p>;
  return (
    <ol className="history small">
      {data?.map((r) => (
        <li key={r.id}>
          {new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(r.changedAt))}
          {' · '}
          {t(`plan.change.${r.changeKind}`)}
          {r.changedBy ? ` · ${r.changedBy}` : ''}
          {r.changeKind === 'EDIT' && r.before && r.after && <span className="muted"> · {changedFields(r.before, r.after, t).join(', ')}</span>}
        </li>
      ))}
    </ol>
  );
}

const COMPARED: (keyof PlannedSession)[] = [
  'date', 'sessionType', 'demand', 'title', 'description', 'targetDistanceKm', 'targetDurationMin', 'estStrain', 'status', 'notes', 'taper',
];

function changedFields(before: PlannedSession, after: PlannedSession, t: (k: string) => string): string[] {
  return COMPARED.filter((k) => (before[k] ?? null) !== (after[k] ?? null)).map((k) => t(`plan.changed.${k}`));
}

function PlanForm({ athleteId, plan, today, onDone }: { athleteId: number; plan?: Plan; today: string; onDone: () => void }) {
  const { t } = useTranslation();
  const save = useSavePlan(athleteId);
  const [name, setName] = useState(plan?.name ?? '');
  const [start, setStart] = useState(plan?.startDate ?? today);
  const [end, setEnd] = useState(plan?.endDate ?? addDays(today, 27));

  const submit = (e: FormEvent, status?: Plan['status']) => {
    e.preventDefault();
    save.mutate({ id: plan?.id, input: { name, startDate: start, endDate: end, status: status ?? plan?.status } }, { onSuccess: onDone });
  };

  return (
    <form className="card stack" onSubmit={(e) => submit(e)} aria-label={plan ? t('plan.editPlan') : t('plan.newPlan')}>
      <div className="grid">
        <label>
          {t('plan.field.planName')}
          <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />
        </label>
        <label>
          {t('plan.field.start')}
          <input type="date" value={start} onChange={(e) => setStart(e.target.value)} required />
        </label>
        <label>
          {t('plan.field.end')}
          <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} required />
        </label>
      </div>
      {save.error && <p className="error">{save.error.message}</p>}
      <div className="row wrap">
        <button type="submit" disabled={save.isPending}>
          {t('common.save')}
        </button>
        <button type="button" className="ghost" onClick={onDone}>
          {t('common.cancel')}
        </button>
        <span className="spacer" />
        {plan && (
          <button type="button" className="ghost" onClick={(e) => submit(e, 'COMPLETED')}>
            {t('plan.completePlan')}
          </button>
        )}
      </div>
    </form>
  );
}
