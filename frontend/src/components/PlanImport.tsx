import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { usePlanImportCommit, usePlanImportPreview } from '../api/hooks';
import type { PlanImportPreview, PlanImportRow } from '../api/types';
import { formatSessionDay } from '../plan';

// Plan import from a CSV or Excel file (stage 2-1a PR 2, ADR 0019). Step 1 shows a preview and saves nothing;
// step 2 sends the same file again and saves every row or none. The coach only; the Plan page decides who sees it.

export function PlanImport({ athleteId, onDone }: { athleteId: number; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<PlanImportPreview | null>(null);
  const [imported, setImported] = useState<number | null>(null);
  const previewMutation = usePlanImportPreview(athleteId);
  const commit = usePlanImportCommit(athleteId);

  function choose(f: File | undefined) {
    if (!f) return;
    setFile(f);
    setPreview(null);
    setImported(null);
    commit.reset();
    previewMutation.mutate(f, { onSuccess: setPreview });
  }

  function reset() {
    setFile(null);
    setPreview(null);
    previewMutation.reset();
    commit.reset();
    if (input.current) input.current.value = '';
  }

  const day = (d?: string | null) => (d ? formatSessionDay(d, i18n.language) : t('plan.import.noDate'));

  return (
    <section className="card stack" aria-label={t('plan.import.button')}>
      <h2>{t('plan.import.button')}</h2>
      <p className="muted small">{t('plan.import.hint')}</p>
      <input
        ref={input}
        type="file"
        accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        hidden
        aria-label={t('plan.import.choose')}
        onChange={(e) => choose(e.target.files?.[0])}
      />
      {imported == null && (
        <div className="import-actions">
          <button className="ghost" disabled={previewMutation.isPending || commit.isPending} onClick={() => input.current?.click()}>
            {t('plan.import.choose')}
          </button>
          <button className="ghost" onClick={() => { reset(); onDone(); }}>{t('plan.import.cancel')}</button>
        </div>
      )}
      {previewMutation.isPending && <p className="muted">{t('plan.import.reading')}</p>}
      {previewMutation.isError && <p className="error">{t('common.error', { message: previewMutation.error.message })}</p>}

      {imported != null && (
        <div className="stack">
          <p role="status">{t('plan.import.done', { count: imported })}</p>
          <div><button onClick={() => { reset(); onDone(); }}>{t('plan.import.ok')}</button></div>
        </div>
      )}

      {preview && imported == null && (
        <div className="stack">
          {file && <p className="small muted">{t('plan.import.file', { name: file.name })}</p>}
          {preview.fileErrors.map((m) => (
            <p key={m} className="error">{m}</p>
          ))}
          {preview.rowCount > 0 && (
            <p className="small">
              <strong>{t('plan.import.summary', { count: preview.rowCount })}</strong>
              {preview.errorCount > 0 && <> · <span className="error">{t('plan.import.errors', { count: preview.errorCount })}</span></>}
              {preview.warningCount > 0 && <> · {t('plan.import.warnings', { count: preview.warningCount })}</>}
            </p>
          )}
          {preview.newPlanStart && preview.newPlanEnd && (preview.newPlanStart !== preview.planStart || preview.newPlanEnd !== preview.planEnd) && (
            <p className="small">{t('plan.import.stretch', { name: preview.planName, start: day(preview.newPlanStart), end: day(preview.newPlanEnd) })}</p>
          )}
          {!preview.canImport && preview.rowCount > 0 && <p className="small muted">{t('plan.import.fixFile')}</p>}
          <ul className="list import-rows">
            {preview.rows.map((r) => (
              <PreviewRow key={r.row} r={r} day={day} />
            ))}
          </ul>
          {commit.isError && <p className="error">{t('common.error', { message: commit.error.message })}</p>}
          <div className="import-actions">
            <button
              disabled={!preview.canImport || commit.isPending || !file}
              onClick={() => file && commit.mutate(file, { onSuccess: (r) => setImported(r.imported) })}
            >
              {commit.isPending ? t('plan.import.importing') : t('plan.import.run', { count: preview.rowCount })}
            </button>
            <button className="ghost" disabled={commit.isPending} onClick={() => { reset(); onDone(); }}>
              {t('plan.import.cancel')}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function PreviewRow({ r, day }: { r: PlanImportRow; day: (d?: string | null) => string }) {
  const { t } = useTranslation();
  const type = r.sessionType ? t(`plan.type.${r.sessionType}`, { defaultValue: r.sessionType }) : '';
  const facts = [
    r.distanceKm != null && t('plan.km', { value: r.distanceKm }),
    r.durationMin != null && t('plan.min', { value: r.durationMin }),
  ].filter(Boolean);
  const cls = r.errors.length ? ' bad' : r.warnings.length ? ' note' : '';
  return (
    <li className={`card import-row${cls}`}>
      <div className="row wrap small">
        <span className="muted">{t('plan.import.row', { row: r.row })}</span>
        <span className="session-day">{day(r.date)}</span>
        {type && <strong>{r.title || type}</strong>}
        {r.demand && <span className={`badge demand ${r.demand.toLowerCase()}`}>{t(`plan.demand.${r.demand}`)}</span>}
      </div>
      {(facts.length > 0 || (r.title && type)) && (
        <p className="small muted">{[r.title && type, ...facts].filter(Boolean).join(' · ')}</p>
      )}
      {r.workout && <p className="small pre">{r.workout}</p>}
      {r.errors.map((m) => (
        <p key={m} className="problem small">{m}</p>
      ))}
      {r.warnings.map((m) => (
        <p key={m} className="caution small">{m}</p>
      ))}
    </li>
  );
}
