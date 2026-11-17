import { useTranslation } from 'react-i18next';
import type { GarminSyncStatus } from '../api/types';
import { codeKey, formatWhen, morningTime, showsLastSync, type SyncNotice } from '../sync';

// The "Last sync" line (Today and Imports) and Today's sync banner (ADR 0015 D9, ADR 0014 D1).

export function LastSync({ sync }: { sync?: GarminSyncStatus | null }) {
  const { t, i18n } = useTranslation();
  if (!showsLastSync(sync)) return null;
  const when = sync.lastSuccessAt ? formatWhen(sync.lastSuccessAt, i18n.language) : null;
  return (
    <p className="muted small" data-testid="last-sync">
      {when ? t('sync.last', { when }) : t('sync.never')}
      {sync.connection === 'CONNECTED' && sync.morningEnabled && <> · {t('sync.morning', { time: morningTime(sync) })}</>}
    </p>
  );
}

export function SyncBanner({ notice }: { notice: SyncNotice | null }) {
  const { t, i18n } = useTranslation();
  if (!notice) return null;
  const when = (iso?: string | null) => (iso ? formatWhen(iso, i18n.language) : '–');
  switch (notice.kind) {
    case 'reconnect':
      return (
        <div className="banner danger" role="alert" data-testid="sync-banner" data-kind="reconnect">
          <strong>{t('sync.reconnectTitle')}</strong>
          <span>{t('sync.reconnectBody')}</span>
        </div>
      );
    case 'failed':
      return (
        <div className="banner danger" role="alert" data-testid="sync-banner" data-kind="failed">
          <strong>{t('sync.failedTitle', { when: when(notice.at) })}</strong>
          <span>{t(codeKey(notice.code))}</span>
        </div>
      );
    case 'retrying':
      return (
        <div className="banner" role="status" data-testid="sync-banner" data-kind="retrying">
          <strong>{t('sync.retryingTitle', { when: when(notice.at), retry: when(notice.retryAt) })}</strong>
          <span>{t(codeKey(notice.code))}</span>
        </div>
      );
    case 'running':
      return (
        <div className="banner" role="status" data-testid="sync-banner" data-kind="running">
          <span>{t('sync.running')}</span>
        </div>
      );
    case 'noData':
      return (
        <div className="banner" role="status" data-testid="sync-banner" data-kind="noData">
          <strong>{t('sync.noDataTitle', { when: when(notice.at) })}</strong>
          <span>{t('sync.noDataBody')}</span>
        </div>
      );
  }
}
