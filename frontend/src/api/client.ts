// Thin fetch wrapper: same-origin session cookie + CSRF double-submit header.

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

// Called when the server says the session is over (removed login, changed password, expiry).
let onUnauthorized: (() => void) | undefined;

export function setUnauthorizedHandler(handler: (() => void) | undefined) {
  onUnauthorized = handler;
}

function xsrfToken(): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith('XSRF-TOKEN='))
    ?.split('=')[1];
}

async function ensureCsrf(): Promise<string | undefined> {
  let token = xsrfToken();
  if (!token) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    token = xsrfToken();
  }
  return token ? decodeURIComponent(token) : undefined;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (method !== 'GET' && method !== 'HEAD') {
    const token = await ensureCsrf();
    if (token) headers.set('X-XSRF-TOKEN', token);
  }
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const res = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  // A wrong password on the login form is also 401, but that is not a lost session.
  if (res.status === 401 && path !== '/api/auth/login' && path !== '/api/auth/me') onUnauthorized?.();
  if (!res.ok) {
    let message = res.statusText;
    try {
      const problem = await res.json();
      message = problem.detail ?? problem.title ?? message;
    } catch {
      /* no JSON body */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204 || res.headers.get('Content-Length') === '0') return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
