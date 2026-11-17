# ADR 0005: Session-cookie auth with CSRF double-submit, two roles

- Status: accepted (Phase 0); revocation and login slowdown added by R-09 (see below)

## Decision
- Server-side sessions (Spring Security), not JWT. Single-tenant and self-hosted: there is no cross-service token to pass around, and revocation is just session invalidation (made real by R-09, below).
- The session cookie is HttpOnly and SameSite=Strict, plus `Secure` when `COOKIE_SECURE=true` behind TLS.
- CSRF: the readable `XSRF-TOKEN` cookie is echoed in the `X-XSRF-TOKEN` header on every state-changing call (Spring's SPA handler).
- Roles: `COACH` (everything) and `ATHLETE` (read own data). Row-level checks live in `AccessPolicy`; coach-only endpoints use `@PreAuthorize("hasRole('COACH')")`.
- One coach per install, bootstrapped from `COACH_USERNAME` / `COACH_PASSWORD` on first start together with the coach's own athlete profile. The coach creates athlete logins, one per athlete.
- Passwords use Spring's delegating encoder (bcrypt).

## Consequences
- A future native app (Capacitor) runs in a webview on the same origin, so the cookie flow keeps working. A token flow can be added later if needed.

## R-09: revocation, docs paths, login slowdown
The first version said revocation is session invalidation, but nothing invalidated anything: a session kept a copy of the user for 30 days.
- **Every authenticated request re-checks the user** (`SessionGuard`, one small query by id). It answers 401 and ends the session when the user no longer exists, is disabled, or changed the password after this session logged in.
- **Login time:** the session stores when its password was last proven (`app.training.loginAt`), set at login and at password change. `app_user.password_changed_at` (V18) is set by the database when the password changes. A session is valid if the column is empty or not newer than the session's login time.
- **Password change** rotates the session id of the session that made the change, which adopts the change time as its new login time and keeps working. Every other session of that user gets 401 on its next request. Deleting a login ends its sessions the same way (the row is gone).
- **Sessions from before V18** have no login time. They are checked only for "exists and enabled", so nothing in the check itself forces a new login; only password changes made afterwards end older sessions. *Current state (R-15 correction):* this did not mean nobody had to log in again. Sessions are kept in the app's memory (no session store), so every restart of the app, including every `docker compose up --build` update, ends all sessions and everyone logs in again. This was already true before R-09 and is still true. It is accepted for a single-coach, self-hosted install: updates are rare and a new login takes seconds. A shared session store would be needed to change it.
- **API description pages** (`/api/openapi`, `/api/docs`, `/swagger-ui/**`) now need a login.
- **Login slowdown** (`LoginThrottle`, in memory, per username and client address): after `app.security.login-max-failures` wrong passwords [configurable, default 5: slows guessing while a coach who mistypes a few times is unaffected], logins answer 429 with `Retry-After` until `app.security.login-window` [configurable, default 15 minutes, counted from the first failure: about 5 guesses per 15 minutes, and the only coach account is never locked out for long] has passed. Even the right password waits, otherwise the slowdown would not slow guessing. The count is lost on restart. Nothing but counts is kept; passwords and request bodies are never logged.
- **Frontend:** any 401 from an API call (except the login form's own) clears the cached user, which shows the login page.
- Roles, cookies and CSRF are unchanged. Not done (out of scope): JWT, 2FA, TLS.
