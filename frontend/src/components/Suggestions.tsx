import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useDecideSuggestion, useRunSuggestions, useSessionSuggestions, useSessionTypes, useSuggestionsToday } from '../api/hooks';
import type { Suggestion } from '../api/types';
import { formatSessionDay } from '../plan';
import { describeSession, editedAfter, editFieldsOf, reasonText, sessionName, type EditFields } from '../suggestions';

// The engine's suggestions (stage 2-3, ADR 0022): one card per suggestion with its reasons and one-tap
// accept / edit / reject for the coach. Every decision is stored on the suggestion; accepting changes the plan.

/** Today: the health messages and the pending suggestions of the next 7 days, with "Check the plan now" for the coach. */
export function SuggestionsPanel({ athleteId, isCoach }: { athleteId: number; isCoach: boolean }) {
  const { t } = useTranslation();
  const { data, error, isLoading } = useSuggestionsToday(athleteId);
  const run = useRunSuggestions(athleteId);

  return (
    <section className="stack suggestions" aria-label={t('suggestions.title')} data-testid="suggestions">
      <div className="row wrap">
        <h2>{t('suggestions.title')}</h2>
        <span className="spacer" />
        {isCoach && (
          <button className="ghost" onClick={() => run.mutate()} disabled={run.isPending} data-testid="run-engine">
            {run.isPending ? t('suggestions.running') : t('suggestions.runNow')}
          </button>
        )}
      </div>
      {run.data && <p className="muted small" role="status">{t('suggestions.ran', { created: run.data.created, expired: run.data.expired })}</p>}
      {run.error && <p className="error">{run.error.message}</p>}
      {isLoading && <p className="muted small">{t('common.loading')}</p>}
      {error && <p className="warn">{t('common.error', { message: error.message })}</p>}
      {data?.healthMessages.map((h) => (
        <div key={h.code + h.text} className="banner health" role="note" data-testid="health-message">
          <strong>{t('suggestions.health')}</strong>
          <span>{reasonText(h, t)}</span>
        </div>
      ))}
      {data && data.pending.length === 0 && <p className="card muted small">{t('suggestions.none')}</p>}
      {data?.pending.map((s) => <SuggestionCard key={s.id} s={s} isCoach={isCoach} />)}
      {data && data.pending.length > 0 && !isCoach && <p className="muted small">{t('suggestions.readOnly')}</p>}
    </section>
  );
}

export function SuggestionCard({ s, isCoach, compact = false }: { s: Suggestion; isCoach: boolean; compact?: boolean }) {
  const { t, i18n } = useTranslation();
  const decide = useDecideSuggestion();
  const [mode, setMode] = useState<'view' | 'edit' | 'reject'>('view');
  const [note, setNote] = useState('');
  const before = s.before.session;
  const counterpart = s.before.counterpart;
  const pending = s.status === 'PENDING';

  return (
    <article className={`card stack suggestion action-${s.action.toLowerCase()}`} data-testid="suggestion" data-action={s.action}>
      <div className="row wrap">
        <span className={`badge action ${s.action.toLowerCase()}`}>{t(`suggestions.action.${s.action}`)}</span>
        <strong>{sessionName(before, t)}</strong>
        {!compact && <span className="muted small">{t('suggestions.forDay', { date: formatSessionDay(s.forDate, i18n.language) })}</span>}
        <span className="spacer" />
        <span className={`badge confidence ${s.confidence.toLowerCase()}`}>{t(`suggestions.confidence.${s.confidence}`)}</span>
      </div>

      <dl className="change small">
        <dt>{t('suggestions.before')}</dt>
        <dd data-testid="before">{describeSession(before, t, i18n.language)}</dd>
        <dt>{t('suggestions.after')}</dt>
        <dd data-testid="after">{describeSession(s.after.session, t, i18n.language)}</dd>
      </dl>
      {counterpart && s.after.counterpart && (
        <p className="small muted">
          {t('suggestions.swapWith', { other: sessionName(counterpart, t), date: formatSessionDay(s.after.counterpart.date, i18n.language) })}
        </p>
      )}

      <ul className="reasons small">
        {s.reasons.map((r, i) => (
          <li key={i}>{reasonText(r, t)}</li>
        ))}
      </ul>

      {!pending && (
        <p className="small muted" data-testid="decision">
          {s.decidedBy ? t('suggestions.decidedBy', { status: t(`suggestions.status.${s.status}`), who: s.decidedBy }) : t(`suggestions.status.${s.status}`)}
          {s.coachNote ? ` · ${s.coachNote}` : ''}
        </p>
      )}

      {isCoach && pending && mode === 'view' && (
        <div className="row wrap actions">
          <button onClick={() => decide.mutate({ id: s.id, kind: 'accept' })} disabled={decide.isPending} data-testid="accept">
            {t('suggestions.accept')}
          </button>
          <button className="ghost" onClick={() => setMode('edit')}>
            {t('suggestions.edit')}
          </button>
          <button className="ghost" onClick={() => setMode('reject')}>
            {t('suggestions.reject')}
          </button>
        </div>
      )}

      {isCoach && pending && mode === 'reject' && (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            decide.mutate({ id: s.id, kind: 'reject', note });
          }}
        >
          <label>
            {t('suggestions.rejectWhy')}
            <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} rows={2} />
          </label>
          <div className="row wrap">
            <button type="submit" className="danger" disabled={decide.isPending} data-testid="confirm-reject">
              {t('suggestions.confirmReject')}
            </button>
            <button type="button" className="ghost" onClick={() => setMode('view')}>
              {t('common.cancel')}
            </button>
          </div>
        </form>
      )}

      {isCoach && pending && mode === 'edit' && <EditForm s={s} onCancel={() => setMode('view')} />}
      {decide.error && <p className="error">{decide.error.message}</p>}
    </article>
  );
}

function EditForm({ s, onCancel }: { s: Suggestion; onCancel: () => void }) {
  const { t } = useTranslation();
  const types = useSessionTypes();
  const decide = useDecideSuggestion();
  const [f, setF] = useState<EditFields>(() => editFieldsOf(s));
  const [note, setNote] = useState('');
  const set = <K extends keyof EditFields>(k: K, v: EditFields[K]) => setF((x) => ({ ...x, [k]: v }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    decide.mutate({ id: s.id, kind: 'accept', note, after: editedAfter(s, f) });
  };

  return (
    <form className="stack session-form" onSubmit={submit} aria-label={t('suggestions.edit')}>
      <p className="muted small">{t('suggestions.editHint')}</p>
      <div className="grid">
        <label>
          {t('plan.field.date')}
          <input type="date" value={f.date} onChange={(e) => set('date', e.target.value)} required />
        </label>
        <label>
          {t('plan.field.type')}
          <select value={f.sessionType} onChange={(e) => set('sessionType', e.target.value)}>
            {(types.data ?? [{ code: f.sessionType }]).map((x) => (
              <option key={x.code} value={x.code}>
                {t(`plan.type.${x.code}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('plan.field.km')}
          <input inputMode="decimal" value={f.km} onChange={(e) => set('km', e.target.value)} />
        </label>
        <label>
          {t('plan.field.min')}
          <input inputMode="numeric" value={f.min} onChange={(e) => set('min', e.target.value)} />
        </label>
        {s.before.counterpart && (
          <label>
            {t('suggestions.counterpartDate', { other: sessionName(s.before.counterpart, t) })}
            <input type="date" value={f.counterpartDate} onChange={(e) => set('counterpartDate', e.target.value)} required />
          </label>
        )}
      </div>
      <label>
        {t('plan.field.description')}
        <textarea value={f.description} onChange={(e) => set('description', e.target.value)} maxLength={2000} rows={3} />
      </label>
      <label>
        {t('suggestions.note')}
        <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} rows={2} />
      </label>
      {decide.error && <p className="error">{decide.error.message}</p>}
      <div className="row wrap">
        <button type="submit" disabled={decide.isPending} data-testid="accept-edited">
          {t('suggestions.acceptEdited')}
        </button>
        <button type="button" className="ghost" onClick={onCancel}>
          {t('common.cancel')}
        </button>
      </div>
    </form>
  );
}

/** Plan: every suggestion about one session, newest first, with its decision. */
export function SessionSuggestions({ sessionId, isCoach }: { sessionId: number; isCoach: boolean }) {
  const { t } = useTranslation();
  const { data, isLoading } = useSessionSuggestions(sessionId);
  if (isLoading) return <p className="muted small">{t('common.loading')}</p>;
  if (!data || data.length === 0) return <p className="muted small">{t('suggestions.noHistory')}</p>;
  return (
    <div className="stack" data-testid="session-suggestions">
      {data.map((s) => (
        <SuggestionCard key={s.id} s={s} isCoach={isCoach} compact />
      ))}
    </div>
  );
}
