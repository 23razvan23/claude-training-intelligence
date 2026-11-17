import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/client';
import { useGarminCheck, useGarminRuns, useGarminSyncNow } from '../api/hooks';
import type { GarminCheckReport, GarminSyncRun } from '../api/types';

// Sync now, the last run, and "Check against export" (ADR 0015 D5-D6). Shown inside the
// Garmin card once Garmin is connected.

const KNOWN_CODES = [
  'GARMIN_UNAVAILABLE', 'RATE_LIMITED', 'SIDECAR_UNREACHABLE', 'IMPORT_RUNNING', 'AUTH_REJECTED', 'KEY_CHANGED',
  'RECONNECT_NEEDED', 'NOT_CONNECTED', 'NOT_CONFIGURED', 'SECRET_MISMATCH', 'IMPORT_FAILED', 'RECOMPUTE_FAILED',
  'INTERRUPTED', 'BAD_RESPONSE', 'SYNC_RUNNING',
];

function time(iso: string) {
  return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

function isoDay(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function GarminSync({ athleteId }: { athleteId: number }) {
  const { t } = useTranslation();
  const { data: runs } = useGarminRuns(athleteId);
  const syncNow = useGarminSyncNow(athleteId);
  const last = runs?.[0];
  const running = last?.status === 'RUNNING';
  const code = (c?: string | null) => (c && KNOWN_CODES.includes(c) ? t(`garmin.codes.${c}`) : t('garmin.codes.OTHER'));

  return (
    <div className="stack">
      <div className="row wrap">
        <button type="button" disabled={running || syncNow.isPending} onClick={() => syncNow.mutate()}>
          {running || syncNow.isPending ? t('garmin.syncing') : t('garmin.syncNow')}
        </button>
        <span className="muted small">{t('garmin.syncHint')}</span>
      </div>
      {syncNow.isError && (
        <p className="error small">
          {syncNow.error instanceof ApiError && syncNow.error.code ? code(syncNow.error.code) : syncNow.error.message}
        </p>
      )}
      {last && <LastRun run={last} codeText={code} />}
      <ExportCheck athleteId={athleteId} />
    </div>
  );
}

function LastRun({ run, codeText }: { run: GarminSyncRun; codeText: (c?: string | null) => string }) {
  const { t } = useTranslation();
  const when = time(run.finishedAt ?? run.startedAt);
  if (run.status === 'RUNNING') return <p className="small">{t('garmin.run.running', { when: time(run.startedAt) })}</p>;
  if (run.status === 'RETRY_WAIT') {
    return (
      <p className="warn small" role="status">
        {t('garmin.run.retry', { reason: codeText(run.errorCode), at: run.nextRetryAt ? time(run.nextRetryAt) : '' })}
      </p>
    );
  }
  if (run.status === 'FAILED') {
    return (
      <p className="error small" role="alert">
        {t('garmin.run.failed', { when, reason: codeText(run.errorCode) })}
      </p>
    );
  }
  return (
    <p className="small">
      {run.rowsInserted
        ? t(run.recomputed ? 'garmin.run.okNew' : 'garmin.run.okNewNoScore', { when, count: run.rowsInserted })
        : t('garmin.run.okNothing', { when })}
      {run.missing.length > 0 && <span className="muted"> · {t('garmin.run.missing', { count: run.missing.length })}</span>}
    </p>
  );
}

function ExportCheck({ athleteId }: { athleteId: number }) {
  const { t } = useTranslation();
  const check = useGarminCheck(athleteId);
  const today = new Date();
  const [from, setFrom] = useState(isoDay(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 10)));
  const [to, setTo] = useState(isoDay(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 4)));

  return (
    <details className="stack">
      <summary className="small">{t('garmin.check.title')}</summary>
      <p className="muted small">{t('garmin.check.hint')}</p>
      <div className="row wrap">
        <label className="small">
          {t('garmin.check.from')} <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="small">
          {t('garmin.check.to')} <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <button type="button" className="ghost" disabled={check.isPending} onClick={() => check.mutate({ from, to })}>
          {check.isPending ? t('garmin.check.running') : t('garmin.check.run')}
        </button>
      </div>
      {check.isError && <p className="error small">{t('common.error', { message: check.error.message })}</p>}
      {check.data && <CheckResult report={check.data} />}
    </details>
  );
}

function CheckResult({ report }: { report: GarminCheckReport }) {
  const { t } = useTranslation();
  const newRows = Object.entries(report.tables).filter(([, c]) => c.inserted > 0);
  const used = report.differences.filter((d) => d.usedByScores || d.outcome === 'NEW_RECORD');
  const other = report.differences.filter((d) => !d.usedByScores && d.outcome !== 'NEW_RECORD');
  return (
    <div className="stack small" aria-label={t('garmin.check.title')}>
      {report.differencesUsedByScores === 0 && used.length === 0 ? (
        <p className="ok">{t('garmin.check.allGood', { from: report.from, to: report.to })}</p>
      ) : (
        <p className="warn">{t('garmin.check.differs', { count: report.differencesUsedByScores })}</p>
      )}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>{t('garmin.check.table')}</th>
              <th>{t('garmin.check.newRows')}</th>
              <th>{t('garmin.check.same')}</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(report.tables).map(([table, c]) => (
              <tr key={table}>
                <td>{table}</td>
                <td>{c.inserted}</td>
                <td>{c.duplicates}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {[...used, ...other].length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>{t('garmin.check.day')}</th>
                <th>{t('garmin.check.table')}</th>
                <th>{t('garmin.check.column')}</th>
                <th>{t('garmin.check.outcome')}</th>
              </tr>
            </thead>
            <tbody>
              {[...used, ...other].map((d, i) => (
                <tr key={i} className={d.usedByScores ? 'warn' : 'muted'}>
                  <td>{d.day}</td>
                  <td>
                    {d.table} ({d.record})
                  </td>
                  <td>{d.column ?? '—'}</td>
                  <td>
                    {t(`garmin.check.outcomes.${d.outcome}`)}
                    {d.usedByScores ? ` · ${t('garmin.check.usedByScores')}` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {newRows.length > 0 && <p className="muted">{t('garmin.check.newRowsHint')}</p>}
      {report.missing.length > 0 && <p className="muted">{t('garmin.check.missing', { list: report.missing.join(', ') })}</p>}
      <p className="muted">{t('garmin.check.nothingKept')}</p>
    </div>
  );
}
