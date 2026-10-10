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

## CERV-005 — On a pull request, the CI TCK run tested `main`, not the pull request

- **Opening date**: 2026-10-07
- **Status**: ✅ FIXED 2026-10-07

### Symptom

The pull-request CI renames the reactor version (`versions:set` to `0.4.0-PR<n>.<sha>`), installs
it, then runs `mvn -P tck,tck-official -pl cervantes-tck test`. The TCK passed, but against the
Cervantes `0.4.0-SNAPSHOT` jars published from `main`, not against the pull request.

### Minimal repro

In a copy of the repository: `./mvnw versions:set -DnewVersion=0.4.0-SIMCI -DprocessAllModules=true`,
`./mvnw install -DskipTests`, then
`./mvnw -P tck,tck-official -pl cervantes-tck dependency:list -DincludeGroupIds=io.vidocq.cervantes`:
every Cervantes artifact resolves at `0.4.0-SNAPSHOT`.

### Cause

`cervantes-tck/pom.xml` pinned `<cervantes.version>0.4.0-SNAPSHOT</cervantes.version>`.
`versions:set` only rewrites the project and parent versions, never a property, so the TCK kept
resolving the snapshot. Found because the same pin in humboldt-tck made the humboldt pull-request
CI fail a Telemetry 2.2 test that only the pull request passes.

### Fix

`<cervantes.version>${project.version}</cervantes.version>`: the TCK now resolves the reactor's own
version (same repro gives `0.4.0-SIMCI` everywhere). Unchanged for a local build, where it still
resolves `0.4.0-SNAPSHOT`.

## CERV-006 — `cervantes-cdi-vauban` uses `jakarta.json` without reading it (cervantes#21)

- **Opening date**: 2026-10-08
- **Status**: ✅ FIXED 2026-10-08 (89d1d5c)

### Symptom

`io.vidocq.cervantes.cdi.internal.ClaimResolver` maps claims to JSON-P values (`jakarta.json.Json`,
`JsonValue`, `JsonString`, ...), but the module descriptor of `io.vidocq.cervantes.cdi.vauban` has no
`requires jakarta.json`. `cervantes-core` reads `jakarta.json` only through a non-transitive
`requires`, so nothing gives the module that read edge.

### Minimal repro

Move `cervantes-cdi-vauban/src/main/module-info/module-info.java` to `src/main/java/` and build:
javac compiles the module's code against its descriptor and rejects every `jakarta.json` import
(package not visible). Equivalently, `jar --describe-module` of the produced jar lists no
`requires jakarta.json`.

### Cause

The late module-info workaround compiled `module-info.java` alone at `prepare-package`, after the
code was compiled on the class path, so javac never checked the code against the descriptor and the
missing read edge went unnoticed.

### Fix

- `89d1d5c` — `cervantes-cdi-vauban/module-info`: `requires jakarta.json;`, and the module-info
  moves back to `src/main/java` so javac checks the code against it. `ModuleDescriptorTest` reads
  `target/classes/module-info.class` and asserts the edge; run on `main`'s late-compiled
  descriptor it fails with `must require jakarta.json, requires = [... no jakarta.json ...]`.
- `82bbd31` — the late module-info workaround is removed from `cervantes-core` and
  `cervantes-jaxrs` too (test-only `--add-exports`/`--add-reads` for the `cervantes-jaxrs` test
  doubles), so a missing read edge now fails the build.

Verified: `clean verify` gives the same test counts as `main` per module (cervantes-cdi-vauban
+1, the new test); `jar --describe-module` is identical for every jar except
cervantes-cdi-vauban gaining `requires jakarta.json`; MP JWT 2.2 TCK 208/208 PASS.

## CERV-007 — `@Claim` injection unsatisfied on a class path (cervantes#24)

- **Opening date**: 2026-10-09
- **Status**: ✅ FIXED 2026-10-09

### Symptom

On a class path (Weld SE, an application server such as OpenLiberty, any WAR), a deployment with an
`@Inject @Claim(...)` injection point fails with an unsatisfied dependency: the Cervantes CDI
extension never runs. Under Weld SE, the producers, `JsonWebTokenContext`, the authentication filter
and the `@RolesAllowed` feature are not discovered either.

### Minimal repro

`jar tf cervantes-cdi-vauban-*.jar | grep META-INF`: no
`META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`, and no
`META-INF/beans.xml` in `cervantes-cdi-vauban` nor in `cervantes-jaxrs`.

### Cause

`CervantesClaimExtension` was registered only through `provides` in the module descriptor, which a
class path ignores. Vauban, on the module path, hid the gap; every test and the TCK run on Vauban.
Without `beans.xml` the jars are implicit bean archives, which Weld SE does not scan by default.

### Fix

`META-INF/services/...BuildCompatibleExtension` lists the extension, and both jars ship a
`META-INF/beans.xml` (`bean-discovery-mode="annotated"`). `ClassPathRegistrationTest` checks that the
services file lists exactly what the descriptor provides and that the archive is explicit;
`BeanArchiveTest` does the same for `cervantes-jaxrs`. Both fail on `main` (`missing
target/classes/META-INF/...`). The Weld SE and Open Liberty integration tests are in
`cervantes-it-other-containers` (CERV-008).

## CERV-008 — Cervantes does not deploy under Weld or Open Liberty (cervantes#24)

- **Opening date**: 2026-10-10
- **Status**: ✅ FIXED 2026-10-10

### Symptom

With the CERV-007 fix in, a Weld SE deployment still fails:
`WELD-001408: Unsatisfied dependencies for type JsonWebToken with qualifiers @Default`. Weld first
skips `JsonWebTokenContext`, `JsonWebTokenProducer`, `JwtAuthenticationFilter` and
`RolesAllowedDynamicFeature` with an INFO message:
`WELD-000119: ... Type io.vidocq.vauban.api.ProxyLink not found`. Open Liberty fails the same way
(`CWWKZ0002E`).

### Minimal repro

`cervantes-it-weld` (`WeldPortabilityTest`) on `main`; on Liberty, `cervantes-it-openliberty` with
`vauban-api` excluded from the WAR: 5/5 red.

### Cause

The Vauban build weaves a `protected <init>(io.vidocq.vauban.api.ProxyLink)` entry constructor into
every normal-scoped bean, and `vauban-api` was `provided` (`requires static`), so it is absent
outside Vauban. Same cause as Knock's BUG-20261010-01 and Heisenberg's BUG-006.

### Fix

`vauban-api` is a runtime dependency of `cervantes-cdi-vauban` and `cervantes-jaxrs` (plain
`requires`), with its Jakarta CDI dependencies excluded. Covered by `cervantes-it-weld` (5 tests) and
`cervantes-it-openliberty` (5 tests over HTTP).
