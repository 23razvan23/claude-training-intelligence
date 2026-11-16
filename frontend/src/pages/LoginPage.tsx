import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useLogin } from '../api/hooks';

export function LoginPage() {
  const { t } = useTranslation();
  const login = useLogin();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    // Today is the landing page after every login (stage 1b-2a).
    // The page unmounts as soon as the user is known, so navigate from the promise, not a mutate callback.
    login
      .mutateAsync({ username, password })
      .then(() => navigate('/today', { replace: true }))
      .catch(() => {
        /* shown from login.error below */
      });
  };

  return (
    <main className="login">
      <form className="card stack" onSubmit={submit}>
        <h1>{t('app.name')}</h1>
        <label>
          {t('login.username')}
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label>
          {t('login.password')}
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {login.isError && (
          <p className="error">
            {login.error instanceof ApiError && login.error.status === 429 ? t('login.tooMany') : t('login.failed')}
          </p>
        )}
        <button type="submit" disabled={login.isPending}>
          {t('login.submit')}
        </button>
      </form>
    </main>
  );
}
