# ADR 0005: Session-cookie auth with CSRF double-submit, two roles

- Status: accepted (Phase 0)

## Decision
- Server-side sessions (Spring Security), not JWT. Single-tenant and self-hosted: there is no cross-service token to pass around, and revocation is just session invalidation.
- The session cookie is HttpOnly and SameSite=Strict, plus `Secure` when `COOKIE_SECURE=true` behind TLS.
- CSRF: the readable `XSRF-TOKEN` cookie is echoed in the `X-XSRF-TOKEN` header on every state-changing call (Spring's SPA handler).
- Roles: `COACH` (everything) and `ATHLETE` (read own data). Row-level checks live in `AccessPolicy`; coach-only endpoints use `@PreAuthorize("hasRole('COACH')")`.
- One coach per install, bootstrapped from `COACH_USERNAME` / `COACH_PASSWORD` on first start together with the coach's own athlete profile. The coach creates athlete logins, one per athlete.
- Passwords use Spring's delegating encoder (bcrypt).

## Consequences
- A future native app (Capacitor) runs in a webview on the same origin, so the cookie flow keeps working. A token flow can be added later if needed.
