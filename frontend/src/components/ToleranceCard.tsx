import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useSetToleranceOverride, useTolerance } from '../api/hooks';
import { shareOfTolerance, toleranceSteps } from '../signals';

// Running tolerance with its derivation and the coach's override (stage 2-2, ADR 0021 D11). Athletes screen, coach.

export function ToleranceCard({ athleteId, athleteName }: { athleteId: number; athleteName: string }) {
  const { t } = useTranslation();
  const { data, isLoading, error } = useTolerance(athleteId);
  const save = useSetToleranceOverride(athleteId);
  const [km, setKm] = useState('');
  const [note, setNote] = useState('');

  if (isLoading) return <p className="muted">{t('common.loading')}</p>;
  if (error) return <p className="warn">{t('common.error', { message: error.message })}</p>;
  const tol = data?.tolerance ?? null;
  const share = shareOfTolerance(data?.running, tol);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const value = Number(km.replace(',', '.'));
    if (km.trim() === '' || !Number.isFinite(value)) return;
    save.mutate({ kmPerWeek: value, note: note.trim() || undefined }, { onSuccess: () => { setKm(''); setNote(''); } });
  };

  return (
    <div className="card stack" data-testid="tolerance-card">
      <h2>{t('tolerance.title', { name: athleteName })}</h2>
      {!tol ? (
        <p className="muted small">{t('tolerance.notYet')}</p>
      ) : (
        <>
          <p className="big" data-testid="tolerance-value">
            {tol.kmPerWeek == null ? t('tolerance.noValue') : t('tolerance.value', { km: tol.kmPerWeek.toFixed(1) })}{' '}
            <span className="muted small">{t(`tolerance.source.${tol.source}`)}</span>
          </p>
          {data?.running && (
            <p className="small">
              {t('tolerance.now', { km7: data.running.shortKm.toFixed(1), avg21: data.running.longWeeklyAvgKm.toFixed(1), share: share ?? '–' })}
            </p>
          )}
          <ol className="small" data-testid="tolerance-steps">
            {toleranceSteps(tol).map((s) => (
              <li key={s.key}>{t(s.key, s.params)}</li>
            ))}
          </ol>
          <p className="muted small">{t('tolerance.note')}</p>
        </>
      )}

      <form className="row wrap" onSubmit={submit}>
        <label>
          {t('tolerance.overrideKm')}
          <input type="number" min={0} max={1000} step={0.5} inputMode="decimal" value={km} onChange={(e) => setKm(e.target.value)} />
        </label>
        <label className="grow">
          {t('tolerance.overrideNote')}
          <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        </label>
        <button type="submit" disabled={save.isPending || km.trim() === ''}>
          {t('tolerance.setOverride')}
        </button>
        {tol?.source === 'override' && (
          <button type="button" className="ghost" disabled={save.isPending} onClick={() => save.mutate({ kmPerWeek: null })}>
            {t('tolerance.clearOverride')}
          </button>
        )}
      </form>
      {save.error && <p className="warn small">{save.error.message}</p>}

      {data && data.overrides.length > 0 && (
        <details>
          <summary className="small">{t('tolerance.history', { n: data.overrides.length })}</summary>
          <ul className="list small">
            {data.overrides.slice(0, 10).map((o) => (
              <li key={o.id}>
                {o.createdAt.slice(0, 10)}: {o.kmPerWeek == null ? t('tolerance.cleared') : t('tolerance.value', { km: o.kmPerWeek.toFixed(1) })}
                {o.note ? ` · ${o.note}` : ''}
                {o.createdBy ? ` · ${o.createdBy}` : ''}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}
