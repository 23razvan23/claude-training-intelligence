import type { GarminSyncStatus, Today } from './api/types';

// The Garmin sync on Today and Imports (ADR 0015 D9, ADR 0014 D1): pure functions, easy to test.

/** Why today's data may be missing, as one of the distinct banners Today shows. */
export type SyncNotice =
  | { kind: 'reconnect' }
  | { kind: 'failed'; at?: string | null; code?: string | null }
  | { kind: 'retrying'; at?: string | null; code?: string | null; retryAt?: string | null }
  | { kind: 'running' }
  | { kind: 'noData'; at?: string | null };

/**
 * The banner Today shows about the sync, or null.
 * - Garmin needs a reconnect: always (the morning run is stopped until then).
 * - Otherwise only while there is no row for today (STALE, NO_DATA): the newest run failed, waits to
 *   retry or is still running; or it succeeded but Garmin had nothing for today yet.
 * - Not connected or not set up: null (Today's own "import an export" wording applies).
 */
export function syncNotice(today: Pick<Today, 'state'>, sync?: GarminSyncStatus | null): SyncNotice | null {
  if (!sync) return null;
  if (sync.connection === 'RECONNECT_NEEDED') return { kind: 'reconnect' };
  if (sync.connection !== 'CONNECTED') return null;
  if (today.state !== 'STALE' && today.state !== 'NO_DATA') return null;
  switch (sync.latestStatus) {
    case 'FAILED':
      return { kind: 'failed', at: sync.latestAt, code: sync.latestErrorCode };
    case 'RETRY_WAIT':
      return { kind: 'retrying', at: sync.latestAt, code: sync.latestErrorCode, retryAt: sync.nextRetryAt };
    case 'RUNNING':
      return { kind: 'running' };
    case 'SUCCEEDED':
      return { kind: 'noData', at: sync.lastSuccessAt };
    default:
      return null; // connected, but never synced yet
  }
}

/** Whether the "Last sync" line is worth showing: sync is set up and has been used. */
export function showsLastSync(sync?: GarminSyncStatus | null): sync is GarminSyncStatus {
  return !!sync && (sync.connection === 'CONNECTED' || sync.connection === 'RECONNECT_NEEDED' || !!sync.lastSuccessAt);
}

/** "07:00:00" → "07:00". */
export function morningTime(sync: GarminSyncStatus): string {
  return sync.morningTime.slice(0, 5);
}

/** A time as "Tue 18 Nov, 07:01" (the phone's timezone, which is the athlete's). */
export function formatWhen(iso: string, lang: string): string {
  return new Date(iso).toLocaleString(lang, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/** Error codes with their own wording under garmin.codes; anything else reads as OTHER. */
export const KNOWN_SYNC_CODES = [
  'GARMIN_UNAVAILABLE', 'RATE_LIMITED', 'SIDECAR_UNREACHABLE', 'IMPORT_RUNNING', 'AUTH_REJECTED', 'KEY_CHANGED',
  'RECONNECT_NEEDED', 'NOT_CONNECTED', 'NOT_CONFIGURED', 'SECRET_MISMATCH', 'IMPORT_FAILED', 'RECOMPUTE_FAILED',
  'INTERRUPTED', 'BAD_RESPONSE', 'SYNC_RUNNING',
];

export function codeKey(code?: string | null): string {
  return code && KNOWN_SYNC_CODES.includes(code) ? `garmin.codes.${code}` : 'garmin.codes.OTHER';
}
