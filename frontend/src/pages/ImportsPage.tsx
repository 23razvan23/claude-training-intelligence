import { useRef, useState, type DragEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useImports, useMe, useUpload } from '../api/hooks';
import type { ImportBatch } from '../api/types';
import { useAthleteSelection } from '../athleteContext';
import { GarminCard } from '../components/GarminCard';

type Kind = 'whoop' | 'garmin' | 'fit';
const ACCEPT: Record<Kind, string> = { whoop: '.zip,.csv', garmin: '.zip', fit: '.fit' };

export function ImportsPage() {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const { athleteId } = useAthleteSelection();
  const { data: batches } = useImports(athleteId);

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;

  return (
    <section className="stack">
      <h1>{t('imports.title')}</h1>
      {me?.role === 'COACH' && <GarminCard athleteId={athleteId} />}
      {me?.role === 'COACH' ? (
        <div className="cards">
          {(['whoop', 'garmin', 'fit'] as Kind[]).map((k) => (
            <UploadCard key={k} kind={k} athleteId={athleteId} />
          ))}
        </div>
      ) : (
        <p className="muted">{t('imports.coachOnly')}</p>
      )}
      <h2>{t('imports.history')}</h2>
      {batches?.length === 0 && <p className="muted">{t('imports.empty')}</p>}
      <ul className="list">
        {batches?.map((b) => <BatchRow key={b.id} batch={b} />)}
      </ul>
    </section>
  );
}

function UploadCard({ kind, athleteId }: { kind: Kind; athleteId: number }) {
  const { t } = useTranslation();
  const upload = useUpload(athleteId);
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);

  const send = (list: FileList | null) => {
    if (!list || list.length === 0) return;
    const files = Array.from(list);
    upload.mutate({ kind, files: kind === 'garmin' ? files.slice(0, 1) : files });
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    send(e.dataTransfer.files);
  };

  return (
    <div
      className={`card stack drop ${over ? 'over' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={onDrop}
    >
      <h3>{t(`imports.${kind}.title`)}</h3>
      <p className="muted small">{t(`imports.${kind}.hint`)}</p>
      <input
        ref={input}
        type="file"
        hidden
        accept={ACCEPT[kind]}
        multiple={kind !== 'garmin'}
        onChange={(e) => {
          send(e.target.files);
          e.target.value = '';
        }}
      />
      <div className="row wrap">
        <button disabled={upload.isPending} onClick={() => input.current?.click()}>
          {upload.isPending ? t('imports.uploading') : t('imports.choose')}
        </button>
        <span className="muted small">{t('imports.drop')}</span>
      </div>
      {upload.isError && <p className="error">{t('common.error', { message: upload.error.message })}</p>}
    </div>
  );
}

function Progress({ batch }: { batch: ImportBatch }) {
  const { t } = useTranslation();
  const p = batch.progress;
  if (!p) return null;
  const minutes = batch.startedAt ? Math.floor((Date.now() - new Date(batch.startedAt).getTime()) / 60000) : 0;
  const key = p.total != null && p.phase !== 'wellness' && p.phase !== 'activities' ? `${p.phase}_total` : p.phase;
  const pct = p.total ? Math.min(100, Math.round((100 * p.done) / p.total)) : null;
  return (
    <div className="stack small">
      <span>
        {t(`imports.progress.${key}`, { done: p.done.toLocaleString(), total: p.total?.toLocaleString() })}
        {minutes > 0 && <span className="muted"> · {t('imports.progress.elapsed', { minutes })}</span>}
      </span>
      <span className="progress-track" role="progressbar" aria-valuenow={pct ?? undefined}>
        <span className={pct == null ? 'progress-bar indeterminate' : 'progress-bar'} style={pct == null ? undefined : { width: `${pct}%` }} />
      </span>
    </div>
  );
}

function BatchRow({ batch }: { batch: ImportBatch }) {
  const { t } = useTranslation();
  const totals = Object.values(batch.stats ?? {}).reduce(
    (acc, c) => ({
      inserted: acc.inserted + c.inserted,
      duplicates: acc.duplicates + c.duplicates,
      skipped: acc.skipped + c.skipped,
    }),
    { inserted: 0, duplicates: 0, skipped: 0 },
  );
  return (
    <li className="card stack">
      <div className="row wrap">
        <span className={`badge ${batch.status.toLowerCase()}`}>{t(`imports.status.${batch.status}`)}</span>
        <strong>{t(`imports.kind.${batch.kind}`)}</strong>
        <span className="muted small ellipsis">{batch.filename}</span>
        <span className="spacer" />
        <span className="muted small">{new Date(batch.createdAt).toLocaleString()}</span>
      </div>
      {(batch.status === 'RUNNING' || batch.status === 'PENDING') && <Progress batch={batch} />}
      {batch.status === 'SUCCEEDED' && <div className="small">{t('imports.summary', totals)}</div>}
      {batch.error && <div className="error small">{batch.error}</div>}
      {(batch.status === 'SUCCEEDED' || batch.status === 'FAILED') && Object.keys(batch.stats ?? {}).length > 0 && (
        <details>
          <summary className="small">{Object.keys(batch.stats).length} tables</summary>
          <table className="small">
            <tbody>
              {Object.entries(batch.stats).map(([table, c]) => (
                <tr key={table}>
                  <td>{table}</td>
                  <td>+{c.inserted}</td>
                  <td className="muted">={c.duplicates}</td>
                  <td className="muted">−{c.skipped}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
      {batch.warnings?.length > 0 && (
        <details>
          <summary className="small warn">{t('imports.warnings', { count: batch.warnings.length })}</summary>
          <ul className="small">
            {batch.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </details>
      )}
    </li>
  );
}
