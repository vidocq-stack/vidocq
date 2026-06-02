# BUG.md — Cervantes

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

---

## CERV-001 — JAX-RS security beans fail to wire on the module path (strict JPMS)

- **Opening date**: 2026-06-02
- **Status**: ✅ FIXED 2026-06-02

### Symptom

On a strict module-path deployment (Vidocq runtime / Arago Docker), every request 500s and/or the
container's `@Initialized(ApplicationScoped.class)` observer chain aborts at boot. Errors:

- `Cannot obtain Lookup for io.vidocq.cervantes.jaxrs.JwtAuthenticationFilter. Ensure the module
  opens the package to io.vidocq.vauban.core`
- `Cannot obtain Lookup for io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer ...`
- When MP-JWT is **not** configured: `IllegalStateException: invalid MicroProfile JWT configuration
  (mp.jwt.verify.* ...)` thrown by the `JwtValidator` producer.

None reproduce on the class-path (TCK stays 206/206) — module boundaries don't apply there.

### Minimal repro

Deploy cervantes on the module path via a CDI container that instantiates `@Provider`/producer beans
by deep reflection (vauban). Separately: ship cervantes with **no** `mp.jwt.verify.*` configured.

### Cause

1. `cervantes-jaxrs` **exports** `io.vidocq.cervantes.jaxrs` but does not **open** it; `cervantes-cdi-vauban`
   exports but does not open `io.vidocq.cervantes.cdi.internal`. vauban-core instantiates the auth
   filter / DynamicFeature / producer beans via `privateLookupIn` + `setAccessible`, which needs the
   package **opened**, not just exported.
2. `JwtAuthConfigProducer.jwtValidator()` threw `IllegalStateException` when no verification key was
   configured, instead of producing `null`. The JAX-RS filter already treats a `null` validator as
   "MP-JWT off → anonymous", so the throw made that documented inert path unreachable (an app that
   ships cervantes but configures no `mp.jwt.*`, e.g. a dev/demo stack without an IdP, failed).

### Fix

- `cervantes-jaxrs/module-info`: `opens io.vidocq.cervantes.jaxrs to io.vidocq.vauban.core;`
- `cervantes-cdi-vauban/module-info`: `opens io.vidocq.cervantes.cdi.internal to io.vidocq.vauban.core;`
  (qualified opens — internal stays unexported as API; mirrors `knock-cdi-vauban`).
- `JwtAuthConfigProducer.jwtValidator()`: return `null` when no verification key is configured
  (inert filter); still throw on a key that is present but invalid (real misconfiguration).

Verified: **MP-JWT 2.1 TCK 206/206 PASS**; Arago Docker boots and serves with no IdP configured
(filter inert), and with Keycloak configured the OIDC acceptance suite stays green.
