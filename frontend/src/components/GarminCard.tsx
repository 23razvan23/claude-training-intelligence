import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/client';
import { useGarminConnect, useGarminDisconnect, useGarminStatus } from '../api/hooks';
import { GarminSync } from './GarminSync';

// Garmin connection (ADR 0011): the password is typed once and sent to the app, which signs
// in through the sidecar and keeps only an encrypted token. If Garmin asks for a code, the
// same form shows a code field. Nothing secret is kept in the page after a submit.

const LOCAL_HOSTS = ['localhost', '127.0.0.1', '::1', '[::1]'];

export function GarminCard({ athleteId }: { athleteId: number }) {
  const { t } = useTranslation();
  const { data: status, isError, error } = useGarminStatus(athleteId);
  const connect = useGarminConnect(athleteId);
  const disconnect = useGarminDisconnect(athleteId);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [mfaId, setMfaId] = useState<string | null>(null);

  const reset = () => {
    setPassword('');
    setCode('');
    setMfaId(null);
    connect.reset();
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const body = mfaId ? { mfaId, code } : { username, password };
    connect.mutate(body, {
      onSuccess: (r) => {
        setPassword('');
        setCode('');
        if (r.status === 'MFA_REQUIRED' && r.mfaId) {
          setMfaId(r.mfaId);
        } else {
          setMfaId(null);
          setUsername('');
        }
      },
      onError: (err) => {
        setPassword('');
        setCode('');
        // A used-up or expired code request means starting over with the password.
        if (err instanceof ApiError && err.code === 'MFA_EXPIRED') setMfaId(null);
      },
    });
  };

  const onDisconnect = () => {
    if (window.confirm(t('garmin.confirmDisconnect'))) disconnect.mutate(undefined, { onSuccess: reset });
  };

  if (isError) return <div className="card">{t('common.error', { message: error.message })}</div>;
  if (!status) return <div className="card muted">{t('common.loading')}</div>;

  const remote = typeof window !== 'undefined' && !LOCAL_HOSTS.includes(window.location.hostname);
  const showForm = status.state === 'NOT_CONNECTED' || status.state === 'RECONNECT_NEEDED';

  return (
    <div className="card stack garmin-card" aria-label={t('garmin.title')}>
      <h3>{t('garmin.title')}</h3>

      {status.state === 'NOT_CONFIGURED' && <p className="warn small">{t('garmin.notConfigured')}</p>}

      {status.state === 'CONNECTED' && (
        <p className="small">
          <span className="badge succeeded">{t('garmin.state.CONNECTED')}</span>{' '}
          {status.connectedAt && t('garmin.connectedSince', { when: new Date(status.connectedAt).toLocaleString() })}
        </p>
      )}

      {status.state === 'CONNECTED' && <GarminSync athleteId={athleteId} />}

      {status.state === 'RECONNECT_NEEDED' && (
        <div className="banner" role="alert">
          <strong>{t('garmin.reconnectTitle')}</strong>
          <span className="small">
            {t(status.lastError === 'KEY_CHANGED' ? 'garmin.reconnectKeyChanged' : 'garmin.reconnectHint')}
          </span>
        </div>
      )}

      {showForm && (
        <form className="stack" onSubmit={submit} autoComplete="off">
          {!mfaId ? (
            <>
              <p className="muted small">{t('garmin.passwordOnce')}</p>
              {remote && <p className="warn small">{t('garmin.remoteWarning')}</p>}
              <label className="stack small">
                {t('garmin.username')}
                <input
                  type="email"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="off"
                  required
                />
              </label>
              <label className="stack small">
                {t('garmin.password')}
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="new-password"
                  required
                />
              </label>
            </>
          ) : (
            <>
              <p className="small">{t('garmin.mfaHint')}</p>
              <label className="stack small">
                {t('garmin.code')}
                <input
                  inputMode="numeric"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  autoComplete="one-time-code"
                  required
                  autoFocus
                />
              </label>
            </>
          )}
          <div className="row wrap">
            <button type="submit" disabled={connect.isPending}>
              {connect.isPending ? t('garmin.connecting') : mfaId ? t('garmin.submitCode') : t('garmin.connect')}
            </button>
            {mfaId && (
              <button type="button" className="ghost" onClick={reset}>
                {t('common.cancel')}
              </button>
            )}
          </div>
          {connect.isError && <p className="error small">{errorText(t, connect.error)}</p>}
        </form>
      )}

      {status.stored && (
        <div className="row wrap">
          <button type="button" className="danger" disabled={disconnect.isPending} onClick={onDisconnect}>
            {t('garmin.disconnect')}
          </button>
          <span className="muted small">{t('garmin.disconnectHint')}</span>
        </div>
      )}
      {disconnect.isError && <p className="error small">{t('common.error', { message: disconnect.error.message })}</p>}
    </div>
  );
}

function errorText(t: (k: string, o?: Record<string, unknown>) => string, err: Error): string {
  const code = err instanceof ApiError ? err.code : undefined;
  const known = ['AUTH_FAILED', 'MFA_INVALID', 'MFA_EXPIRED', 'RATE_LIMITED', 'GARMIN_UNAVAILABLE', 'SIDECAR_UNREACHABLE',
    'SECRET_MISMATCH', 'NOT_CONFIGURED'];
  return code && known.includes(code) ? t(`garmin.errors.${code}`) : t('common.error', { message: err.message });
}
