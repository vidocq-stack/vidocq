# BUG.md — Kerckhoffs

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## KERCK-20261115-01 · 2026-11-15 · `getAuthType()` answers `HTTP`, not `BASIC`

- **Symptom:** in a servlet protected by the BASIC mechanism, `HttpServletRequest.getAuthType()` returns `HTTP`; Servlet 6.1 expects `BASIC` (`HttpServletRequest.BASIC_AUTH`) for HTTP Basic.
- **Minimal repro:** Vidocq `vidocq-runtime-it-kerckhoffs-security`, `GET /whoami` with a valid `Authorization: Basic …`; assert `getAuthType()` equals `"BASIC"` (the assertion was removed from the IT, Vidocq#603).
- **Cause:** no built-in mechanism named its type in the message map, so `MechanismRequestAuthentication` (`kerckhoffs-foy`) fell back to its default `HTTP`.
- **Status:** fixed 2026-11-15: `HttpAuthenticationMechanismModule` publishes `BASIC` / `FORM` (`jakarta.servlet.http.authType`) after a successful built-in mechanism; a custom mechanism keeps the `HTTP` default. Vidocq's IT can assert `BASIC` again once this SNAPSHOT is deployed.

## KERCK-20261116-01 · 2026-11-16 · `@Decorator` of an `HttpAuthenticationMechanism` is not applied

- **Symptom:** official Security TCK `app-mem-basic-decorate` passes 1 of 2 tests: the decorator that must wrap the mechanism never runs.
- **Minimal repro:** `run-official-tck-security-4.0.sh app-mem-basic-decorate` (container or assembled runtime).
- **Cause:** Vauban (CDI Lite) applies no `@Decorator`, and the mechanism is a synthetic bean, so a decorator has nothing to attach to.
- **Status:** open, upstream (Vauban). A build-time decorator support is being assessed with the Vauban session.

## KERCK-20261116-02 · 2026-11-16 · REST endpoints are not protected by the Servlet authentication hook

- **Symptom:** official Security TCK `app-jaxrs` passes 4 of 8: requests to the REST resources bypass the authentication and authorization of Foy.
- **Minimal repro:** `run-official-tck-security-4.0.sh app-jaxrs`.
- **Cause:** Cassini mounts on Chappe outside Foy, so neither the context path nor the authentication hook sees its requests; the runtime security runner also has no REST extension.
- **Status:** open, upstream (Cassini inside Foy, plus a REST extension in the runner).
