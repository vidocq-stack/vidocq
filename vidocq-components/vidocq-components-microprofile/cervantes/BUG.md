# BUG.md — Cervantes

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

---

## CERV-001 — JAX-RS security beans fail to wire on the module path (strict Java Modules)

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

---

## CERV-002 — `JsonWebTokenContext` fails to wire on the module path (strict Java Modules)

- **Opening date**: 2026-06-03
- **Status**: ✅ FIXED 2026-06-03

### Symptom

With Keycloak configured (Arago `docker-compose.localdev.yml`), any request carrying a valid Bearer
token 500s on the JWT context:

```
Cannot obtain Lookup for io.vidocq.cervantes.cdi.JsonWebTokenContext. Ensure the module opens the
package to io.vidocq.vauban.core: opens io.vidocq.cervantes.cdi to io.vidocq.vauban.core;
```

Does not reproduce on the class-path (TCK stays 206/206) — module boundaries don't apply there. This
is the same family as CERV-001, but the **exported** (not `.internal`) package `io.vidocq.cervantes.cdi`
was missed: its `@RequestScoped` `JsonWebTokenContext` is also instantiated by vauban-core via
`privateLookupIn`, so `exports` alone is insufficient.

### Minimal repro

Deploy cervantes on the module path with `mp.jwt.verify.*` configured, then call any
`@Inject JsonWebToken` / `@Context SecurityContext` resource with a valid Bearer (e.g. Arago
`GET /api/oidc/me`). Only the module path (docker compose) triggers it; classpath/TCK stay green.

### Cause

`cervantes-cdi-vauban` **exports** `io.vidocq.cervantes.cdi` but did not **open** it. CERV-001 opened
`io.vidocq.cervantes.cdi.internal` (the producers) but `JsonWebTokenContext` lives in the parent
package `io.vidocq.cervantes.cdi`, which stayed export-only.

### Fix

- `cervantes-cdi-vauban/module-info`: add `opens io.vidocq.cervantes.cdi to io.vidocq.vauban.core;`
  (alongside the existing `.internal` open; mirrors `knock-cdi-vauban`).

Verified: Arago `docker-compose.localdev.yml` — `GET /api/oidc/me` with a Keycloak Bearer for the
seeded speaker returns **200** with the resolved identity (was 500). MP-JWT 2.1 TCK still 206/206.

---

## CERV-003 — `org.eclipse.microprofile.jwt` module declares no read edges (strict Java Modules)

- **Opening date**: 2026-06-03
- **Status**: ✅ FIXED 2026-06-03

### Symptom

After CERV-002, with Keycloak configured, a request with a valid Bearer 500s the moment the JWT
claims are read:

```
java.lang.IllegalAccessError: class org.eclipse.microprofile.jwt.Claims (in module
org.eclipse.microprofile.jwt) cannot access class jakarta.json.JsonObject (in module jakarta.json)
because module org.eclipse.microprofile.jwt does not read module jakarta.json
```

Does not reproduce on the class-path (the unnamed module reads everything). The next two
runtime accesses (`jakarta.cdi`, `jakarta.inject`) would fail the same way as soon as `@Claim` /
`ClaimLiteral` load.

### Cause

`cervantes-mp-jwt-api` repackages the official MP JWT 2.1 API as an explicit module
`org.eclipse.microprofile.jwt`, but its `module-info` declared **only exports, no requires** (a
deliberate but wrong choice — the comment claimed consumers would declare the spec's real
dependencies). Readability is per-module of the *referencing* class: the spec's OWN bytecode
references `jakarta.json` (`Claims`/`JsonWebToken`), `jakarta.cdi` (`@Claim` is `@Nonbinding`,
`ClaimLiteral extends AnnotationLiteral`) and `jakarta.inject` (`@Claim` is `@Qualifier`). Consumers
reading those modules does not give *this* module the read edge.

### Fix

- `cervantes-mp-jwt-api/module-info`: `requires static jakarta.json; requires static jakarta.cdi;
  requires static jakarta.inject;`. `static` — mandatory only to compile this module-info (resolved
  via three new `provided` deps: champollion-api, jakarta.enterprise.cdi-api, jakarta.inject-api), but
  NOT forced onto consumers (cervantes-api/core use no CDI and must not have to put jakarta.cdi/inject
  on their module path). At runtime the read edge activates whenever the target module is resolved,
  which it always is in a real MP-JWT deployment.
- `cervantes-mp-jwt-api/pom`: add the three `provided` deps above for module-info resolution only.

`requires transitive` was tried first and rejected: it forced jakarta.cdi/inject onto cervantes-api
(no-CDI), failing its compile with "module not found".

Verified: Arago `docker-compose.localdev.yml` — `GET /api/oidc/me` with a Keycloak Bearer returns
**200**. MP-JWT 2.1 TCK still 206/206 (class-path unaffected).

---

## CERV-004 — Unrecognised `mp.jwt.decrypt.key.algorithm` fails every encrypted token at request time

- **Opening date**: 2026-10-04
- **Status**: ✅ FIXED 2026-10-04 (commit c8b4b6c)

### Symptom

With `mp.jwt.decrypt.key.algorithm` set to a value Cervantes does not recognise (for instance
`rsa-oaep`, `RSA-OAEP-512` or `A128KW`), the application started normally, then rejected every
encrypted (JWE) token at request time. No error at startup; the misconfiguration only showed up as
401 responses.

### Minimal repro

Configure a valid `mp.jwt.decrypt.key.location` and `mp.jwt.decrypt.key.algorithm=rsa-oaep`, start
the application, send any JWE bearer token. Found while reviewing the English documentation (FA2).

### Cause

The configured value was compared to the token's `alg` header at each decryption, but never
validated against the supported algorithms (`RSA-OAEP`, `RSA-OAEP-256`) when the validator was built.

### Fix

`JwtAuthConfigProducer.configuredDecryptAlgorithm` checks the value against
`JweDecryptor.SUPPORTED_ALGORITHMS` (exact, case-sensitive names) when the validator is built, and
`CervantesClaimExtension` builds the validator in its `@Validation` phase, so the container fails to
start with a message naming the property, the bad value and the supported list. Covered by
`FailFastStartupTest`.
