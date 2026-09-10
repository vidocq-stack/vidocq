# MicroProfile 7.2 Upgrade — Implementation Plan (Vidocq 0.3.0-SNAPSHOT)

> **STATUS: ON HOLD (decision 2026-08-27).** Vidocq targets *certification*, not early adoption. This plan is **not to be executed** until MicroProfile 7.2 is actually released: Eclipse release review done, `org.eclipse.microprofile:microprofile:7.2` on Maven Central, OpenAPI `4.2` final and Telemetry `2.2` TCKs published. As of 2026-08-27 only the Eclipse *planning* record exists (dated 2026-07-21, no release review) — JWT 2.2 is final, OpenAPI is at 4.2-RC2, Telemetry 2.2 has no artifact. Re-check with `node ~/.claude/jobs/7e5f64ca/tmp/central.mjs` (or the Central metadata URLs in §0.2) before reopening.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Vidocq 0.3.0-SNAPSHOT line from MicroProfile 7.1 to MicroProfile 7.2 — implement the three updated component specs (JWT Auth 2.2, OpenAPI 4.2, Telemetry 2.2) in their bricks (cervantes, grimm, humboldt), re-run every official TCK on the assembled runtime, and sweep the documentation.

**Architecture:** MicroProfile 7.2 (Eclipse *planning* record dated 2026-07-21 — not yet ratified) changes exactly three components; the other five (Config 3.1, Fault Tolerance 4.1, Health 4.0, Rest Client 4.0, Jakarta EE Core Profile) are unchanged, so ravel/heisenberg/knock/cyrano/vauban/cassini/champollion need **no code change**. Each affected brick gets a small, TDD-driven behavioural change plus a TCK-version bump; the vidocq runtime then bumps its in-reactor `-Ptck` runners and its docs. The upgrade is **staged by artifact availability**: JWT 2.2 is final on Central, OpenAPI 4.2 is only at RC2, Telemetry 2.2 has no published TCK yet — the plan ships everything that can be verified now and leaves two explicit follow-up bumps.

**Tech Stack:** Java 25 (Temurin), Maven 3.9.16, JPMS strict, Arquillian/TestNG TCK harnesses, OpenTelemetry Java API 1.64.0 (shaded into `humboldt-otel-api` via maven-shade + ModiTect), MicroProfile APIs from Maven Central.

**Spec:** this document is self-contained — the facts section below *is* the spec (verified against Eclipse/Maven Central/GitHub on 2026-08-27). Upstream references: MP 7.2 release record `https://projects.eclipse.org/projects/technology.microprofile/releases/microprofile-7.2`; OpenAPI 4.2 spec `https://download.eclipse.org/microprofile/microprofile-open-api-4.2-RC2/microprofile-openapi-spec-4.2-RC2.html` (§8.1 release notes); JWT 2.2 tag `microprofile/microprofile-jwt-auth@2.2` (`spec/src/main/asciidoc/configuration.asciidoc`); Telemetry 2.2 milestone 3 (`microprofile/microprofile-telemetry` issues #318, #321, PR #319).

---

## 0. Verified facts (2026-08-27) — the release train had NOT left yet

### 0.1 What MicroProfile 7.2 contains

| Component | MP 7.1 | MP 7.2 | Change |
|---|---|---|---|
| Config | 3.1 | 3.1 | none |
| Fault Tolerance | 4.1 | 4.1 | none |
| Health | 4.0 | 4.0 | none (Health 4.1 exists as an Eclipse record but was never published to Central; not in the platform) |
| JWT Auth (RBAC) | 2.1 | **2.2** | when `mp.jwt.verify.publickey.algorithm` is **not** set, **both RS256 and ES256 MUST be accepted** (spec §configuration). API jar is binary-identical to 2.1 (0 class diff). TCK adds `RsaAndEcSignatureAlgorithmTest` (`testRS256Token`, `testES256Token`) backed by `META-INF/microprofile-config-rsa-ec.properties` (`mp.jwt.verify.publickey.location=/rs256es256.jwk`, no algorithm) and a JWKS holding one RSA key (`kid=rskey`) and one EC key (`kid=eckey`). TCK **removes** the `container/ejb`, `container/jacc`, `container/servlet` packages. |
| OpenAPI | 4.1 | **4.2** | API: `@Header` gains `example()` and `examples()`; `Schema` now overrides all `Extensible` methods (`getExtensions/setExtensions/addExtension/removeExtension/hasExtension/getExtension`) with schema-specific semantics — "for the base OAS 3.1 dialect, Schema instances consider all unknown properties to be extensions"; `@Header.allowEmptyValue` is `@Deprecated`; `@ExternalDocumentation` on `TYPE` is deprecated (annotation still targets METHOD+TYPE). Spec: Bean Validation `@Digits` must be processed. TCK adds `ExternalDocumentationAnnotationTest` (1 test: method-level `@ExternalDocumentation` → `paths.'/a'.get.externalDocs`) and `SchemaExtensionPropertyTest` (6 pure-model tests), and extends `AirlinesAppTest` (header `example`/`examples`) and `beanvalidation.BeanValidationTest` (`@Digits`). |
| Rest Client | 4.0 | 4.0 | none |
| Telemetry | 2.1 | **2.2** | "Adopt the latest OpenTelemetry" (issue #318: spec repo pins `opentelemetry.java.version=1.64.0`, `opentelemetry.java.instrumentation.version=2.30.0`, `version.otel.semconv-java=1.43.0`) and **`code.function.name` MUST be present on `@WithSpan` spans** (issue #321 / PR #319). TCK assertion (`RestClientSpanTest.spanChild`): attribute value = `org.eclipse.microprofile.telemetry.tracing.tck.rest.RestClientSpanTest$SpanBean.spanChild`, i.e. **`Class.getName()` (binary name, `$` for nested) + "." + method name**. Instrumentation-annotations 2.30 adds `WithSpan.inheritContext()` (default `true`). |
| Jakarta EE Core Profile | 10 min. | 10 min., **11 explicitly allowed** | Vidocq already targets Core Profile 11 (CDI 4.1 Lite, REST 4.0, Annotations 3.0, Interceptors 2.2) — now spec-endorsed. |

Out of scope: MicroProfile GraphQL 2.1 (released 2026-08-07, standalone, not part of the platform).

### 0.2 Artifact availability on Maven Central (2026-08-27)

| Artifact | Latest on Central | Status for this plan |
|---|---|---|
| `org.eclipse.microprofile.jwt:microprofile-jwt-auth-api` / `-tck` | **2.2** (2026-06-24) | final — bump now |
| `org.eclipse.microprofile.openapi:microprofile-openapi-api` / `-tck` | **4.2-RC2** (2026-06-30) — no 4.2 final yet | bump to RC2 now, follow-up to 4.2 |
| `org.eclipse.microprofile.telemetry:microprofile-telemetry-{tracing,metrics,logs}-tck` | **2.1** — no 2.2, no 2.2-RC, no git tag | implement now, TCK bump blocked (see Task C4) |
| `org.eclipse.microprofile:microprofile` (platform BOM) | 7.1 | 7.2 not published; Vidocq does not import the BOM — no action |
| `io.opentelemetry:opentelemetry-bom` | 1.65.0 | align on **1.64.0** (the version the Telemetry 2.2 TCK pins) |
| `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations` | 2.31.1 | align on **2.30.0** |
| `io.opentelemetry.semconv:opentelemetry-semconv` | 1.43.0 | **1.43.0** (stable artifact, replaces `1.27.0-alpha`; TCK-only) |
| Config 3.1.1, FT 4.1.2, Health 4.0.1, Rest Client 4.0 | — | unchanged (FT api 4.1.2 is an optional CVE-only bump, not required) |

### 0.3 OpenTelemetry API 1.39.0 → 1.64.0 impact on humboldt (verified with `javap`)

- **Zero abstract methods added** on the 27 API interfaces humboldt implements (`Tracer`, `TracerProvider`, `Span`, `SpanBuilder`, `Meter`, `MeterProvider`, all `*Counter/*Histogram/*Gauge(+Builder)`, `Observable*Measurement`, `Logger`, `LoggerProvider`, `LogRecordBuilder`) nor on `io.opentelemetry.context.{Scope,ContextStorage,ContextStorageProvider}`. The bump is source-compatible.
- Only new package in `opentelemetry-api` is internal (`io.opentelemetry.api.impl`) — the ModiTect `module-info.java` of `humboldt-otel-api` needs no new `exports`.
- `opentelemetry-semconv` 1.43.0 provides `io.opentelemetry.semconv.CodeAttributes.CODE_FUNCTION_NAME` (`code.function.name`) — humboldt does **not** depend on semconv at runtime (plain `AttributeKey` constants), keep it that way.

### 0.4 Current state of the workspace (all on `main`, clean except 2 untracked files in `vidocq/`)

| Repo | Pinned today | Target |
|---|---|---|
| `cervantes` | `microprofile.jwt.version=2.1` (root pom l.62, `cervantes-tck/pom.xml` l.36), script `run-official-tck-mp-jwt-2.1.sh`, TCK 206/206 | 2.2, 208 expected |
| `grimm` | `version.mp.openapi=4.1` (root pom l.72), `grimm-tck/pom.xml` `microprofile.openapi.version=4.1` (l.21), script `run-official-tck-mp-openapi-4.1.sh`, TCK 349/349 | 4.2-RC2 → 4.2, 356 expected (349 + 1 + 6) |
| `humboldt` | `microprofile.telemetry.version=2.1`, `opentelemetry.version=1.39.0`, `opentelemetry.instrumentation.version=2.7.0`, `opentelemetry.semconv.version=1.27.0-alpha` (root pom l.64-67, `humboldt-tck/pom.xml` l.47-52), script `run-official-tck-telemetry-2.1.sh`, TCK 85/85 | OTel 1.64.0 / 2.30.0 / 1.43.0 now; Telemetry TCK 2.2 when published |
| `vidocq` | runners: `vidocq-runtime-tck-cervantes-jwt` (jwt tck 2.1), `vidocq-runtime-tck-grimm-openapi` (openapi tck **4.0.2** — stale even for 7.1, comment says "MP 7.1 pins OpenAPI 4.0" which is wrong), `vidocq-runtime-tck-humboldt-telemetry` (telemetry 2.1, OTel 1.39.0/2.7.0/1.27.0-alpha); `TCK.md` table titled "MicroProfile 7.1", 1837 green | 2.2 / 4.2-RC2 / 2.1+OTel 1.64; docs say 7.2 |

---

## Global Constraints

- **Toolchain:** JDK 25 (Temurin) + Maven 3.9.16, pinned via `.sdkmanrc` in each sub-project — run `sdk env` in the sub-project before building (`sdk env` breaks under `set -u`; invoke `mvn`/`./mvnw` directly inside scripts). All POMs `modelVersion 4.0.0`; every artifact is `0.3.0-SNAPSHOT`.
- **Build hygiene:** always `./mvnw clean install` / `clean test`, never bare `install` or an isolated `test` — codegen modules give false failures on stale `target/`. Never `-U`; `-o` only when you know the local M2 is complete. Bricks must be `clean install`ed into the local M2 **before** the vidocq runtime is built (it resolves `cervantes/grimm/humboldt:0.3.0-SNAPSHOT` from M2).
- **JPMS strict:** every production module keeps its `module-info.java`; TCK runner modules carry no `module-info.java` (unnamed). No new `opens`. No new runtime dependency — the only dependency changes in this plan are version bumps of already-present Jakarta/MicroProfile/OpenTelemetry artifacts (`dependency-gatekeeper` agent must review every `pom.xml` diff anyway).
- **TDD:** each behavioural change starts with a failing unit test in the brick, then the official TCK is the integration gate. Run every TCK **yourself** and read the surefire/TestNG summary — never trust an agent's "green" report (known false positives in both directions). Keep raw logs under `/Users/yblazart/.claude/jobs/7e5f64ca/tmp/`.
- **Language:** all code, comments, Javadoc, test names, commit messages, `.md`/`.adoc` files in **English**. Chat with the maintainer in French.
- **Commits:** Conventional Commits, GPG-signed, DCO `Signed-off-by` (`git commit -S --signoff`), and — per the workspace `CLAUDE.md` AI-policy — the provenance trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` plus `Claude-Session: https://claude.ai/code/session_01EryQo9ghKqzz48nq5yKzbe`. Author/committer stay the human maintainer.
- **Branches / merge order:** branch `pr/ybl/mp-7.2` in `cervantes`, `grimm`, `humboldt` (independent, can be worked in parallel), then `pr/ybl/mp-7.2` in `vidocq` (depends on the three bricks being merged and published as SNAPSHOT, or installed locally). Docs PR in `vidocq-docs` last. PRs on Codefloe (`https://codefloe.com/Vidocq/<repo>`); the `governance-checks` gate needs the CLA handle `@yblazart`, GPG and sign-off.
- **Traceability:** any TCK failure that turns out to be a real Vidocq bug goes to the brick's `BUG.md` (`/log-bug`); no performance claims in this plan.
- **Deprecations are not errors:** no `-Werror`/`failOnWarning` is configured in vidocq-parent, grimm, humboldt or cervantes — `@Header.allowEmptyValue` (deprecated in 4.2) keeps being mapped; add `@SuppressWarnings("deprecation")` only on the four call sites listed in Task B3 to keep the build log clean.

---

## File structure (what changes where)

**cervantes** (`/Users/yblazart/projects/perso/vidocq/cervantes`)
- Modify `cervantes-core/src/main/java/io/vidocq/cervantes/internal/PemKeys.java` — add family-agnostic `fromPem(String)`.
- Modify `cervantes-core/src/main/java/io/vidocq/cervantes/internal/KeyResolvers.java` — `Optional<Family>` instead of `Family` (`fromInlinePem`, `fromLocation`, `LazyHttpKeyResolver`).
- Modify `cervantes-api/src/main/java/io/vidocq/cervantes/api/JwtConfig.java` — new component `Optional<SignatureAlgorithm> requiredAlgorithm`.
- Modify `cervantes-core/src/main/java/io/vidocq/cervantes/internal/DefaultJwtValidator.java` — enforce the configured algorithm family.
- Modify `cervantes-cdi-vauban/src/main/java/io/vidocq/cervantes/cdi/internal/JwtAuthConfigProducer.java` — `buildKeyResolver`/`buildConfig`.
- Create `cervantes-core/src/test/java/io/vidocq/cervantes/internal/PemKeysTest.java`; modify `DefaultJwtValidatorTest.java`, `cervantes-cdi-vauban/src/test/.../JwtAuthConfigProducerTest.java`.
- Modify `pom.xml`, `cervantes-tck/pom.xml`, rename `run-official-tck-mp-jwt-2.1.sh` → `run-official-tck-mp-jwt-2.2.sh`, `TCK.md`, `README.md`, `docs/en/modules/ROOT/pages/{reference,concepts,migration}.adoc`.

**grimm** (`/Users/yblazart/projects/perso/vidocq/grimm`)
- Modify `pom.xml`, `grimm-tck/pom.xml`, rename `run-official-tck-mp-openapi-4.1.sh` → `run-official-tck-mp-openapi-4.2.sh`, `README.md`, `TCK.md`, docs.
- Modify `grimm-core/src/main/java/io/vidocq/grimm/internal/model/SchemaImpl.java` — extension semantics over `extraProperties`; `setAll` routes through `set`.
- Modify `grimm-core/src/main/java/io/vidocq/grimm/internal/serialization/OpenApiValueMapper.java` — emit schema extensions exactly once.
- Modify `grimm-core/src/main/java/io/vidocq/grimm/internal/scanner/AnnotationScanner.java` and `JaxRsResourceScanner.java` — `@Header.example/examples`, method-level `@ExternalDocumentation`.
- Modify `grimm-core/src/main/java/io/vidocq/grimm/internal/serialization/OpenApiModelMapper.java` — header `example`/`examples` from static documents.
- Modify `grimm-core/src/main/java/io/vidocq/grimm/internal/schema/BeanValidationMapper.java` — `@Digits`.
- Create `grimm-core/src/test/java/io/vidocq/grimm/internal/model/SchemaImplExtensionsTest.java`, `grimm-core/src/test/java/io/vidocq/grimm/internal/schema/BeanValidationMapperTest.java`; modify `AnnotationScannerTest.java`, `JaxRsResourceScannerTest.java`.

**humboldt** (`/Users/yblazart/projects/perso/vidocq/humboldt`)
- Modify `pom.xml` (l.65-67), `humboldt-tck/pom.xml` (l.50-52) — OTel versions.
- Modify `humboldt-cdi/src/main/java/io/vidocq/humboldt/cdi/WithSpanInterceptor.java` — `code.function.name`, `inheritContext`.
- Modify `humboldt-cdi/src/test/java/io/vidocq/humboldt/cdi/WithSpanInterceptorTest.java`.
- `README.md`, `TCK.md`, docs (`docs/en/modules/ROOT/pages/{index,concepts,getting-started,internals}.adoc`) — "Telemetry 2.2" once the TCK is green.

**vidocq** (`/Users/yblazart/projects/perso/vidocq/vidocq`)
- Modify `vidocq-runtime-integration-tests/vidocq-runtime-tck-cervantes-jwt/pom.xml`, `.../vidocq-runtime-tck-grimm-openapi/pom.xml`, `.../vidocq-runtime-tck-humboldt-telemetry/pom.xml`, `vidocq-runtime-integration-tests/TCK.md`.
- Modify `README.md`, `ROADMAP.md`, `CLAUDE.md`, `AGENTS.md`, `CERTIFICATION.md` (one sentence), `docs/en/modules/ROOT/pages/whats-new.adoc`, `docs/en/modules/ROOT/pages/tck.adoc`.

**vidocq-docs** — `content/home/modules/ROOT/pages/{index,roadmap}.adoc`. **vidocq-workspace** — `CLAUDE.md` (TCK section mentions "MicroProfile 7.1"). **knock** — `CONTRIBUTING.md` (one mention). **humboldt** — `PLAN.md` (historical, leave).

---

## Phase A — Cervantes: MicroProfile JWT Auth 2.2

### Task A1: family-agnostic PEM public-key parsing

**Files:**
- Modify: `cervantes/cervantes-core/src/main/java/io/vidocq/cervantes/internal/PemKeys.java:41-55`
- Create: `cervantes/cervantes-core/src/test/java/io/vidocq/cervantes/internal/PemKeysTest.java`

**Interfaces:**
- Produces: `public static PublicKey PemKeys.fromPem(String pem)` — auto-detects RSA vs EC from the SubjectPublicKeyInfo (tries `KeyFactory "RSA"` then `"EC"`); the existing `fromPem(String, SignatureAlgorithm.Family)` is kept unchanged.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** MP JWT 2.2: without a configured algorithm both RSA and EC PEM keys must load. */
class PemKeysTest {

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    void autoDetectsRsaPem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair rsa = g.generateKeyPair();
        assertInstanceOf(RSAPublicKey.class, PemKeys.fromPem(pem(rsa.getPublic())));
    }

    @Test
    void autoDetectsEcPem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ec = g.generateKeyPair();
        assertInstanceOf(ECPublicKey.class, PemKeys.fromPem(pem(ec.getPublic())));
    }

    @Test
    void explicitFamilyStillRejectsMismatch() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        String ecPem = pem(g.generateKeyPair().getPublic());
        assertThrows(JwtValidationException.class,
                () -> PemKeys.fromPem(ecPem, SignatureAlgorithm.Family.RSA));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(JwtValidationException.class,
                () -> PemKeys.fromPem("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"));
    }
}
```

- [ ] **Step 2: Run it, expect compilation failure** (`fromPem(String)` does not exist)

Run: `cd cervantes && ./mvnw -q -pl cervantes-core clean test -Dtest=PemKeysTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD FAILURE — `cannot find symbol: method fromPem(java.lang.String)`.

- [ ] **Step 3: Implement**

In `PemKeys.java`, factor the DER extraction and add the overload:

```java
    /** Strips the PEM armour and decodes the base64 body. */
    private static byte[] derOf(String pem) throws JwtValidationException {
        String base64 = pem
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException("invalid PEM public key", e);
        }
    }

    public static PublicKey fromPem(String pem, SignatureAlgorithm.Family family) throws JwtValidationException {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(derOf(pem));
        String algorithm = switch (family) {
            case RSA -> "RSA";
            case EC -> "EC";
        };
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(spec);
        } catch (GeneralSecurityException e) {
            throw new JwtValidationException("invalid PEM public key", e);
        }
    }

    /**
     * Family-agnostic variant (MP JWT 2.2 §"Supported Signature Algorithms": when
     * {@code mp.jwt.verify.publickey.algorithm} is not set, both RS256 and ES256 must be
     * accepted). Tries the RSA {@link KeyFactory} first, then EC.
     */
    public static PublicKey fromPem(String pem) throws JwtValidationException {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(derOf(pem));
        GeneralSecurityException last = null;
        for (String algorithm : new String[] {"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(spec);
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw new JwtValidationException("invalid PEM public key (neither RSA nor EC)", last);
    }
```

- [ ] **Step 4: Run the test, expect PASS**

Run: `cd cervantes && ./mvnw -q -pl cervantes-core clean test -Dtest=PemKeysTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
cd cervantes && git checkout -b pr/ybl/mp-7.2
git add cervantes-core/src/main/java/io/vidocq/cervantes/internal/PemKeys.java cervantes-core/src/test/java/io/vidocq/cervantes/internal/PemKeysTest.java
git commit -S --signoff -m "feat(core): auto-detect RSA/EC family when parsing PEM public keys (MP JWT 2.2)"
```

### Task A2: accept both RS256 and ES256 when no algorithm is configured; enforce it when it is

**Files:**
- Modify: `cervantes/cervantes-api/src/main/java/io/vidocq/cervantes/api/JwtConfig.java:47-80`
- Modify: `cervantes/cervantes-core/src/main/java/io/vidocq/cervantes/internal/KeyResolvers.java:46,80,105-120`
- Modify: `cervantes/cervantes-core/src/main/java/io/vidocq/cervantes/internal/DefaultJwtValidator.java:70-76`
- Modify: `cervantes/cervantes-cdi-vauban/src/main/java/io/vidocq/cervantes/cdi/internal/JwtAuthConfigProducer.java:109-136`
- Test: `cervantes/cervantes-core/src/test/java/io/vidocq/cervantes/internal/DefaultJwtValidatorTest.java`, `cervantes/cervantes-cdi-vauban/src/test/java/io/vidocq/cervantes/cdi/internal/JwtAuthConfigProducerTest.java`

**Interfaces:**
- Consumes: `PemKeys.fromPem(String)` from A1.
- Produces: `JwtConfig` record gains a 7th component `Optional<SignatureAlgorithm> requiredAlgorithm` (last position); `KeyResolvers.fromInlinePem(String, Optional<SignatureAlgorithm.Family>)` and `KeyResolvers.fromLocation(String, Optional<SignatureAlgorithm.Family>)` replace the `Family` overloads; `JwtAuthConfigProducer.buildConfig(Config)` fills `requiredAlgorithm` from `mp.jwt.verify.publickey.algorithm`.

- [ ] **Step 1: Write the failing tests**

In `DefaultJwtValidatorTest` (it already generates `RSA` and `EC` key pairs in `@BeforeAll` and has helpers building signed tokens; reuse them — the existing test `validatesEs256Token`-style helper names may differ, mirror whatever the file uses to build an ES256 token):

```java
    @Test
    void acceptsEs256AndRs256WhenNoAlgorithmConfigured() throws Exception {
        JwtConfig config = new JwtConfig(Optional.of(ISS), Set.of(AUD), Duration.ofSeconds(60),
                true, Optional.empty(), false, Optional.empty());
        KeyResolver both = (kid, alg) -> Optional.of(
                alg.family() == SignatureAlgorithm.Family.EC ? EC.getPublic() : RSA.getPublic());
        DefaultJwtValidator validator = new DefaultJwtValidator(both, config, CLOCK, null);

        assertNotNull(validator.validate(TestJwts.signed(RSA, SignatureAlgorithm.RS256, ISS, AUD, NOW)));
        assertNotNull(validator.validate(TestJwts.signed(EC, SignatureAlgorithm.ES256, ISS, AUD, NOW)));
    }

    @Test
    void rejectsTokenWhoseAlgorithmFamilyDiffersFromConfiguredOne() throws Exception {
        JwtConfig config = new JwtConfig(Optional.of(ISS), Set.of(AUD), Duration.ofSeconds(60),
                true, Optional.empty(), false, Optional.of(SignatureAlgorithm.ES256));
        KeyResolver rsaOnly = (kid, alg) -> Optional.of(RSA.getPublic());
        DefaultJwtValidator validator = new DefaultJwtValidator(rsaOnly, config, CLOCK, null);

        JwtValidationException ex = assertThrows(JwtValidationException.class,
                () -> validator.validate(TestJwts.signed(RSA, SignatureAlgorithm.RS256, ISS, AUD, NOW)));
        assertTrue(ex.getMessage().contains("ES256"));
    }
```

(If `TestJwts` has no `signed(KeyPair, SignatureAlgorithm, iss, aud, now)` helper, add one there — it is test code — using the same JWS builder the other tests use.)

In `JwtAuthConfigProducerTest`:

```java
    @Test
    void noAlgorithmMeansBothFamiliesAccepted() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        String ecPem = pem(g.generateKeyPair().getPublic()); // same helper as PemKeysTest, copy it here
        Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.publickey", ecPem));

        KeyResolver resolver = JwtAuthConfigProducer.buildKeyResolver(config);

        assertTrue(resolver.resolve(null, SignatureAlgorithm.ES256).isPresent());
        assertTrue(JwtAuthConfigProducer.buildConfig(config).requiredAlgorithm().isEmpty());
    }

    @Test
    void configuredAlgorithmIsExposedOnJwtConfig() {
        Config config = CdiTestSupport.config(Map.of("mp.jwt.verify.publickey.algorithm", "ES256"));
        assertEquals(SignatureAlgorithm.ES256,
                JwtAuthConfigProducer.buildConfig(config).requiredAlgorithm().orElseThrow());
    }
```

- [ ] **Step 2: Run, expect compilation failures** (`JwtConfig` has 6 components, `requiredAlgorithm()` missing)

Run: `cd cervantes && ./mvnw -q clean test -Dtest='DefaultJwtValidatorTest,JwtAuthConfigProducerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD FAILURE (compilation).

- [ ] **Step 3: Implement**

`JwtConfig.java` — add the component and keep the two static factories compiling:

```java
public record JwtConfig(
        Optional<String> issuer,
        Set<String> audiences,
        Duration clockSkew,
        boolean requireExpiration,
        Optional<Long> tokenAge,
        boolean encryptionRequired,
        /** Signature algorithm whitelisted by {@code mp.jwt.verify.publickey.algorithm}; empty = RS256 and ES256 both accepted (MP JWT 2.2). */
        Optional<SignatureAlgorithm> requiredAlgorithm) {

    public JwtConfig {
        Objects.requireNonNull(issuer, "issuer");
        // ...existing requireNonNull calls...
        Objects.requireNonNull(requiredAlgorithm, "requiredAlgorithm");
    }
    // In the two existing static factories (lines ~68 and ~74) append `Optional.empty()` as the last argument.
```

Then `grep -rn "new JwtConfig(" cervantes --include='*.java'` and append `Optional.empty()` (or the relevant value) to every call site (main + tests).

`DefaultJwtValidator.validate` — right after `alg` is resolved:

```java
        config.requiredAlgorithm().ifPresent(required -> {
            if (required.family() != alg.family()) {
                throw new IllegalStateException(); // placeholder replaced below
            }
        });
```
Use a plain `if` (checked exception):
```java
        if (config.requiredAlgorithm().isPresent()
                && config.requiredAlgorithm().get().family() != alg.family()) {
            throw new JwtValidationException("token algorithm " + alg.name()
                    + " does not match the configured mp.jwt.verify.publickey.algorithm="
                    + config.requiredAlgorithm().get().name());
        }
```

`KeyResolvers.java` — change the two public signatures and the lazy resolver:

```java
    public static KeyResolver fromInlinePem(String value, Optional<SignatureAlgorithm.Family> family) throws JwtValidationException {
        // ...unchanged JWK/JWKS branches...
        return new ConfiguredKeyResolver(parsePem(trimmed, family));
    }

    public static KeyResolver fromLocation(String location, Optional<SignatureAlgorithm.Family> family) throws JwtValidationException {
        // ...unchanged, pass `family` through to LazyHttpKeyResolver and parsePem...
    }

    private static PublicKey parsePem(String pem, Optional<SignatureAlgorithm.Family> family) throws JwtValidationException {
        return family.isPresent() ? PemKeys.fromPem(pem, family.get()) : PemKeys.fromPem(pem);
    }
```
`LazyHttpKeyResolver`: field `private final Optional<SignatureAlgorithm.Family> family;` and use `parsePem` at detection time.

`JwtAuthConfigProducer.java`:

```java
    static JwtConfig buildConfig(Config config) {
        // ...existing...
        Optional<SignatureAlgorithm> requiredAlgorithm = config
                .getOptionalValue("mp.jwt.verify.publickey.algorithm", String.class)
                .flatMap(SignatureAlgorithm::fromJoseName);
        return new JwtConfig(issuer, audiences, JwtConfig.DEFAULT_CLOCK_SKEW, true, tokenAge,
                encryptionRequired, requiredAlgorithm);
    }

    static KeyResolver buildKeyResolver(Config config) throws JwtValidationException {
        Optional<SignatureAlgorithm.Family> family = config
                .getOptionalValue("mp.jwt.verify.publickey.algorithm", String.class)
                .flatMap(SignatureAlgorithm::fromJoseName)
                .map(SignatureAlgorithm::family);
        // ...rest unchanged, passing `family`...
    }
```
Update the Javadoc at l.51 (`RS256 (default)` → "unset = both RS256 and ES256 accepted").

- [ ] **Step 4: Run the whole cervantes suite, expect PASS**

Run: `cd cervantes && ./mvnw clean install`
Expected: all modules green (`cervantes-core`, `cervantes-cdi-vauban`, `cervantes-jaxrs` tests), BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A cervantes-api cervantes-core cervantes-cdi-vauban
git commit -S --signoff -m "feat: accept RS256 and ES256 when mp.jwt.verify.publickey.algorithm is unset (MP JWT 2.2)"
```

### Task A3: bump the JWT TCK to 2.2 and update the docs

**Files:**
- Modify: `cervantes/pom.xml:62` (`<microprofile.jwt.version>2.1` → `2.2`)
- Modify: `cervantes/cervantes-tck/pom.xml:36` (same property), `:293-304` (excludes)
- Rename: `cervantes/run-official-tck-mp-jwt-2.1.sh` → `run-official-tck-mp-jwt-2.2.sh`
- Modify: `cervantes/TCK.md`, `cervantes/README.md`, `cervantes/docs/en/modules/ROOT/pages/reference.adoc:165`, `concepts.adoc:97`, `migration.adoc:34`, `index.adoc` (spec version)

- [ ] **Step 1: Bump the versions**

`sed -i '' 's#<microprofile.jwt.version>2.1</microprofile.jwt.version>#<microprofile.jwt.version>2.2</microprofile.jwt.version>#' pom.xml cervantes-tck/pom.xml` then `git diff` to confirm exactly two hunks.

- [ ] **Step 2: Drop the obsolete exclusions**

In `cervantes-tck/pom.xml` around l.293-300 remove the three `<exclude>org/eclipse/microprofile/jwt/tck/container/{ejb,jacc,servlet}/**</exclude>` lines (those packages no longer exist in the 2.2 `tests` jar) and reword the comment to "MP JWT 2.2 dropped the EJB/JACC/Servlet container tests". Keep `<excludedGroups>ee-security-optional</excludedGroups>` — verify it is still referenced: `unzip -p ~/.m2/repository/org/eclipse/microprofile/jwt/microprofile-jwt-auth-tck/2.2/microprofile-jwt-auth-tck-2.2-tests.jar META-INF/MANIFEST.MF >/dev/null && cd /Users/yblazart/.claude/jobs/7e5f64ca/tmp/jwt22 && grep -rl "ee-security-optional" . | head` (the jar is already unpacked there); if nothing references it, remove the `excludedGroups` too.

- [ ] **Step 3: Rename the script and fix its internals**

`git mv run-official-tck-mp-jwt-2.1.sh run-official-tck-mp-jwt-2.2.sh` then `grep -n "2\.1" run-official-tck-mp-jwt-2.2.sh` and update every hard-coded `2.1` (labels, artifact coordinates) to `2.2`.

- [ ] **Step 4: Run the official TCK**

Run: `cd cervantes && ./mvnw -ntp clean install -DskipTests && ./run-official-tck-mp-jwt-2.2.sh 2>&1 | tee /Users/yblazart/.claude/jobs/7e5f64ca/tmp/cervantes-tck-2.2.log`
Expected: `Tests run: 208, Failures: 0, Errors: 0` (206 from 2.1 + `RsaAndEcSignatureAlgorithmTest.testRS256Token` + `.testES256Token`). If `RsaAndEcSignatureAlgorithmTest` fails, the JWKS path is the suspect: `JwksKeyResolver.lookup` resolves by `kid` (`rskey`/`eckey`) and `JwkParser` supports `kty=EC` — check the `alg`/`use` filtering in `JwkParser` does not drop one of the two keys. Log any real defect in `cervantes/BUG.md`.

- [ ] **Step 5: Update the docs**

- `TCK.md`: title "MicroProfile JWT 2.2", artifact `microprofile-jwt-auth-tck:2.2`, result line "208/208 (date)", note on removed ejb/jacc/servlet packages.
- `README.md`: "MicroProfile JWT 2.2", script name, TCK count.
- `docs/en/modules/ROOT/pages/reference.adoc:165-166`: "Expected algorithm family (`RS256`, `ES256`). **Unset: both RS256 and ES256 are accepted (MP JWT 2.2).** When set, tokens announcing another family are rejected."
- `concepts.adoc:97` and `migration.adoc:34`: same nuance (one sentence each).
- `index.adoc` / any `2.1` mention: `grep -rn "JWT 2\.1" docs README.md` → 2.2.

- [ ] **Step 6: Commit and open the PR**

```bash
git add -A
git commit -S --signoff -m "build(tck): run the official MicroProfile JWT 2.2 TCK (208/208) and document the 2.2 behaviour"
git push -4 -u origin pr/ybl/mp-7.2   # ssh -4: Codefloe/Codeberg IPv6 hangs
```
Open the PR on Codefloe (`Vidocq/cervantes`), title `feat: MicroProfile JWT Auth 2.2 (MicroProfile 7.2)`; confirm `governance-checks` and the build job are green.

---

## Phase B — Grimm: MicroProfile OpenAPI 4.2

### Task B1: bump the API to 4.2-RC2 and confirm the build

**Files:**
- Modify: `grimm/pom.xml:72` (`<version.mp.openapi>4.1` → `4.2-RC2`)
- Modify: `grimm/grimm-tck/pom.xml:21` (`<microprofile.openapi.version>4.1` → `4.2-RC2`)

- [ ] **Step 1: Bump and build**

Run: `cd grimm && git checkout -b pr/ybl/mp-7.2 && sed -i '' 's#<version.mp.openapi>4.1</version.mp.openapi>#<version.mp.openapi>4.2-RC2</version.mp.openapi>#' pom.xml && sed -i '' 's#<microprofile.openapi.version>4.1</microprofile.openapi.version>#<microprofile.openapi.version>4.2-RC2</microprofile.openapi.version>#' grimm-tck/pom.xml && ./mvnw clean install`
Expected: BUILD SUCCESS — the 4.2 API is purely additive (verified: 0 class-level diff, only new methods on `Schema`/`@Header`). `SchemaImpl` already inherits the `Extensible` implementations from `AbstractExtensibleRef`, so the new abstract overrides on `Schema` are satisfied.

- [ ] **Step 2: Check the JPMS view of the new API jar**

Run: `jar --describe-module --file ~/.m2/repository/org/eclipse/microprofile/openapi/microprofile-openapi-api/4.2-RC2/microprofile-openapi-api-4.2-RC2.jar | head -3`
Expected: the same module name grimm's `module-info.java` files `requires` today (`grep -rh "requires.*openapi" grimm-*/src/main/java/module-info.java`). If it changed, update the `requires` lines.

- [ ] **Step 3: Commit**

```bash
git commit -S --signoff -am "build: target MicroProfile OpenAPI API 4.2-RC2 (MicroProfile 7.2)"
```

### Task B2: `Schema` extension semantics (issue #698, `SchemaExtensionPropertyTest`)

**Files:**
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/model/SchemaImpl.java:101-102,395-445`
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/serialization/OpenApiValueMapper.java` (schema branch — `grep -n "Schema" OpenApiValueMapper.java`)
- Create: `grimm/grimm-core/src/test/java/io/vidocq/grimm/internal/model/SchemaImplExtensionsTest.java`

**Interfaces:**
- Produces: on `SchemaImpl`, the `Extensible` methods and the generic `get/set/getAll/setAll` share **one** store (`extraProperties`): unknown properties *are* extensions (base OAS 3.1 dialect, or `schemaDialect == null`).

- [ ] **Step 1: Write the failing tests** (mirror of the six TCK methods)

```java
package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** MP OpenAPI 4.2 (#698): for the base dialect, unknown schema properties are extensions. */
class SchemaImplExtensionsTest {

    private static final String EXT = "my-extension";

    @Test
    void extensionSetForUnknownProperty() {
        Schema s = OASFactory.createSchema().set(EXT, "v");
        assertEquals("v", s.getExtensions().get(EXT));
        assertTrue(s.hasExtension(EXT));
        assertEquals("v", s.getExtension(EXT));
    }

    @Test
    void extensionSetAllForUnknownProperty() {
        Schema s = OASFactory.createSchema();
        s.setAll(Map.of(EXT, "v", "type", List.of(Schema.SchemaType.STRING)));
        assertEquals(Map.of(EXT, "v"), s.getExtensions());
        assertEquals(List.of(Schema.SchemaType.STRING), s.getType());
    }

    @Test
    void extensionAvailableFromGet() {
        Schema s = OASFactory.createSchema().addExtension(EXT, "v");
        assertEquals("v", s.get(EXT));
        assertTrue(s.getAll().containsKey(EXT));
    }

    @Test
    void extensionSetWithNonnullDialect() {
        Schema s = OASFactory.createSchema()
                .schemaDialect("https://spec.openapis.org/oas/3.1/dialect/base")
                .addExtension(EXT, "v");
        assertEquals("v", s.get(EXT));
        assertEquals("v", s.getExtension(EXT));
    }

    @Test
    void setAllClearsExtensions() {
        Schema s = OASFactory.createSchema().addExtension(EXT, "v");
        s.setAll(Map.of("type", List.of(Schema.SchemaType.INTEGER)));
        assertTrue(s.getExtensions().isEmpty());
        assertNull(s.get(EXT));
    }

    @Test
    void nullExtensionNotAdded() {
        Schema s = OASFactory.createSchema().addExtension(EXT, null);
        assertFalse(s.hasExtension(EXT));
        assertTrue(s.getExtensions().isEmpty());
    }
}
```

- [ ] **Step 2: Run, expect failures** (`extensionSetForUnknownProperty`, `extensionAvailableFromGet`, `setAllClearsExtensions` at least — today `set()` stores unknowns in `extraProperties` while `getExtensions()` reads the inherited `extensions` map)

Run: `cd grimm && ./mvnw -q -pl grimm-core clean test -Dtest=SchemaImplExtensionsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: failures.

- [ ] **Step 3: Implement in `SchemaImpl`**

Override the six `Extensible` methods to use `extraProperties`, and route `setAll` through `set`:

```java
    // ── extensions == unknown properties (MP OpenAPI 4.2 §Schema Extensions, base dialect) ──
    @Override public Map<String, Object> getExtensions() {
        return extraProperties == null ? Map.of() : ModelCollections.immutableMapView(extraProperties);
    }
    @Override public void setExtensions(Map<String, Object> extensions) {
        extraProperties = extensions == null ? null : new LinkedHashMap<>(extensions);
    }
    @Override public Schema addExtension(String name, Object value) {
        if (name == null || value == null) return this;
        extraProperties = ModelCollections.copyOnWriteMap(extraProperties);
        extraProperties.put(name, value);
        return this;
    }
    @Override public void removeExtension(String name) {
        if (extraProperties != null) {
            extraProperties = ModelCollections.copyOnWriteMap(extraProperties);
            extraProperties.remove(name);
        }
    }
    @Override public boolean hasExtension(String name) {
        return extraProperties != null && extraProperties.containsKey(name);
    }
    @Override public Object getExtension(String name) {
        return extraProperties == null ? null : extraProperties.get(name);
    }

    @Override
    public void setAll(Map<String, ?> allProperties) {
        extraProperties = null;                     // clears extensions (TCK testSetAllClearsExtensions)
        if (allProperties != null) {
            for (Map.Entry<String, ?> e : allProperties.entrySet()) {
                set(e.getKey(), e.getValue());      // standard keys go through their setter
            }
        }
    }
```
`set(name, null)` must remove the extra property (spec: "null to remove the property") — adjust the fall-through branch: `if (value == null) { if (extraProperties != null) { copy; remove; } return this; }`. `getAll()` keeps returning the extra store (unchanged, TCK 4.1 relies on it).

- [ ] **Step 4: Serialize extensions once**

In `OpenApiValueMapper`, find the schema serialization (`grep -n "getAll()\|getExtensions()" OpenApiValueMapper.java`). Because both now read the same map, make sure a `SchemaImpl` is written with **one** loop (either the generic `getAll()` loop or the `Extensible` loop, not both) — otherwise every `x-…` key appears twice in the YAML/JSON. Same check in `ModelMerger.mergeExtensions` (l.595-607) and `SchemaRegistry` (l.234) — they read `getExtensions()` and stay correct.

- [ ] **Step 5: Run the module, expect PASS**

Run: `cd grimm && ./mvnw -q -pl grimm-core clean test`
Expected: `SchemaImplExtensionsTest` 6/6 and no regression in `SchemaGeneratorTest`/serialization tests.

- [ ] **Step 6: Commit**

```bash
git add grimm-core
git commit -S --signoff -m "feat(model): treat unknown Schema properties as extensions (MP OpenAPI 4.2 #698)"
```

### Task B3: `@Header.example` / `@Header.examples` (issue #697)

**Files:**
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/scanner/JaxRsResourceScanner.java:1607-1623` (`toModelHeader`) and the `ExampleObject` mapping at `:1549-1570`
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/scanner/AnnotationScanner.java:1172-1188` (`toModelHeader`)
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/serialization/OpenApiModelMapper.java:650-665` (header keys from static files)
- Test: `grimm/grimm-core/src/test/java/io/vidocq/grimm/internal/scanner/JaxRsResourceScannerTest.java`, `AnnotationScannerTest.java`

**Interfaces:**
- Produces: `private org.eclipse.microprofile.openapi.models.examples.Example toModelExample(ExampleObject)` in each scanner (extracted from the existing inline `ExampleObject` loop), reused for `@Content.examples` and `@Header.examples`.

- [ ] **Step 1: Write the failing tests**

`JaxRsResourceScannerTest` (TCK shape: `AirlinesApp` `X-Password-Strength` header):

```java
    @Path("/hdr")
    static class HeaderExampleResource {
        @GET
        @APIResponse(responseCode = "200", headers = @Header(name = "X-Password-Strength",
                example = "0",
                examples = {
                    @ExampleObject(name = "strong", value = "10"),
                    @ExampleObject(name = "weak", value = "5.1")}))
        public String get() { return ""; }
    }

    @Test
    void mapsHeaderExampleAndExamples() {
        OpenAPI openAPI = scan(HeaderExampleResource.class);   // use the test file's existing scan helper
        var header = openAPI.getPaths().getPathItem("/hdr").getGET()
                .getResponses().getAPIResponse("200").getHeaders().get("X-Password-Strength");
        assertEquals("0", header.getExample());
        assertEquals("10", header.getExamples().get("strong").getValue());
        assertEquals("5.1", header.getExamples().get("weak").getValue());
    }
```
`AnnotationScannerTest`: same assertion on `@Components(headers = @Header(name = "X-Rate", example = "42"))` → `openAPI.getComponents().getHeaders().get("X-Rate").getExample()`.

- [ ] **Step 2: Run, expect compile failure** (`example`/`examples` exist in the 4.2 API, so the test compiles — the assertion fails with `null`)

Run: `cd grimm && ./mvnw -q -pl grimm-core clean test -Dtest='JaxRsResourceScannerTest,AnnotationScannerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 2 failures (`expected: <0> but was: <null>`).

- [ ] **Step 3: Implement**

In both scanners extract the existing `ExampleObject` → `Example` block into `toModelExample(ExampleObject)` and extend `toModelHeader`:

```java
        if (!headerAnnotation.example().isEmpty()) {
            header.setExample(headerAnnotation.example());
        }
        for (ExampleObject exampleAnnotation : headerAnnotation.examples()) {
            if (!exampleAnnotation.name().isEmpty()) {
                header.addExample(exampleAnnotation.name(), toModelExample(exampleAnnotation));
            }
        }
```
Keep `header.setAllowEmptyValue(headerAnnotation.allowEmptyValue());` and annotate the enclosing method `@SuppressWarnings("deprecation")` (both scanners, `OpenApiModelMapper.toHeader`, `HeaderImpl` accessors). In `OpenApiModelMapper` header switch add `case "example" -> header.setExample(value);` and `case "examples" -> header.setExamples(toExamples(value));` (helper already used for media types at l.621).

- [ ] **Step 4: Run, expect PASS**; **Step 5: Commit**

```bash
git add grimm-core
git commit -S --signoff -m "feat(scanner): map @Header example/examples (MP OpenAPI 4.2 #697)"
```

### Task B4: method-level `@ExternalDocumentation` → `operation.externalDocs` (`ExternalDocumentationAnnotationTest`)

**Files:**
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/scanner/JaxRsResourceScanner.java` — in `buildOperation(clazz, method, opAnn, consumes, produces)` (called at l.136)
- Test: `JaxRsResourceScannerTest.java`

- [ ] **Step 1: Failing test**

```java
    @Path("/a")
    static class ExternalDocsResource {
        @GET
        @ExternalDocumentation(description = "Find more information about this application resource",
                url = "https://example.org/AResource.java")
        public String get() { return ""; }
    }

    @Test
    void mapsMethodLevelExternalDocumentationOntoTheOperation() {
        OpenAPI openAPI = scan(ExternalDocsResource.class);
        var docs = openAPI.getPaths().getPathItem("/a").getGET().getExternalDocs();
        assertNotNull(docs);
        assertEquals("https://example.org/AResource.java", docs.getUrl());
        assertEquals("Find more information about this application resource", docs.getDescription());
        assertNull(openAPI.getExternalDocs(), "method-level docs must not leak to the document root");
    }
```

- [ ] **Step 2: Run, expect failure** (`docs` is null — `JaxRsResourceScanner` has no `ExternalDocumentation` handling today).

- [ ] **Step 3: Implement** in `buildOperation`, next to the `@Tags`/`@Extensions` handling:

```java
        ExternalDocumentation extDocs = method.getAnnotation(ExternalDocumentation.class);
        if (extDocs != null && !extDocs.url().isEmpty()) {
            var model = OASFactory.createObject(org.eclipse.microprofile.openapi.models.ExternalDocumentation.class);
            model.setUrl(extDocs.url());
            if (!extDocs.description().isEmpty()) {
                model.setDescription(extDocs.description());
            }
            applyExtensions(model, extDocs.extensions());
            modelOp.setExternalDocs(model);
        }
```
Class-level `@ExternalDocumentation` keeps mapping to `openAPI.externalDocs` in `AnnotationScanner.processExternalDocumentation` (deprecated by 4.2 but still legal — do not remove).

- [ ] **Step 4: Run, expect PASS**; **Step 5: Commit** `feat(scanner): map method-level @ExternalDocumentation onto the operation (MP OpenAPI 4.2 #713)`.

### Task B5: Bean Validation `@Digits` (issue #717, `BeanValidationTest`)

**Files:**
- Modify: `grimm/grimm-core/src/main/java/io/vidocq/grimm/internal/schema/BeanValidationMapper.java:63-92`
- Create: `grimm/grimm-core/src/test/java/io/vidocq/grimm/internal/schema/BeanValidationMapperTest.java`

**Interfaces:**
- Consumes: `BeanValidationMapper.apply(Schema, Annotation[])`, helpers `intAttr`, `matches`. Verify first where `apply` is invoked from `SchemaGenerator` — the schema **type must already be set** when `apply` runs (the mapping below depends on it): `grep -n "BeanValidationMapper.apply" grimm-core/src/main/java -r`.

TCK-derived contract (fields of `apps/beanvalidation/BeanValidationData`):

| Java type + `@Digits(integer=a, fraction=b)` | Schema type | Property set |
|---|---|---|
| `int`/`long`/`BigInteger` (b = 0) | `integer` | nothing (either no `multipleOf` or `multipleOf = 1` is accepted) |
| `float`/`double`/`BigDecimal`, b > 0 (5/3, 10/6, 20/10) | `number` | `multipleOf = 10^-b` (`0.001`, `1e-6`, `1e-10`) |
| `float`/`double`/`BigDecimal`/custom, b = 0 | `number` | `multipleOf = 1` |
| `String` (10/5) | `string` | `pattern` accepting `1`, `1.5`, `123456789.1234`, `1234567890.12345`, rejecting `12345678901.12345` and `1234567890.123456` |

Never override a value the user already set (`multipleOf`/`pattern` non-null → skip).

- [ ] **Step 1: Failing test**

```java
package io.vidocq.grimm.internal.schema;

import jakarta.validation.constraints.Digits;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BeanValidationMapperTest {

    static class Holder {
        @Digits(integer = 5, fraction = 3) float number;
        @Digits(integer = 10, fraction = 0) double numberAsInteger;
        @Digits(integer = 9, fraction = 0) int integer;
        @Digits(integer = 10, fraction = 5) String string;
    }

    private static Annotation[] annotationsOf(String field) throws NoSuchFieldException {
        return Holder.class.getDeclaredField(field).getAnnotations();
    }

    private static Schema typed(Schema.SchemaType type) {
        return OASFactory.createSchema().type(List.of(type));
    }

    @Test
    void digitsOnNumberSetsMultipleOfFromFraction() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER);
        BeanValidationMapper.apply(s, annotationsOf("number"));
        assertEquals(0, new BigDecimal("0.001").compareTo(s.getMultipleOf()));
    }

    @Test
    void digitsWithZeroFractionOnNumberSetsMultipleOfOne() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER);
        BeanValidationMapper.apply(s, annotationsOf("numberAsInteger"));
        assertEquals(0, BigDecimal.ONE.compareTo(s.getMultipleOf()));
    }

    @Test
    void digitsOnIntegerLeavesSchemaUntouched() throws Exception {
        Schema s = typed(Schema.SchemaType.INTEGER);
        BeanValidationMapper.apply(s, annotationsOf("integer"));
        assertNull(s.getMultipleOf());
    }

    @Test
    void digitsOnStringSetsPattern() throws Exception {
        Schema s = typed(Schema.SchemaType.STRING);
        BeanValidationMapper.apply(s, annotationsOf("string"));
        Pattern p = Pattern.compile(s.getPattern());
        for (String ok : List.of("1", "1.5", "123456789.1234", "1234567890.12345", "-42.1")) {
            assertTrue(p.matcher(ok).matches(), ok);
        }
        for (String ko : List.of("12345678901.12345", "1234567890.123456", "abc", "1.")) {
            assertFalse(p.matcher(ko).matches(), ko);
        }
    }

    @Test
    void userValuesWin() throws Exception {
        Schema s = typed(Schema.SchemaType.NUMBER).multipleOf(new BigDecimal("0.5"));
        BeanValidationMapper.apply(s, annotationsOf("number"));
        assertEquals(new BigDecimal("0.5"), s.getMultipleOf());
    }
}
```
(`jakarta.validation:jakarta.validation-api` must be on grimm-core's **test** classpath — check `grimm-core/pom.xml`; the TCK already needs it, add it `<scope>test</scope>` if absent. Production code keeps matching annotations by name — no runtime dependency.)

- [ ] **Step 2: Run, expect 4 failures** (only `digitsOnIntegerLeavesSchemaUntouched` passes).

- [ ] **Step 3: Implement** in `BeanValidationMapper.apply` (new branch after `NotEmpty`) and helper:

```java
            } else if (matches(name, "Digits")) {
                applyDigits(schema, ann);
            }
```
```java
    private static void applyDigits(Schema schema, Annotation ann) {
        int integer = intAttr(ann, "integer", 0);
        int fraction = intAttr(ann, "fraction", 0);
        List<Schema.SchemaType> types = schema.getType();
        if (types == null) {
            return;
        }
        if (types.contains(Schema.SchemaType.STRING)) {
            if (schema.getPattern() == null || schema.getPattern().isBlank()) {
                String pattern = fraction > 0
                        ? "^-?\\d{1," + integer + "}(\\.\\d{1," + fraction + "})?$"
                        : "^-?\\d{1," + integer + "}$";
                schema.setPattern(pattern);
            }
        } else if (types.contains(Schema.SchemaType.NUMBER)) {
            if (schema.getMultipleOf() == null) {
                schema.setMultipleOf(fraction == 0 ? BigDecimal.ONE : BigDecimal.ONE.movePointLeft(fraction));
            }
        }
        // integer: multipleOf = 1 is implied by the type — nothing to add.
    }
```

- [ ] **Step 4: Run module tests, expect PASS**; **Step 5: Commit** `feat(schema): derive multipleOf/pattern from Bean Validation @Digits (MP OpenAPI 4.2 #717)`.

### Task B6: run the official OpenAPI 4.2-RC2 TCK, docs

**Files:**
- Rename: `grimm/run-official-tck-mp-openapi-4.1.sh` → `run-official-tck-mp-openapi-4.2.sh` (fix internal `4.1` strings)
- Modify: `grimm/README.md:3,7,20,36-37`, `grimm/TCK.md`, `grimm/docs/en/modules/ROOT/pages/*.adoc` (`grep -rn "4\.1" docs`)

- [ ] **Step 1: Full TCK**

Run: `cd grimm && ./mvnw -ntp clean install -DskipTests && ./run-official-tck-mp-openapi-4.2.sh all 2>&1 | tee /Users/yblazart/.claude/jobs/7e5f64ca/tmp/grimm-tck-4.2-RC2.log`
Expected: `Tests run: 356, Failures: 0` (349 + `ExternalDocumentationAnnotationTest` ×1 + `SchemaExtensionPropertyTest` ×6). Watch specifically `AirlinesAppTest` (new header-example and `tags.find{…}.description` assertions) and `BeanValidationTest`. Any red test → fix in the relevant task above (B2–B5), re-run. Never use the `smoke` mode as evidence (memory: smoke is broken, always `all`).

- [ ] **Step 2: Docs + commit + PR**

Update README ("MicroProfile OpenAPI 4.2", "356/356", script name), `TCK.md`, Antora pages. Note in `TCK.md`: "run against `4.2-RC2`; re-run on the 4.2 final (Task E1)".

```bash
git add -A && git commit -S --signoff -m "build(tck): run the official MicroProfile OpenAPI 4.2-RC2 TCK (356/356)"
git push -4 -u origin pr/ybl/mp-7.2
```
PR `Vidocq/grimm`: `feat: MicroProfile OpenAPI 4.2 (MicroProfile 7.2)`.

---

## Phase C — Humboldt: MicroProfile Telemetry 2.2

### Task C1: adopt OpenTelemetry 1.64.0 / instrumentation-annotations 2.30.0 / semconv 1.43.0

**Files:**
- Modify: `humboldt/pom.xml:65-67`, `humboldt/humboldt-tck/pom.xml:50-52`

- [ ] **Step 1: Bump**

```bash
cd humboldt && git checkout -b pr/ybl/mp-7.2
sed -i '' -e 's#<opentelemetry.version>1.39.0<#<opentelemetry.version>1.64.0<#' \
          -e 's#<opentelemetry.semconv.version>1.27.0-alpha<#<opentelemetry.semconv.version>1.43.0<#' \
          -e 's#<opentelemetry.instrumentation.version>2.7.0<#<opentelemetry.instrumentation.version>2.30.0<#' \
          pom.xml humboldt-tck/pom.xml
git diff --stat   # expect 2 files, 6 lines
```

- [ ] **Step 2: Clean build, then inspect the shaded JPMS modules**

Run: `./mvnw clean install`
Expected: BUILD SUCCESS (0.3 verified: no new abstract method on any implemented interface). If `humboldt-tck` fails to compile on a semconv symbol that moved to the incubating artifact, add `io.opentelemetry.semconv:opentelemetry-semconv-incubating:1.43.0-alpha` **test scope in `humboldt-tck` only**.
Then: `jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-0.3.0-SNAPSHOT.jar` and the same for `humboldt-otel-context` and `humboldt-otel-instrumentation-annotations` — module names and `exports` must be identical to `main` (the ModiTect descriptors are hand-written; `io.opentelemetry.api.impl` is internal and must **not** be exported).

- [ ] **Step 3: Keep the 2.1 TCK green on the new OTel**

Run: `./run-official-tck-telemetry-2.1.sh 2>&1 | tee /Users/yblazart/.claude/jobs/7e5f64ca/tmp/humboldt-tck-2.1-otel164.log`
Expected: 85/85 unchanged.

- [ ] **Step 4: Commit** `build: adopt OpenTelemetry 1.64.0, instrumentation-annotations 2.30.0, semconv 1.43.0 (MP Telemetry 2.2)`.

### Task C2: mandatory `code.function.name` on `@WithSpan` spans (issue #321)

**Files:**
- Modify: `humboldt/humboldt-cdi/src/main/java/io/vidocq/humboldt/cdi/WithSpanInterceptor.java:70-82`
- Test: `humboldt/humboldt-cdi/src/test/java/io/vidocq/humboldt/cdi/WithSpanInterceptorTest.java`

**Interfaces:**
- Produces: attribute `code.function.name` = `method.getDeclaringClass().getName() + "." + method.getName()` (binary class name — the TCK expects `…RestClientSpanTest$SpanBean.spanChild`).

- [ ] **Step 1: Failing test** (the test file already has a nested `Target` class and an `invocationFor(name, args)` helper — reuse them):

```java
    @Test
    void sets_code_function_name_with_binary_class_name() throws Exception {
        interceptor.aroundInvoke(invocationFor("annotatedDefault", "hello"));

        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals(Target.class.getName() + ".annotatedDefault",
                s.attributes().get(AttributeKey.stringKey("code.function.name")),
                "MP Telemetry 2.2: code.function.name is mandatory, binary name + '.' + method");
    }
```
(Adapt `s.attributes()` to the accessor `SpanData` exposes in humboldt — `grep -n "attributes" humboldt-sdk-trace/src/main/java/io/vidocq/humboldt/sdk/trace/data/SpanData.java`.)

- [ ] **Step 2: Run, expect failure** (`null`).

- [ ] **Step 3: Implement**

```java
    /** OTel semconv {@code code.function.name} — mandatory on @WithSpan spans since MP Telemetry 2.2. */
    private static final AttributeKey<String> CODE_FUNCTION_NAME = AttributeKey.stringKey("code.function.name");
    ...
        SpanBuilder builder = t.spanBuilder(spanName)
                .setSpanKind(kind)
                .setAttribute(CODE_FUNCTION_NAME, method.getDeclaringClass().getName() + "." + method.getName());
```
Add `import io.opentelemetry.api.common.AttributeKey;`. No semconv dependency (keeps humboldt zero-dep at runtime).

- [ ] **Step 4: Run, expect PASS**; **Step 5: Commit** `feat(cdi): emit code.function.name on @WithSpan spans (MP Telemetry 2.2)`.

### Task C3: honour `@WithSpan(inheritContext = false)` (instrumentation-annotations 2.30)

**Files:** same as C2.

- [ ] **Step 1: Failing test**

```java
    @Test
    void inheritContext_false_starts_a_new_trace() throws Exception {
        Span parent = provider.get("test").spanBuilder("parent").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            interceptor.aroundInvoke(invocationFor("detached"));   // Target.detached() annotated @WithSpan(inheritContext = false)
        } finally {
            parent.end();
        }
        SpanData child = exporter.getFinishedSpans().stream()
                .filter(s -> s.name().equals("Target.detached")).findFirst().orElseThrow();
        assertNotEquals(parent.getSpanContext().getTraceId(), child.traceId());
        assertFalse(child.parentSpanContext().isValid());
    }
```
Add to `Target`: `@WithSpan(inheritContext = false) String detached() { return "x"; }`.

- [ ] **Step 2: Run, expect failure** (child is parented to `parent`).

- [ ] **Step 3: Implement** — after building `builder`:

```java
        if (annotation != null && !annotation.inheritContext()) {
            builder.setNoParent();
        }
```

- [ ] **Step 4: Run, expect PASS**; **Step 5: Commit** `feat(cdi): support @WithSpan(inheritContext = false)`.

### Task C4: Telemetry 2.2 TCK — gated on artifact publication

No `microprofile-telemetry-*-tck:2.2` (nor RC) exists on Maven Central or as a git tag as of 2026-08-27, although the Eclipse release record is dated 2026-07-21.

- [ ] **Step 1: Ahead-of-publication run from source (optional but recommended)**

```bash
cd /Users/yblazart/.claude/jobs/7e5f64ca/tmp && git clone --depth 1 https://github.com/microprofile/microprofile-telemetry.git
cd microprofile-telemetry && mvn -q -DskipTests install      # installs 2.2-SNAPSHOT TCK jars in ~/.m2
cd /Users/yblazart/projects/perso/vidocq/humboldt && ./run-official-tck-telemetry-2.1.sh -Dmicroprofile.telemetry.version=2.2-SNAPSHOT 2>&1 | tee /Users/yblazart/.claude/jobs/7e5f64ca/tmp/humboldt-tck-2.2-SNAPSHOT.log
```
(If the script does not forward `-D` args to Maven, run the underlying `mvn -f humboldt-tck/pom.xml -Ptck-official test -Dmicroprofile.telemetry.version=2.2-SNAPSHOT` directly.) Expected: 85/85 + the new `code.function.name` assertion in `RestClientSpanTest.spanChild` green. Record the result in `humboldt/TCK.md` under a clearly labelled "2.2-SNAPSHOT (built from source, commit <sha>)" entry — it is evidence, not certification.

- [ ] **Step 2: Watch for publication**

`node /Users/yblazart/.claude/jobs/7e5f64ca/tmp/central.mjs` prints the latest versions; when `microprofile-telemetry-tracing-tck` shows `2.2`, execute Task E2.

- [ ] **Step 3: Docs + commit + PR**

Update `README.md` (l.5, l.11), `docs/en/modules/ROOT/pages/{index,concepts,getting-started,internals}.adoc` to "MicroProfile Telemetry 2.2 (OpenTelemetry 1.64)"; keep the TCK badge at "2.1: 85/85, 2.2 pending TCK publication". Commit `docs: MicroProfile Telemetry 2.2 status`, push `-4`, PR `Vidocq/humboldt`: `feat: MicroProfile Telemetry 2.2 — OTel 1.64, code.function.name, inheritContext`.

---

## Phase D — Vidocq runtime: runners and documentation

### Task D1: bump the in-reactor TCK runners and re-run them on the assembled runtime

**Files:**
- Modify: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-cervantes-jwt/pom.xml:16,28,35` (description, exclusion comment, `microprofile.jwt.tck.version` 2.1 → 2.2; drop the ejb/jacc/servlet excludes in its surefire/testng config — `grep -n "container/" pom.xml`)
- Modify: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-grimm-openapi/pom.xml:31-32` (comment → "MicroProfile 7.2 pins OpenAPI 4.2 — RC2 until the final is published", `4.0.2` → `4.2-RC2`)
- Modify: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-humboldt-telemetry/pom.xml:31-38` (comment, `opentelemetry.version` 1.64.0, `opentelemetry.instrumentation.version` 2.30.0, `opentelemetry.semconv.version` 1.43.0; `microprofile.telemetry.version` stays 2.1 until Task E2)
- Modify: `vidocq/vidocq-runtime-integration-tests/TCK.md:48-60`

Prerequisite: the three bricks are `clean install`ed locally (or merged and available as `0.3.0-SNAPSHOT` from the snapshots repo). `git status` shows 2 untracked files in `vidocq/` — leave them alone (not part of this work) and branch from `main`: `git checkout -b pr/ybl/mp-7.2`.

- [ ] **Step 1: Edit the three POMs** as listed (use `sed`/editor, then `git diff` — expect ~10 changed lines).

- [ ] **Step 2: Run each runner and read the summary yourself**

```bash
cd vidocq && ./mvnw -ntp clean install -DskipTests
for m in cervantes-jwt grimm-openapi humboldt-telemetry; do
  ./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-$m clean test 2>&1 | tee /Users/yblazart/.claude/jobs/7e5f64ca/tmp/vidocq-tck-$m.log | grep -E "Tests run:|BUILD"
done
```
Expected: JWT `208` (0 failures); OpenAPI: the runner reported `344` at 4.0.2 — at 4.2-RC2 expect `344 + 7 = 351` unless 4.1/4.2 also added tests the old pin never ran (record the **actual** figure, and explain the delta vs grimm's own 356 in `TCK.md` — grimm-tck and the runtime runner have historically counted differently); Telemetry `85` unchanged. A "Failed to bind" error is a port flake — re-run once before investigating.

- [ ] **Step 3: Update `TCK.md`**

Table header "Spec (MicroProfile 7.2)", rows: `JWT Auth 2.2 | 208`, `OpenAPI 4.2 (RC2) | <actual>`, `Telemetry 2.1 (2.2 TCK not yet published; OTel 1.64) | 85`, new total, date. Rewrite the JWT paragraph: the ejb/jacc/servlet exclusion no longer exists in 2.2 (only `ee-security-optional` if still needed).

- [ ] **Step 4: Commit** `build(tck): MicroProfile 7.2 runners — JWT 2.2, OpenAPI 4.2-RC2, OTel 1.64 for Telemetry`.

### Task D2: documentation sweep "MicroProfile 7.1" → "7.2"

**Files (verified list):** `vidocq/README.md:9,24,231`, `vidocq/ROADMAP.md:3,10,74`, `vidocq/CLAUDE.md:7`, `vidocq/AGENTS.md:7`, `vidocq/CERTIFICATION.md` (add: "MicroProfile 7.2 explicitly allows Jakarta EE 11 Core Profile as the base"), `vidocq/docs/en/modules/ROOT/pages/whats-new.adoc:10`, `vidocq/docs/en/modules/ROOT/pages/tck.adoc:97`; `vidocq-docs/content/home/modules/ROOT/pages/index.adoc:184`, `roadmap.adoc:109`; `vidocq-workspace/CLAUDE.md` (TCK section: "certification MicroProfile 7.1" → 7.2); `knock/CONTRIBUTING.md` (one mention).

- [ ] **Step 1: Find every occurrence** — `grep -rnE "MicroProfile 7\.1|MP 7\.1" vidocq vidocq-docs vidocq-workspace knock --include='*.md' --include='*.adoc' | grep -v target` (expect ~20 hits; the six runner `pom.xml` comments were handled in D1).

- [ ] **Step 2: Edit with the honest nuance**

- Product statements ("modular Java SE MicroProfile 7.2 runtime") → 7.2.
- TCK claims (`tck.adoc`, `whats-new.adoc`, `vidocq-docs index/roadmap`): "MicroProfile 7.2 component TCKs: JWT 2.2 and OpenAPI 4.2 (RC2 — re-run on final) green; Telemetry 2.2 implemented, its TCK not yet published (2.1 suite green on OpenTelemetry 1.64)". Add a `[.tag-new]#NEW#` bullet in `whats-new.adoc` (badges are stripped at release time by `cut-docs-release.js`).
- `README.md:231` section title "MicroProfile 7.2 Extensions"; update the per-extension spec versions in that table (JWT 2.2, OpenAPI 4.2, Telemetry 2.2).

- [ ] **Step 3: Build the docs locally when the repo has an Antora/Roq build** (`vidocq-docs`: follow its README; kroki failures are silent — check the rendered page count) and commit:

```bash
git commit -S --signoff -am "docs: MicroProfile 7.2 — spec versions, TCK status, Core Profile 11 endorsement"
```
Push `-4`, PRs on `Vidocq/vidocq`, `Vidocq/vidocq-docs`, `Vidocq/vidocq-workspace`, `Vidocq/knock` (the last two are one-line docs changes).

---

## Phase E — Follow-ups (blocked on upstream publication) and bookkeeping

### Task E1: OpenAPI 4.2 final

Trigger: `microprofile-openapi-api` `<release>` on Central becomes `4.2` (check with `central.mjs`).
- [ ] `grimm/pom.xml`, `grimm/grimm-tck/pom.xml`, `vidocq/.../vidocq-runtime-tck-grimm-openapi/pom.xml`: `4.2-RC2` → `4.2`; re-run Task B6 step 1 and D1 step 2 (OpenAPI only); update `TCK.md`s ("4.2 final"); one commit per repo `build: MicroProfile OpenAPI 4.2 final`.

### Task E2: Telemetry 2.2 TCK

Trigger: `microprofile-telemetry-tracing-tck` `2.2` on Central.
- [ ] `humboldt/pom.xml:64`, `humboldt/humboldt-tck/pom.xml:47`, `vidocq/.../vidocq-runtime-tck-humboldt-telemetry/pom.xml:32`: `2.1` → `2.2`; `git mv run-official-tck-telemetry-2.1.sh run-official-tck-telemetry-2.2.sh`; run both TCK harnesses (expected 85 + any new test, 0 failures); update `humboldt/TCK.md`, `README.md`, `vidocq/.../TCK.md`, `tck.adoc`; commits `build(tck): official MicroProfile Telemetry 2.2 TCK`.

### Task E3: memory + release note

- [ ] Update the memory file `project_tck_assembled_runtime_campaign.md` (or create `project_mp72_upgrade.md`) with: MP 7.2 = JWT 2.2 / OpenAPI 4.2 / Telemetry 2.2 only; artifact-availability pitfalls (Telemetry 2.2 TCK unpublished, OpenAPI RC2, BOM 7.2 absent); the `code.function.name` binary-name rule; the `SchemaImpl` single-store decision; and the new TCK counts. Add the `MEMORY.md` index line.
- [ ] `vidocq/ROADMAP.md`: tick "MicroProfile 7.2 TCK per implemented spec" once E1/E2 are done; the "MicroProfile 7.2 compatible" claim on the website waits for E1 **and** E2.

---

## Self-review

**Spec coverage.** JWT 2.2 (both algorithms when unset, TCK 2.2, removed packages) → A1–A3. OpenAPI 4.2 (`@Header` example/examples → B3; `Schema` extension semantics → B2; `@Digits` → B5; method-level `@ExternalDocumentation` TCK → B4; `allowEmptyValue`/TYPE deprecations → B3 note + B4 note; TCK 4.2-RC2 → B6; final → E1). Telemetry 2.2 (OTel 1.64 → C1; `code.function.name` → C2; `inheritContext` → C3; TCK gating → C4/E2). Unchanged components and Core Profile 11 endorsement → §0.1, D2. Runtime runners + docs → D1/D2. Memory → E3.

**Placeholder scan.** Every code step carries the actual code; the only "verify first" items are deliberate (where `apply` is called in `SchemaGenerator`, whether `ee-security-optional` still exists in the 2.2 jar, how `SpanData` exposes attributes) and each says exactly what to grep.

**Type consistency.** `JwtConfig` 7th component `Optional<SignatureAlgorithm> requiredAlgorithm` is used with that name in A2 tests, validator and producer. `KeyResolvers.fromInlinePem/fromLocation` take `Optional<SignatureAlgorithm.Family>` everywhere. `PemKeys.fromPem(String)` (A1) is what `KeyResolvers.parsePem` (A2) calls. `toModelExample(ExampleObject)` (B3) is private per scanner. `CODE_FUNCTION_NAME` (C2) is a `AttributeKey<String>`.
