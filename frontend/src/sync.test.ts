import { describe, expect, it } from 'vitest';
import type { GarminSyncStatus } from './api/types';
import { codeKey, showsLastSync, syncNotice } from './sync';

const base: GarminSyncStatus = { connection: 'CONNECTED', morningEnabled: true, morningTime: '07:00:00' };

describe('syncNotice', () => {
  it('tells apart failed, retrying, running, no data and reconnect', () => {
    expect(syncNotice({ state: 'STALE' }, { ...base, latestStatus: 'FAILED', latestErrorCode: 'X' })?.kind).toBe('failed');
    expect(syncNotice({ state: 'STALE' }, { ...base, latestStatus: 'RETRY_WAIT' })?.kind).toBe('retrying');
    expect(syncNotice({ state: 'NO_DATA' }, { ...base, latestStatus: 'RUNNING' })?.kind).toBe('running');
    expect(syncNotice({ state: 'STALE' }, { ...base, latestStatus: 'SUCCEEDED' })?.kind).toBe('noData');
    expect(syncNotice({ state: 'FRESH' }, { ...base, connection: 'RECONNECT_NEEDED' })?.kind).toBe('reconnect');
  });

  it('says nothing about the sync when today has data, or sync is not in use', () => {
    expect(syncNotice({ state: 'FRESH' }, { ...base, latestStatus: 'FAILED' })).toBeNull();
    expect(syncNotice({ state: 'MISSING' }, { ...base, latestStatus: 'SUCCEEDED' })).toBeNull();
    expect(syncNotice({ state: 'STALE' }, { ...base, connection: 'NOT_CONNECTED' })).toBeNull();
    expect(syncNotice({ state: 'STALE' }, { ...base, connection: 'NOT_CONFIGURED' })).toBeNull();
    expect(syncNotice({ state: 'STALE' }, base)).toBeNull(); // connected, never synced
    expect(syncNotice({ state: 'STALE' }, undefined)).toBeNull();
  });
});

describe('helpers', () => {
  it('shows the last-sync line only when sync is in use', () => {
    expect(showsLastSync(base)).toBe(true);
    expect(showsLastSync({ ...base, connection: 'NOT_CONNECTED' })).toBe(false);
    expect(showsLastSync({ ...base, connection: 'NOT_CONNECTED', lastSuccessAt: '2026-11-16T05:00:00Z' })).toBe(true);
    expect(showsLastSync(null)).toBe(false);
  });

  it('maps unknown codes to the generic message', () => {
    expect(codeKey('GARMIN_UNAVAILABLE')).toBe('garmin.codes.GARMIN_UNAVAILABLE');
    expect(codeKey('SOMETHING_ELSE')).toBe('garmin.codes.OTHER');
    expect(codeKey(null)).toBe('garmin.codes.OTHER');
  });
});
