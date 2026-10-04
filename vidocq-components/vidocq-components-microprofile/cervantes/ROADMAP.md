# ROADMAP — Cervantes (MicroProfile JWT 2.2)

Source of truth for milestones and TCK score. TDD at each step (Red → Green → Refactor).

## Milestones

- [x] **M0 — Skeleton & green build.** Repo, parent pom (Model 4.1.0, `vidocq-parent:1.0.0`,
  `<subprojects>`), `cervantes-mp-jwt-api` (repackaging spec MP JWT 2.2), modules
  `api/core/cdi-vauban/cassini/bench/examples`, CI (`.forgejo/workflows`), docs, `.sdkmanrc`.
  Criterion: `./mvnw -ntp install -DskipTests` green.
- [x] **M1 — Core, signed tokens.** base64url decoding + JSON parsing (Champollion),
  `JwtSignatureVerifier` (RS/ES 256/384/512 ; ECDSA JOSE signature transcoding R‖S ⇄ DER),
  `JwtClaimsValidator` (iss/aud/exp/nbf/iat + clock-skew), `ConfiguredKeyResolver` + `PemKeys`,
  `DefaultJsonWebToken`. **12 green tests** (RSA, EC P-256, expiration, nbf, forged signature,
  iss/aud, unsigned, clock-skew). `cervantes-api` + `cervantes-core` move out of stub mode
  (skip deploy/javadoc removed).
- [x] **M2 — JWKS & rotation.** `JwkParser` (RSA `n`/`e`, EC `crv`/`x`/`y` via JCA), `JwksSource`
  (JDK `HttpClient` / file), `JwksKeyResolver` (cache, TTL refresh, rotation on unknown `kid`
  bounded by `minRefreshInterval`, snapshot fallback if fetch fails, unique key without `kid`).
  VT-friendly concurrency (`ReentrantLock` + `AtomicReference`). **+9 tests (21 total in core).**
- [x] **M3 — CDI (`cervantes-cdi-vauban`).** `JsonWebTokenContext` (`@RequestScoped`, set by the
  M4 filter), `JsonWebTokenProducer` (`@Produces @RequestScoped JsonWebToken`, anonymous principal if
  absent), `JwtAuthConfigProducer` (reads `mp.jwt.verify.*` via Ravel → produces the `JwtValidator`),
  public `KeyResolvers` factory (core: inline PEM / PEM file / JWKS file or URL),
  `DefaultJsonWebToken.anonymous()`. **8 tests** (unit + Vauban container integration).
- [x] **M3b — Typed `@Claim` injection (synthetic BCE beans).** `CervantesClaimExtension`
  (`BuildCompatibleExtension` : `@Registration` collects the types of `@Claim` injection points,
  `@Synthesis` registers a `SyntheticBean` `@Dependent` qualified `@Claim` by type — `@Claim` having
  `value`/`standard` `@Nonbinding`, one bean per type covers all sites), `ClaimSyntheticCreator`
  (`SyntheticBeanCreator` → `InjectionPoint` + current `JsonWebTokenContext`), `ClaimResolver`
  (extraction/conversion: `String`, `Long`, `Integer`, `Boolean`, `Double`, `Set<String>`, `jakarta.json`
  types `JsonValue`/`JsonString`/`JsonNumber`/`JsonObject`/`JsonArray`, eager `Optional<T>`,
  lazy `ClaimValue<T>`/`Provider<T>`/`Supplier<T>` that reread the current token), `ClaimValueImpl`,
  `DefaultJsonWebToken.rawClaim()` (raw JSON value). Model = `ravel/ConfigCdiExtension` (unlocked
  by VAU-BCE-001). `provides BuildCompatibleExtension`. **+2 tests** (embedded Vauban integration,
  complete type matrix including primitive `boolean` field, + lazy multi-scope). ✅ **`@Claim` on a
  primitive field** works (boxing to wrapper type, Ravel pattern) **thanks to the Vauban fix
  VAU-INJ-PRIM** (branch `pr/ybl/jwt-needs` : `TypeMapper` exposed a primitive injection point as
  `ClassType[boolean]` instead of a `PrimitiveType`, so boxing did not trigger). ⚠️ **M3b therefore
  depends on a Vauban ≥ the SNAPSHOT containing VAU-INJ-PRIM** — push/publish the Vauban fix before
  the Cervantes M3b CI run, otherwise `@Claim boolean` injection fails silently.
- [x] **M4 — JAX-RS security (`cervantes-cassini`).** `JwtSecurityContext` (backed by the
  `JsonWebToken`), `JwtAuthenticationFilter` (`@PreMatching` `@Priority(AUTHENTICATION)` : extracts
  the Bearer token, validates, `setSecurityContext` + sets the `JsonWebTokenContext`; 401 if invalid;
  anonymous if absent), `RolesAllowedDynamicFeature` + `RolesAllowedRequestFilter`
  (`@RolesAllowed`/`@PermitAll`/`@DenyAll`, method precedence over class, 401/403). **13 tests**
  (JAX-RS doubles). **Pure standard JAX-RS API — NO Cassini modification required**
  (enforcement goes through `setSecurityContext`/`getSecurityContext` that `CassiniRequestContext`
  already honors). ⏳ Injecting `@Context SecurityContext` *inside a resource* reflecting the JWT will
  require a Cassini patch (`FieldInjector`) — deferred to M6/TCK (branch `pr/ybl/jwt-needs` on
  Cassini at that time). `@LoginConfig`: application marker, not needed here.
- [x] **M5 — JWE (encrypted tokens).** `JweDecryptor` (unwraps `RSA-OAEP`/`RSA-OAEP-256`,
  decrypts `A256GCM`, via `javax.crypto`), `PemKeys.privateKeyFromPem` (PKCS#8), `Jwe` facade,
  JWE detection (5 parts) + decryption in `DefaultJwtValidator`, wiring
  `mp.jwt.decrypt.key`/`.location` in `JwtAuthConfigProducer`. **+6 tests.** ℹ️ Content-enc
  `A128CBC-HS256` not yet supported (only `A256GCM`, the spec default) — add it if the TCK requires it.
- [x] **M6 — Official MP JWT 2.1 TCK: 206/206 PASS.** ✅ `cervantes-tck` module outside the reactor
  (Model 4.0.0 standalone, heisenberg-tck template), `CervantesJwtDeployableContainer` Arquillian
  (`Vauban` CDI + Cassini HTTP per deployment: ShrinkWrap archive → bean classes, archive MP-Config →
  system props, rewrite `*.location` `http://localhost:8080` → real ephemeral URL after server
  startup), `run-official-tck-mp-jwt-2.1.sh` (renamed to 2.2 on the MP 7.2 bump). **206 tests, 0 failure, 0 skip**
  (verified on a clean run). Documented exclusions (not applicable to the Core+JWT profile): *servlet*
  container tests (`…/tck/container/servlet/**`) and TestNG group `ee-security-optional`.
  - Delivered prerequisites: **cassini** patch `@Context SecurityContext` (merged on main, REST TCK 4.0
    2535 PASS preserved) + **vauban** fix VAU-INJ-PRIM (merged).
  - Spec gaps revealed by the TCK and fixed in cervantes-core/-cdi-vauban: `KeyResolvers` (lazy HTTP +
    PEM/JWKS autodetection + post-start URL reread), `JwksSource` (Accept header + classpath resolution
    §9.2.2), `JwkParser` (JWK/CRT private key), `Jwe`/`JweDecryptor` (required algorithm + `cty=JWT`
    check), `ClaimResolver` (`raw_token`→JsonString, simple `aud`→array), `CervantesClaimExtension`
    (unwrap `Provider<T>`/`Instance<T>`), `JwtAuthenticationFilter` (token/cookie extraction + errors),
    `DefaultJsonWebToken` (anonymous token → null per claim).
- [x] **M7 — Bench & examples.** `cervantes-bench` (JMH `DefaultJwtValidator.validate`, RS256/RS512 ;
  SmallRye JWT baseline behind the opt-in profile `-Pcompare-smallrye` → `BENCH.md`).
  `cervantes-examples` (`ProtectedResource` : `@RolesAllowed` + `@Inject @Claim`, demonstrated under
  embedded Vauban).
- [x] **M8 — Runtime wrapper (repo `vidocq`).** `vidocq-runtime-cervantes-jwt-extension`
  (`VidocqExtension`, priority ~500), added to reactor `vidocq-runtime-core-extensions` + jlink +
  IT `vidocq-runtime-it-cervantes-jwt`. (PR vidocq #1 merged.)

## TCK score

| Date | Suite | PASS | FAIL | SKIP | Note |
|------|-------|------|------|------|------|
| 2026-05-26 | MP JWT 2.1 | 206 | 0 | 0 | 100 % ; exclusions : container servlet + group `ee-security-optional` |
| 2026-10-04 | MP JWT 2.2 | 208 | 0 | 0 | 100 % ; +2 tests (`RsaAndEcSignatureAlgorithmTest`) ; EJB/JACC/Servlet container tests removed upstream, only group `ee-security-optional` still excluded |

## Actioned decisions

- **2026-05-26** — Modules `core/cdi-vauban/cassini/bench/examples` created as empty stubs at M0 with
  `maven.deploy.skip` + `maven.javadoc.skip` (the deploy profile javadoc canary fails on a module
  without a public API, cf. heisenberg-examples). These flags are removed as soon as the module
  gets its first public classes (M1/M3/M4); `bench`/`examples` keep them.
- **2026-05-26** — `cervantes-mp-jwt-api` repackages the spec with a module-info declaring only
  `exports` (zero `requires`); consumers declare their own `requires`
  (`jakarta.json`, `jakarta.cdi`). Same approach as `heisenberg-mp-ft-api`. Selected module name:
  `org.eclipse.microprofile.jwt`.
- **2026-05-26** — `cervantes-core` Java Modules trick: cleaning `module-info.class` is tied to
  `process-sources` (and not `generate-test-sources` as in heisenberg) so incremental builds
  without `clean` do not flip `default-compile` into module mode. No-op on a clean build.
- **2026-05-26** — Current Vauban defect: a `@Produces` bean whose PRODUCER is normal-scoped
  (`@ApplicationScoped`) is resolved incorrectly (the producer proxy is cast to the product type →
  `ClassCastException`). Workaround: both producer AND product are `@Dependent` (the
  `RavelConfigProducer` pattern). `JwtAuthConfigProducer.jwtValidator()` will move back to
  `@ApplicationScoped` once the Vauban defect is fixed.
- **2026-05-26** — Vauban gap (M3b) **FIXED** (VAU-INJ-PRIM, branch `pr/ybl/jwt-needs` on vauban):
  primitive-field `@Claim` injection failed because `TypeMapper` exposed a primitive injection point
  as `ClassType[name=boolean]` instead of a `PrimitiveType` → `instanceof PrimitiveType`
  false in the BCE → no boxing → synthetic bean registered incorrectly → empty (silent) resolution.
  Fix: `TypeMapper.map` maps a primitive-named `ClassType` to `VaubanPrimitiveType` + regression test
  `PrimitiveQualifiedInjectionTest`. Verified end-to-end (cervantes `@Claim boolean` green).
  The Cervantes extension returned to simple boxing (Ravel pattern). **Dependency**: Cervantes M3b
  requires a Vauban build containing this fix.
- **2026-07-15** — TCK harmonisation: `cervantes-tck` moves **in-reactor behind the `tck` Maven
  profile** of `cervantes-parent` (vidocq-runtime-tck-* pattern), replacing the earlier
  out-of-reactor decision (M6, standalone Model 4.0.0 POM without `<parent>`). The original
  ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 constraint is obsolete since the workspace migrated
  to Maven 3.9.16 / Model 4.0.0. A plain `mvn install` still skips the runner; activation via
  `run-official-tck-mp-jwt-2.2.sh` or `./mvnw -P"tck,smoke|tck-official" -pl cervantes-tck test`.
  Verified: reactor build green without the profile, smoke PASS, full suite 206/206 PASS.
