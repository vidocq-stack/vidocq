# BUG.md — Kerckhoffs

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## KERCK-20261115-01 · 2026-11-15 · `getAuthType()` answers `HTTP`, not `BASIC`

- **Symptom:** in a servlet protected by the BASIC mechanism, `HttpServletRequest.getAuthType()` returns `HTTP`; Servlet 6.1 expects `BASIC` (`HttpServletRequest.BASIC_AUTH`) for HTTP Basic.
- **Minimal repro:** Vidocq `vidocq-runtime-it-kerckhoffs-security`, `GET /whoami` with a valid `Authorization: Basic …`; assert `getAuthType()` equals `"BASIC"` (the assertion was removed from the IT, Vidocq#603).
- **Hypothesised cause:** `MechanismRequestAuthentication` (`kerckhoffs-foy`) hands Foy the Jakarta Authentication auth type of the profile (`HTTP`) instead of the Servlet one the mechanism implies.
- **Status:** open. The TCK smoke set does not assert it.
