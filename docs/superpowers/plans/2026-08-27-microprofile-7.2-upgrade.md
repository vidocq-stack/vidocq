# MicroProfile 7.2 Upgrade — Implementation Plan (Vidocq 0.4.0-SNAPSHOT)

> **STATUS: phases A–D done on 2026-10-04 (branches `pr/ybl/mp-7.2` in cervantes, grimm, humboldt, vidocq); Phase E (finals, outside docs) open; Phase F (review follow-ups) added and started on 2026-10-04.** Refreshed 2026-10-04. The plan was on hold from 2026-08-27 ("we target certification, no migration before final artifacts"). On 2026-09-30 the component finals were tagged — OpenAPI `4.2`, Telemetry `2.2`, JWT `2.2.1` — and their jars are on the **Eclipse staging repositories** for the specification ballot (`https://repo.eclipse.org/repository/microprofile-{open-api,telemetry,jwt-auth}-maven2-staging/`), not yet on Maven Central. The platform PR `microprofile/microprofile#520` (opened 2026-10-01) pins jwt 2.2.1 / openapi 4.2 / telemetry 2.2; the BOM `microprofile:7.2` is not staged yet. **The release candidates on Central are byte-identical to the staged finals** (compared jar by jar on 2026-10-04: only `MANIFEST.MF`, `pom.properties`, the module version string in `module-info.class` and the LICENSE/NOTICE files of the OpenAPI TCK jar differ). Decision (maintainer, 2026-10-04): implement and run the TCKs against the RCs on Central now; switching to the finals is a version-string bump (Phase E). The public "MicroProfile 7.2 compatible" claim and any certification request still wait for the finals on Central.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Vidocq 0.4.0-SNAPSHOT line from MicroProfile 7.1 to MicroProfile 7.2 — implement the three updated component specs (JWT Auth 2.2, OpenAPI 4.2, Telemetry 2.2) in their bricks (cervantes, grimm, humboldt), re-run every official TCK on the assembled runtime, and sweep the documentation.

**Architecture:** MicroProfile 7.2 changes exactly three components; the other five (Config 3.1, Fault Tolerance 4.1, Health 4.0, Rest Client 4.0, Jakarta EE Core Profile) are unchanged, so ravel/heisenberg/knock/cyrano/vauban/cassini/champollion need **no code change**. Each affected brick gets a small, TDD-driven behavioural change plus a TCK-version bump; the vidocq runtime then bumps its in-reactor `-Ptck` runners and its docs. Everything is built against what Maven Central serves today — JWT `2.2` (final), OpenAPI `4.2-RC5`, Telemetry TCK `2.2-RC3` — which is the exact content of the finals under ballot (see STATUS); Phase E swaps the version strings once the finals reach Central.

**Tech Stack:** Java 25 (Temurin), Maven 3.9.16, JPMS strict, Arquillian/TestNG TCK harnesses, OpenTelemetry Java API 1.66.0 (shaded into `humboldt-otel-api` via maven-shade + ModiTect), MicroProfile APIs from Maven Central.

**Spec:** this document is self-contained — the facts section below *is* the spec (verified against Eclipse/Maven Central/GitHub on 2026-08-27, refreshed 2026-10-04). Upstream references: MP 7.2 release record `https://projects.eclipse.org/projects/technology.microprofile/releases/microprofile-7.2`; OpenAPI 4.2 spec `https://download.eclipse.org/microprofile/microprofile-open-api-4.2-RC5/microprofile-openapi-spec-4.2-RC5.html` (§8.1 release notes); JWT 2.2 tag `microprofile/microprofile-jwt-auth@2.2` (`spec/src/main/asciidoc/configuration.asciidoc`); Telemetry 2.2 tag `microprofile/microprofile-telemetry@2.2` (`spec/src/main/asciidoc/{tracing,release-notes}.adoc`; issues #318, #321, PR #319).

---

## 0. Verified facts (2026-08-27, refreshed 2026-10-04)

### 0.0 What changed between 2026-08-27 and 2026-10-04

- Finals tagged 2026-09-30 and staged for the ballot (see STATUS). RC → final diffs are release-plugin commits only: OpenAPI `4.2-RC5..4.2` and Telemetry `2.2-RC3..2.2` touch nothing but `pom.xml` versions; JWT `2.2..2.2.1` only adds the staging-repository configuration to the poms. OpenAPI `4.2-RC2..4.2-RC5` changed no API, SPI or TCK source (dependency bumps, parent pom, staging repository).
- **Telemetry 2.2 final pins OpenTelemetry 1.66.0, instrumentation-annotations 2.31.1, semconv 1.44.0** (not 1.64.0 / 2.30.0 / 1.43.0 as written on 2026-08-27). The public API of `opentelemetry-api` and `opentelemetry-context` is identical between 1.64.0 and 1.66.0 (`javap -public` on all 149 + 32 non-internal classes, 2026-10-04). `@WithSpan` in 2.31.1 has `value()`, `kind()`, `inheritContext()` — same as 2.30.0.
- The Telemetry 2.2 TCK adds no new test class: the tracing suite gains one assertion in `RestClientSpanTest.spanChild` (`code.function.name` = `org.eclipse.microprofile.telemetry.tracing.tck.rest.RestClientSpanTest$SpanBean.spanChild`) and swaps `assertEquals` argument order / case-insensitive header checks elsewhere.
- The workspace moved to **0.4.0-SNAPSHOT**. cervantes and humboldt now gate their PRs on the official TCK (`ci.tck.command` in the root pom, profile `tck,tck-official`) — the TCK version comes from the pom, so bumping the property is enough for CI. The vidocq OpenAPI runner was fixed to TCK 4.1.1 (346) on 2026-08-27; the runtime total is 1868.

### 0.1 What MicroProfile 7.2 contains

| Component | MP 7.1 | MP 7.2 | Change |
|---|---|---|---|
| Config | 3.1 | 3.1 | none |
| Fault Tolerance | 4.1 | 4.1 | none |
| Health | 4.0 | 4.0 | none (Health 4.1 exists as an Eclipse record but was never published to Central; not in the platform) |
| JWT Auth (RBAC) | 2.1 | **2.2** | when `mp.jwt.verify.publickey.algorithm` is **not** set, **both RS256 and ES256 MUST be accepted** (spec §configuration). API jar is binary-identical to 2.1 (0 class diff). TCK adds `RsaAndEcSignatureAlgorithmTest` (`testRS256Token`, `testES256Token`) backed by `META-INF/microprofile-config-rsa-ec.properties` (`mp.jwt.verify.publickey.location=/rs256es256.jwk`, no algorithm) and a JWKS holding one RSA key (`kid=rskey`) and one EC key (`kid=eckey`). TCK **removes** the `container/ejb`, `container/jacc`, `container/servlet` packages. |
| OpenAPI | 4.1 | **4.2** | API: `@Header` gains `example()` and `examples()`; `Schema` now overrides all `Extensible` methods (`getExtensions/setExtensions/addExtension/removeExtension/hasExtension/getExtension`) with schema-specific semantics — "for the base OAS 3.1 dialect, Schema instances consider all unknown properties to be extensions"; `@Header.allowEmptyValue` is `@Deprecated`; `@ExternalDocumentation` on `TYPE` is deprecated (annotation still targets METHOD+TYPE). Spec: Bean Validation `@Digits` must be processed. TCK adds `ExternalDocumentationAnnotationTest` (1 test method × 2 formats: method-level `@ExternalDocumentation` → `paths.'/a'.get.externalDocs`) and `SchemaExtensionPropertyTest` (6 pure-model tests), and extends `AirlinesAppTest` (header `example`/`examples`) and `beanvalidation.BeanValidationTest` (`@Digits`). |
| Rest Client | 4.0 | 4.0 | none |
| Telemetry | 2.1 | **2.2** | "Adopt the latest OpenTelemetry" (issue #318: the 2.2 tag pins `opentelemetry.java.version=1.66.0`, `opentelemetry.java.instrumentation.version=2.31.1`, `opentelemetry.semconv.version=1.44.0`) and **`code.function.name` MUST be present on `@WithSpan` spans** (issue #321 / PR #319). TCK assertion (`RestClientSpanTest.spanChild`): attribute value = `org.eclipse.microprofile.telemetry.tracing.tck.rest.RestClientSpanTest$SpanBean.spanChild`, i.e. **`Class.getName()` (binary name, `$` for nested) + "." + method name**. Instrumentation-annotations 2.30 adds `WithSpan.inheritContext()` (default `true`). |
| Jakarta EE Core Profile | 10 min. | 10 min., **11 explicitly allowed** | Vidocq already targets Core Profile 11 (CDI 4.1 Lite, REST 4.0, Annotations 3.0, Interceptors 2.2) — now spec-endorsed. |

Out of scope: MicroProfile GraphQL 2.1 (released 2026-08-07, standalone, not part of the platform).

### 0.2 Artifact availability (2026-10-04)

| Artifact | Maven Central | Eclipse staging (ballot) | Version used by this plan |
|---|---|---|---|
| `org.eclipse.microprofile.jwt:microprofile-jwt-auth-api` / `-tck` | **2.2** (final) | 2.2.1 (same content) | **2.2** |
| `org.eclipse.microprofile.openapi:microprofile-openapi-api` / `-tck` | **4.2-RC5** (2026-09-30) | 4.2 | **4.2-RC5** → 4.2 in Task E1 |
| `org.eclipse.microprofile.telemetry:microprofile-telemetry-{tracing,metrics,logs}-tck` | **2.2-RC3** (2026-09-17) | 2.2 | **2.2-RC3** → 2.2 in Task E1 |
| `org.eclipse.microprofile:microprofile` (platform BOM) | 7.1 | 7.2 not staged | Vidocq does not import the BOM — no action |
| `io.opentelemetry:opentelemetry-bom` | 1.66.0 | — | **1.66.0** (the version the Telemetry 2.2 TCK pins) |
| `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations` | 2.32.0 | — | **2.31.1** (pinned by the TCK) |
| `io.opentelemetry.semconv:opentelemetry-semconv` | 1.44.0 | — | **1.44.0** (stable artifact, replaces `1.27.0-alpha`; TCK-only) |
| Config 3.1.2, FT 4.1.2, Health 4.0.2, Rest Client 4.0 | — | — | unchanged components; the platform still names Config 3.1 / Health 4.0.1 — no bump in this plan |

Never commit the staging repositories into a pom: they disappear once the ballot closes.

### 0.3 OpenTelemetry API 1.39.0 → 1.66.0 impact on humboldt (verified with `javap`)

- 1.64.0 → 1.66.0: public API identical (2026-10-04). 1.39.0 → 1.64.0 (2026-08-27): **zero abstract methods added** on the 27 API interfaces humboldt implements (`Tracer`, `TracerProvider`, `Span`, `SpanBuilder`, `Meter`, `MeterProvider`, all `*Counter/*Histogram/*Gauge(+Builder)`, `Observable*Measurement`, `Logger`, `LoggerProvider`, `LogRecordBuilder`) nor on `io.opentelemetry.context.{Scope,ContextStorage,ContextStorageProvider}`. The bump is source-compatible.
- Only new package in `opentelemetry-api` is internal (`io.opentelemetry.api.impl`) — the ModiTect `module-info.java` of `humboldt-otel-api` needs no new `exports`.
- `opentelemetry-semconv` (1.43.0 and later) provides `io.opentelemetry.semconv.CodeAttributes.CODE_FUNCTION_NAME` (`code.function.name`) — humboldt does **not** depend on semconv at runtime (plain `AttributeKey` constants), keep it that way.

### 0.4 Current state of the workspace (2026-10-04, `origin/main` of each repo)

| Repo | Pinned today | Target |
|---|---|---|
| `cervantes` | `microprofile.jwt.version=2.1` (root pom l.62, `cervantes-tck/pom.xml` l.36), script `run-official-tck-mp-jwt-2.1.sh`, TCK 206/206 | 2.2, 208 expected |
| `grimm` | `version.mp.openapi=4.1` (root pom l.72), `grimm-tck/pom.xml` `microprofile.openapi.version=4.1` (l.21), script `run-official-tck-mp-openapi-4.1.sh`, TCK 349/349 | 4.2-RC5 → 4.2, 367 actual in the grimm harness (364 official + 3 grimm-local tests; 349 + 2 + 6 + 8 `@Digits` + 2 AirlinesApp header-example cases) |
| `humboldt` | `microprofile.telemetry.version=2.1`, `opentelemetry.version=1.39.0`, `opentelemetry.instrumentation.version=2.7.0`, `opentelemetry.semconv.version=1.27.0-alpha` (root pom l.64-67, `humboldt-tck/pom.xml` l.47-52), script `run-official-tck-telemetry-2.1.sh`, TCK 85/85 | OTel 1.66.0 / 2.31.1 / 1.44.0, Telemetry TCK 2.2-RC3 → 2.2 |
| `vidocq` | runners: `vidocq-runtime-tck-cervantes-jwt` (jwt tck 2.1, port 18086), `vidocq-runtime-tck-grimm-openapi` (openapi tck 4.1.1, 346, port 18085), `vidocq-runtime-tck-humboldt-telemetry` (telemetry 2.1, OTel 1.39.0/2.7.0/1.27.0-alpha); `TCK.md` table titled "MicroProfile 7.1", 1868 green; five runners gate PRs via `ci.tck.modules` (root pom l.88) | 2.2 / 4.2-RC5 / 2.2-RC3 + OTel 1.66; docs say 7.2 |

---

## Global Constraints

- **Working copies:** each repo is worked in a dedicated git worktree already on branch `pr/ybl/mp-7.2` (cut from `origin/main` on 2026-10-04): `/Users/yblazart/projects/perso/vidocq/.worktrees/{cervantes,grimm,humboldt,vidocq}-mp-7.2`. Paths written below as `cervantes/…`, `grimm/…`, `humboldt/…`, `vidocq/…` mean these worktrees — never the main checkouts under `/Users/yblazart/projects/perso/vidocq/<repo>` (they sit on other branches). Do not run `git checkout -b`: the branch exists.
- **Toolchain:** JDK 25 (Temurin) + Maven 3.9.16, pinned via `.sdkmanrc` in each sub-project — run `sdk env` in the sub-project before building (`sdk env` breaks under `set -u`; invoke `mvn`/`./mvnw` directly inside scripts). All POMs `modelVersion 4.0.0`; every artifact is `0.4.0-SNAPSHOT`.
- **Build hygiene:** always `./mvnw clean install` / `clean test`, never bare `install` or an isolated `test` — codegen modules give false failures on stale `target/`. Never `-U`; `-o` only when you know the local M2 is complete. Bricks must be `clean install`ed into the local M2 **before** the vidocq runtime is built (it resolves `cervantes/grimm/humboldt:0.4.0-SNAPSHOT` from M2).
- **JPMS strict:** every production module keeps its `module-info.java`; TCK runner modules carry no `module-info.java` (unnamed). No new `opens`. No new runtime dependency — the only dependency changes in this plan are version bumps of already-present Jakarta/MicroProfile/OpenTelemetry artifacts (`dependency-gatekeeper` agent must review every `pom.xml` diff anyway).
- **TDD:** each behavioural change starts with a failing unit test in the brick, then the official TCK is the integration gate. Run every TCK **yourself** and read the surefire/TestNG summary — never trust an agent's "green" report (known false positives in both directions). Keep raw logs under a scratch directory (written `$LOGS` below).
- **Language:** all code, comments, Javadoc, test names, commit messages, `.md`/`.adoc` files in **English**. Chat with the maintainer in French.
- **Commits:** Conventional Commits, GPG-signed, DCO `Signed-off-by` (`git commit -S --signoff`), and — per the workspace `CLAUDE.md` AI-policy — the provenance trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` must be present (the `Signed-off-by` that the hook adds may follow it). Author/committer stay the human maintainer. Check with `git log -1 --format='%G? %(trailers)'` → `G` + `Signed-off-by` + `Co-Authored-By`.
- **Branches / merge order:** branch `pr/ybl/mp-7.2` in `cervantes`, `grimm`, `humboldt` (independent, worked in parallel), then `pr/ybl/mp-7.2` in `vidocq` (depends on the three bricks being installed locally, later merged and published as SNAPSHOT). **Tasks never push and never open PRs** — pushing (`ssh -4`, Codefloe IPv6 hangs) and the PRs on Codefloe (`https://codefloe.com/Vidocq/<repo>`, `tea pr create`) are done by the controller after the final review, once the maintainer agrees; the `governance-checks` gate needs the CLA handle `@yblazart`, GPG and sign-off.
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
cd cervantes
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

In `cervantes-tck/pom.xml` around l.293-300 remove the three `<exclude>org/eclipse/microprofile/jwt/tck/container/{ejb,jacc,servlet}/**</exclude>` lines (those packages no longer exist in the 2.2 `tests` jar) and reword the comment to "MP JWT 2.2 dropped the EJB/JACC/Servlet container tests". Keep `<excludedGroups>ee-security-optional</excludedGroups>` — verify it is still referenced: `mkdir -p $LOGS/jwt22 && cd $LOGS/jwt22 && unzip -qo ~/.m2/repository/org/eclipse/microprofile/jwt/microprofile-jwt-auth-tck/2.2/microprofile-jwt-auth-tck-2.2-tests.jar && grep -rl "ee-security-optional" . | head` (resolve the jar first with a build if it is not in the M2 yet); if nothing references it, remove the `excludedGroups` too.

- [ ] **Step 3: Rename the script and fix its internals**

`git mv run-official-tck-mp-jwt-2.1.sh run-official-tck-mp-jwt-2.2.sh` then `grep -n "2\.1" run-official-tck-mp-jwt-2.2.sh` and update every hard-coded `2.1` (labels, artifact coordinates) to `2.2`.

- [ ] **Step 4: Run the official TCK**

Run: `cd cervantes && ./mvnw -ntp clean install -DskipTests && ./run-official-tck-mp-jwt-2.2.sh 2>&1 | tee $LOGS/cervantes-tck-2.2.log` (read the script first: if it takes a mode argument, use the full-suite mode, never a smoke subset)
Expected: `Tests run: 208, Failures: 0, Errors: 0` (206 from 2.1 + `RsaAndEcSignatureAlgorithmTest.testRS256Token` + `.testES256Token`). If `RsaAndEcSignatureAlgorithmTest` fails, the JWKS path is the suspect: `JwksKeyResolver.lookup` resolves by `kid` (`rskey`/`eckey`) and `JwkParser` supports `kty=EC` — check the `alg`/`use` filtering in `JwkParser` does not drop one of the two keys. Log any real defect in `cervantes/BUG.md`.

- [ ] **Step 5: Update the docs**

- `TCK.md`: title "MicroProfile JWT 2.2", artifact `microprofile-jwt-auth-tck:2.2`, result line "208/208 (date)", note on removed ejb/jacc/servlet packages.
- `README.md`: "MicroProfile JWT 2.2", script name, TCK count.
- `docs/en/modules/ROOT/pages/reference.adoc:165-166`: "Expected algorithm family (`RS256`, `ES256`). **Unset: both RS256 and ES256 are accepted (MP JWT 2.2).** When set, tokens announcing another family are rejected."
- `concepts.adoc:97` and `migration.adoc:34`: same nuance (one sentence each).
- `index.adoc` / any `2.1` mention: `grep -rn "JWT 2\.1" docs README.md` → 2.2.

- [ ] **Step 6: Commit** (no push — see Global Constraints)

```bash
git add -A
git commit -S --signoff -m "build(tck): run the official MicroProfile JWT 2.2 TCK (208/208) and document the 2.2 behaviour"
```
The controller later opens the PR on Codefloe (`Vidocq/cervantes`), title `feat: MicroProfile JWT Auth 2.2 (MicroProfile 7.2)`.

---

## Phase B — Grimm: MicroProfile OpenAPI 4.2

### Task B1: bump the API to 4.2-RC5 and confirm the build

**Files:**
- Modify: `grimm/pom.xml:72` (`<version.mp.openapi>4.1` → `4.2-RC5`)
- Modify: `grimm/grimm-tck/pom.xml:21` (`<microprofile.openapi.version>4.1` → `4.2-RC5`)

- [ ] **Step 1: Bump and build**

Run: `cd grimm && sed -i '' 's#<version.mp.openapi>4.1</version.mp.openapi>#<version.mp.openapi>4.2-RC5</version.mp.openapi>#' pom.xml && sed -i '' 's#<microprofile.openapi.version>4.1</microprofile.openapi.version>#<microprofile.openapi.version>4.2-RC5</microprofile.openapi.version>#' grimm-tck/pom.xml && ./mvnw clean install`
Expected: BUILD SUCCESS — the 4.2 API is purely additive (verified: 0 class-level diff, only new methods on `Schema`/`@Header`). `SchemaImpl` already inherits the `Extensible` implementations from `AbstractExtensibleRef`, so the new abstract overrides on `Schema` are satisfied.

- [ ] **Step 2: Check the JPMS view of the new API jar**

Run: `jar --describe-module --file ~/.m2/repository/org/eclipse/microprofile/openapi/microprofile-openapi-api/4.2-RC5/microprofile-openapi-api-4.2-RC5.jar | head -3`
Expected: the same module name grimm's `module-info.java` files `requires` today (`grep -rh "requires.*openapi" grimm-*/src/main/java/module-info.java`). If it changed, update the `requires` lines.

- [ ] **Step 3: Commit**

```bash
git commit -S --signoff -am "build: target MicroProfile OpenAPI API 4.2-RC5 (MicroProfile 7.2)"
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

### Task B6: run the official OpenAPI 4.2-RC5 TCK, docs

**Files:**
- Rename: `grimm/run-official-tck-mp-openapi-4.1.sh` → `run-official-tck-mp-openapi-4.2.sh` (fix internal `4.1` strings)
- Modify: `grimm/README.md:3,7,20,36-37`, `grimm/TCK.md`, `grimm/docs/en/modules/ROOT/pages/*.adoc` (`grep -rn "4\.1" docs`)

- [ ] **Step 1: Full TCK**

Run: `cd grimm && ./mvnw -ntp clean install -DskipTests && ./run-official-tck-mp-openapi-4.2.sh all 2>&1 | tee $LOGS/grimm-tck-4.2-RC5.log`
Expected: `Tests run: 367, Failures: 0` (actual: 364 official + 3 grimm-local tests; 349 + `ExternalDocumentationAnnotationTest` 1 method × 2 formats + `SchemaExtensionPropertyTest` ×6 + 8 `@Digits` cases + 2 AirlinesApp header-example cases). Watch specifically `AirlinesAppTest` (new header-example and `tags.find{…}.description` assertions) and `BeanValidationTest`. Any red test → fix in the relevant task above (B2–B5), re-run. Never use the `smoke` mode as evidence (memory: smoke is broken, always `all`).

- [ ] **Step 2: Docs + commit** (no push — see Global Constraints)

Update README ("MicroProfile OpenAPI 4.2", "367/367", script name), `TCK.md`, Antora pages. Note in `TCK.md`: "run against `4.2-RC5` (byte-identical to the 4.2 final under ballot); re-run on the 4.2 final (Task E1)".

```bash
git add -A && git commit -S --signoff -m "build(tck): run the official MicroProfile OpenAPI 4.2-RC5 TCK (367/367)"
```
The controller later opens the PR `Vidocq/grimm`: `feat: MicroProfile OpenAPI 4.2 (MicroProfile 7.2)`.

---

## Phase C — Humboldt: MicroProfile Telemetry 2.2

### Task C1: adopt OpenTelemetry 1.66.0 / instrumentation-annotations 2.31.1 / semconv 1.44.0 and the Telemetry 2.2-RC3 TCK

**Files:**
- Modify: `humboldt/pom.xml:64-67`, `humboldt/humboldt-tck/pom.xml:47-52`
- Rename: `humboldt/run-official-tck-telemetry-2.1.sh` → `run-official-tck-telemetry-2.2.sh` (fix every internal `2.1` label/coordinate)

The 2.2-RC3 TCK compiles against the versions it pins (OTel 1.66.0 / 2.31.1 / semconv 1.44.0), so the OTel bump and the TCK bump land together. Until Tasks C2 and C3 are done, exactly one TCK assertion is expected to fail: `RestClientSpanTest.spanChild` (`code.function.name`).

- [ ] **Step 1: Bump**

```bash
cd humboldt
sed -i '' -e 's#<opentelemetry.version>1.39.0<#<opentelemetry.version>1.66.0<#' \
          -e 's#<opentelemetry.semconv.version>1.27.0-alpha<#<opentelemetry.semconv.version>1.44.0<#' \
          -e 's#<opentelemetry.instrumentation.version>2.7.0<#<opentelemetry.instrumentation.version>2.31.1<#' \
          -e 's#<microprofile.telemetry.version>2.1<#<microprofile.telemetry.version>2.2-RC3<#' \
          pom.xml humboldt-tck/pom.xml
git diff --stat   # expect 2 files, 8 lines
git mv run-official-tck-telemetry-2.1.sh run-official-tck-telemetry-2.2.sh
grep -n "2\.1" run-official-tck-telemetry-2.2.sh   # update every hard-coded 2.1
```
Also `grep -rn "telemetry-2\.1\|run-official-tck-telemetry" --include=pom.xml --include=*.yml --include=*.md .` and update any reference to the old script name (keep historical entries in `TCK.md`/`PLAN.md` as they are).

- [ ] **Step 2: Clean build, then inspect the shaded JPMS modules**

Run: `./mvnw clean install`
Expected: BUILD SUCCESS (§0.3: no new abstract method on any implemented interface). If `humboldt-tck` fails to compile on a semconv symbol that moved to the incubating artifact, add `io.opentelemetry.semconv:opentelemetry-semconv-incubating:1.44.0-alpha` **test scope in `humboldt-tck` only**.
Then: `jar --describe-module --file humboldt-otel-api/target/humboldt-otel-api-0.4.0-SNAPSHOT.jar` and the same for `humboldt-otel-context` and `humboldt-otel-instrumentation-annotations` — compare with the same command on the `origin/main` build (build it in a scratch clone or read the hand-written ModiTect descriptors in the poms): module names and `exports` must be identical (`io.opentelemetry.api.impl` is internal and must **not** be exported).

- [ ] **Step 3: Run the 2.2-RC3 TCK and record the baseline**

Run: `./run-official-tck-telemetry-2.2.sh 2>&1 | tee $LOGS/humboldt-tck-2.2-RC3-c1.log` (read the script first; use its full-suite mode).
Expected: every test green except `RestClientSpanTest.spanChild` (missing `code.function.name`, fixed in C2). Record the exact `Tests run` figure in the task report — any other red test is a regression of the OTel bump and must be fixed here (or logged in `humboldt/BUG.md` with `/log-bug` if it is a pre-existing defect).

- [ ] **Step 4: Commit** `build: adopt OpenTelemetry 1.66.0 and the MicroProfile Telemetry 2.2-RC3 TCK`.

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

### Task C4: official Telemetry 2.2-RC3 TCK green, docs

**Files:**
- Modify: `humboldt/TCK.md`, `humboldt/README.md` (l.5, l.11), `humboldt/docs/en/modules/ROOT/pages/{index,concepts,getting-started,internals,tck}.adoc` (whichever mention the spec/OTel/TCK versions — `grep -rn "2\.1\|1\.39" README.md docs`)

- [ ] **Step 1: Full TCK**

Run: `cd humboldt && ./mvnw -ntp clean install -DskipTests && ./run-official-tck-telemetry-2.2.sh 2>&1 | tee $LOGS/humboldt-tck-2.2-RC3.log`
Expected: same `Tests run` figure as the C1 baseline, **0 failures, 0 errors** — `RestClientSpanTest.spanChild` now green.

- [ ] **Step 2: Docs + commit** (no push — see Global Constraints)

`TCK.md`: new section "MicroProfile Telemetry 2.2 (TCK 2.2-RC3, byte-identical to the 2.2 final under ballot; re-run on the final in Task E1)" with the date and the figure; keep the 2.1 history. README and Antora pages: "MicroProfile Telemetry 2.2 (OpenTelemetry 1.66)", TCK figure, new script name.

```bash
git add -A && git commit -S --signoff -m "build(tck): run the official MicroProfile Telemetry 2.2-RC3 TCK and document 2.2"
```
The controller later opens the PR `Vidocq/humboldt`: `feat: MicroProfile Telemetry 2.2 — OTel 1.66, code.function.name, inheritContext`.

---

## Phase D — Vidocq runtime: runners and documentation

### Task D1: bump the in-reactor TCK runners and re-run them on the assembled runtime

**Files:**
- Modify: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-cervantes-jwt/pom.xml` (description/scope comment around l.16-28, `<!-- MicroProfile 7.1 pins JWT 2.1 -->` + `microprofile.jwt.tck.version` 2.1 → 2.2 at l.34-35; drop the `container/{ejb,jacc,servlet}` excludes at l.215-222 and reword their comment)
- Modify: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-grimm-openapi/pom.xml:31-33` (comment → "MicroProfile 7.2 pins OpenAPI 4.2 — 4.2-RC5 until the final reaches Maven Central (byte-identical)", `4.1.1` → `4.2-RC5`)
- Modify: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-humboldt-telemetry/pom.xml:31-38` (comment, `microprofile.telemetry.version` 2.1 → 2.2-RC3, `opentelemetry.version` 1.66.0, `opentelemetry.instrumentation.version` 2.31.1, `opentelemetry.semconv.version` 1.44.0)
- Modify: `vidocq/vidocq-runtime-integration-tests/TCK.md` (results table + audit notes)

Prerequisite: the three bricks are `clean install`ed from their `pr/ybl/mp-7.2` worktrees into the local M2 (Phases A–C done) — the runtime resolves `cervantes/grimm/humboldt:0.4.0-SNAPSHOT` from there.

- [ ] **Step 1: Edit the three POMs** as listed, then `git diff` (expect ~15 changed lines).

- [ ] **Step 2: Run each runner and read the summary yourself**

```bash
cd vidocq && ./mvnw -ntp clean install -DskipTests
for m in cervantes-jwt grimm-openapi humboldt-telemetry; do
  ./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-$m clean test 2>&1 | tee $LOGS/vidocq-tck-$m.log | grep -E "Tests run:|BUILD"
done
```
Expected: JWT `208` (206 + `RsaAndEcSignatureAlgorithmTest` ×2), 0 failures; OpenAPI `364` actual in the runtime (346 + 18; record the figure and explain any delta vs grimm's own 367 in `TCK.md` — the two harnesses have historically counted differently: 346 vs 349 at 4.1); Telemetry `85` or the figure the brick harness gave in C1/C4, 0 failures. A "Failed to bind" error is a port flake — re-run once before investigating.

- [ ] **Step 3: Update `TCK.md`**

Table header "Spec (MicroProfile 7.2)", rows `JWT Auth 2.2 | 208`, `OpenAPI 4.2 (TCK 4.2-RC5) | <actual>`, `Telemetry 2.2 (TCK 2.2-RC3) | <actual>`, new total, date 2026-10-xx; one sentence explaining the RC = final relationship and that Task E1 re-runs on the finals. Rewrite the JWT paragraph: the ejb/jacc/servlet packages no longer exist in 2.2 (only `ee-security-optional` stays excluded if the runner still needs it).

- [ ] **Step 4: Commit** `build(tck): MicroProfile 7.2 runners — JWT 2.2, OpenAPI 4.2-RC5, Telemetry 2.2-RC3`.

### Task D2: documentation sweep "MicroProfile 7.1" → "7.2" (vidocq repository)

**Files (verified 2026-10-04):** `vidocq/README.md`, `vidocq/ROADMAP.md`, `vidocq/CLAUDE.md` (project overview line and the codename table: Cervantes "JWT Auth 2.1", Humboldt "Telemetry 2.1"), `vidocq/AGENTS.md` (same), `vidocq/CERTIFICATION.md` (add: "MicroProfile 7.2 explicitly allows Jakarta EE 11 Core Profile as the base"), `vidocq/docs/en/modules/ROOT/pages/whats-new.adoc`, `vidocq/docs/en/modules/ROOT/pages/tck.adoc`, the six runner `pom.xml` comments still saying "MicroProfile 7.1" (ravel-config, knock-health, cyrano-restclient…). Leave historical statements alone: `vidocq-runtime-cli/src/test/java/io/vidocq/runtime/cli/ext/KnownExtensionsTest.java:51` ("wired by the MicroProfile 7.1 certification PR #19") and dated plans/specs under `docs/superpowers/`.

- [ ] **Step 1: Find every occurrence** — `grep -rnE "MicroProfile 7\.1|MP 7\.1|JWT (Auth )?2\.1|Telemetry 2\.1|OpenAPI 4\.1" --include='*.md' --include='*.adoc' --include='pom.xml' . | grep -v /target/ | grep -v docs/superpowers/`

- [ ] **Step 2: Edit with the honest nuance**

- Product statements ("modular Java SE MicroProfile 7.2 runtime") → 7.2.
- TCK claims (`tck.adoc`, `whats-new.adoc`, `TCK.md`): "MicroProfile 7.2 component TCKs green on the assembled runtime: JWT 2.2, OpenAPI 4.2 and Telemetry 2.2 — run against the release candidates on Maven Central, byte-identical to the finals under ballot; re-run on the finals once published". No "certified"/"compatible" claim. Add a `[.tag-new]#NEW#` bullet in `whats-new.adoc` (badges are stripped at release time by `cut-docs-release.js`).
- `README.md` "MicroProfile 7.x Extensions" section: title → 7.2, per-extension spec versions (JWT 2.2, OpenAPI 4.2, Telemetry 2.2).

- [ ] **Step 3: Validate the AsciiDoc you touched** (render to HTML with `asciidoctor -o - <file>` and check the structure — zsh does not split `$VAR`, quote paths explicitly) and commit:

```bash
git commit -S --signoff -am "docs: MicroProfile 7.2 — spec versions, TCK status, Core Profile 11 endorsement"
```

---

## Phase E — Follow-ups and bookkeeping

### Task E1: switch to the finals once they reach Maven Central

Trigger: check the Maven Central metadata of the artifacts listed in §0.2: it shows `microprofile-openapi-api` release `4.2` and `microprofile-telemetry-tracing-tck` release `2.2` (and the platform BOM `microprofile:7.2`).
- [ ] grimm (`pom.xml`, `grimm-tck/pom.xml`), humboldt (`pom.xml`, `humboldt-tck/pom.xml`), vidocq (`vidocq-runtime-tck-grimm-openapi`, `vidocq-runtime-tck-humboldt-telemetry`): `4.2-RC5` → `4.2`, `2.2-RC3` → `2.2`; optionally JWT `2.2` → `2.2.1` everywhere (same content, matches the platform BOM). Re-run B6/C4/D1 step 2; update the `TCK.md`s ("finals"); one commit per repo `build: MicroProfile 7.2 final TCKs`.
- [ ] Only then: "MicroProfile 7.2 compatible" on the website, and the certification request.

### Task E2: documentation outside the four repositories

After the four PRs are merged: `vidocq-docs/content/home/modules/ROOT/pages/{index,roadmap}.adoc`, `vidocq-workspace/CLAUDE.md` (TCK section: "certification MicroProfile 7.1" → 7.2), workspace `CLAUDE.md`, `knock/CONTRIBUTING.md` (one mention), `governance/*` mentions if they state the target platform — one small docs PR per repository.

### Task E3: memory + roadmap

- [ ] Update the memory file `project_mp72_upgrade.md` with the new TCK counts, the `code.function.name` binary-name rule and the `SchemaImpl` single-store decision; `vidocq/ROADMAP.md`: tick "MicroProfile 7.2 TCK per implemented spec" once E1 is done.

---

## Phase F — Review follow-ups (decided by the maintainer on 2026-10-04)

The final whole-branch reviews of phases A–D found points that were parked (no second fix wave) plus pre-existing defects logged in `BUG.md`. The maintainer asked to fix all of them: the review follow-ups, the pre-existing defects found during the review, the Vauban inherited-method suspicion, and the stale pre-existing documentation. Same rules as the rest of the plan (Global Constraints): TDD for every behaviour change, official TCK re-run from a clean build in every brick touched, English only, no "certified"/"MicroProfile 7.2 compatible" wording, signed commits with the `Co-Authored-By` trailer, no push.

Working copies: the four `pr/ybl/mp-7.2` worktrees, plus Vauban in `/Users/yblazart/projects/perso/vidocq/.worktrees/vauban-inherited-method` on branch `pr/ybl/inherited-interceptor-method` (cut from `origin/main` 7384ac1). Vauban is a dependency of every brick: the Vauban lane never runs `install` until the controller says so (use `verify`), so the other lanes keep building against the Vauban already in `~/.m2`.

Every bug fixed here that has a `BUG.md` entry gets its entry closed (status, fix commit, date); every new defect found while working gets a new entry.

### Task FA1: cervantes — fail fast and the parked cervantes points

**Files:** `cervantes-cdi-vauban` (producer and a startup observer if needed), `cervantes-api/.../JwtConfig.java`, `cervantes-core/.../PemKeys.java`, `cervantes-core` tests, `cervantes-cdi-vauban` tests.

- [ ] An invalid MP JWT configuration (unrecognised `mp.jwt.verify.publickey.algorithm`, unreadable key, …) must fail when the application starts, not at the first injection of the validator (today the `@Dependent` producer fails lazily). Check what the runtime offers (CDI 4.1 Lite `jakarta.enterprise.event.Startup` observer in an application-scoped bean, or an eager check in the existing producer path) and make the failure happen at container start with the same clear message (property name, bad value, supported values). Keep "MP-JWT off" (no key configured) silent. TDD: a test that the container start (or the startup observer) fails with that message.
- [ ] `JwtConfig` six-argument compatibility constructor Javadoc: "RS256 and ES256 families are both accepted" → all RS256/384/512 and ES256/384/512 algorithms are accepted.
- [ ] `PemKeys.fromPem(String)`: keep the RSA failure too (`addSuppressed` on the thrown exception); add the missing test for invalid base64 (the `derOf` `IllegalArgumentException` path); make `privateKeyFromPem` use `derOf` instead of its own armour stripping.
- [ ] Producer-level test: an EC PEM with `mp.jwt.verify.publickey.algorithm=RS256` is rejected.
- [ ] Rewrite the garbled Javadoc of `JwtAuthConfigProducer` ("lues via Ravel", misplaced `</li> URL`, …) in proper English.

Commit per concern. Full `./mvnw -ntp clean install`, then the official TCK from a clean TCK module (`./mvnw -ntp -Ptck -pl cervantes-tck clean` then `./run-official-tck-mp-jwt-2.2.sh all`): 208/208.

### Task FA2: cervantes — stale pre-existing documentation and French text

- [ ] Translate every remaining French `<description>`/comment in the cervantes poms and resources to English.
- [ ] `CLAUDE.md`: stale `0.3.0-SNAPSHOT`, "outside `<subprojects>`" and any other statement contradicted by the current build; `ROADMAP.md` M0/M6 "Model 4.1.0" / "outside the reactor" statements (rewrite as dated history where they are history, fix where they claim the present); `docs/en/modules/ROOT/pages/reference.adoc` `0.3.0-SNAPSHOT`. Verify each fact against the current poms before writing it.

### Task FB1: grimm — static-file model fidelity

**Files:** `grimm-core` static mapper (`OpenApiModelMapper`), `DiscriminatorImpl`, `OpenApiValueMapper`, `YamlDeserializer`, `ConfigApplier`, header mapping; tests.

- [ ] `x-` keys inside a static `discriminator` must survive (the MP OpenAPI 4.2 `Discriminator` is not `Extensible`): keep them in a package-private side store on `DiscriminatorImpl` that the serializer writes back; TDD round-trip test (JSON and YAML).
- [ ] `$ref` values read from static files are kept verbatim in every schema position (no short-name expansion: `Pet.yaml` stays `Pet.yaml`); short-name expansion stays where the MP OpenAPI API asks for it (programmatic `setRef`/`ref`, annotations). Check first whether any TCK static document relies on expansion (`grep` the TCK jar resources); TDD.
- [ ] `YamlDeserializer` reads YAML 1.2 core-schema numbers (`1e3`, `.5`, `+1`, `-.5e-2`, …) as numbers; TDD.
- [ ] `ConfigApplier` (schemas from `mp.openapi.schema.*` config) builds schemas through the same typed static mapper instead of its minimal converter, so unknown non-`x-` keys and standard keywords are kept; TDD.
- [ ] Static-file `Header` mapping reads `style`, `explode` and `content`; TDD.

### Task FB2: grimm — the three defects logged in BUG.md on 2026-10-04

- [ ] `SchemaImpl.setAll` / `getAll` must follow the MP OpenAPI 4.2 `Schema` Javadoc: `getAll()` returns every non-null property (standard keywords and others), `setAll(map)` replaces all of them (standard keywords included). Read the 4.2 Javadoc (API sources jar or javap + the spec) and the TCK's `ModelConstructionTest`/`SchemaExtensionPropertyTest` before changing anything; keep the reflective setter dispatch from treating the names `extensions`/`all` as properties. TDD; the official TCK must stay 367.
- [ ] `SchemaGenerator`'s own scalar-only extension parser → use the shared `AnnotationModelMappings` extension logic (so `@Schema(extensions = @Extension(value = "{…}", parseValue = true))` yields an object), and map `externalDocs` extensions there too; TDD.
- [ ] APT path: `GrimmModelProcessor` ignores Bean Validation on scalar parameters while the runtime scanner applies it. Restore parity — simplest safe rule: a class whose operation parameters carry Bean Validation annotations is skipped by the processor (falls back to the scanner), consistent with the existing "all or nothing per class" safety valve; TDD in `grimm-processor`.
Close the three BUG.md entries.

### Task FB3: grimm — remaining review minors and stale documentation

- [ ] Replace the private `applyExtensions` pass-throughs in both scanners by direct calls to `AnnotationModelMappings`; de-duplicate the `toPlainString` rationale comment (one place, referenced from the other); manage the `jakarta.validation-api` test version in one property; add one end-to-end test through `SchemaGenerator` for `@Digits`; fix the test-source removal warning in `AnnotationScannerTest` (~339) with a narrow `@SuppressWarnings("removal")` or by testing through the non-deprecated path.
- [ ] TCK script: use `./mvnw` (not `mvn`) for the grimm-tck run.
- [ ] Stale documentation: the ShrinkWrap / "Model 4.1.0" / "do not reintegrate grimm-tck" rationale (`CLAUDE.md` ~40/92, `tck.adoc` ~71, `grimm-tck/README.md` ~7) — grimm-tck is an in-reactor module behind `-Ptck` now; `CLAUDE.md` ~146 names a non-existent `tck-suite.xml`; `CLAUDE.md` ravel `0.3.0-SNAPSHOT`; `ROADMAP.md` ~334 "TCK non-public artifact" risk and ~379 "official score 349/349" (history: say it counted harness tests). Verify each fact before writing it.
Run the official TCK from a clean build at the end of the lane: 367 (364 official + 3 local) unless FB1/FB2 change a count — explain any change.

### Task FC1: humboldt — telemetry correctness follow-ups

- [ ] Span `exception.type` uses `getCanonicalName()` like the OpenTelemetry SDK 1.66 `ExceptionAttributeResolver` (fallback to `getName()` when the canonical name is null), consistent with the log side; TDD.
- [ ] OTLP JSON: non-finite doubles (`NaN`, `Infinity`, `-Infinity`) are written as JSON strings like OpenTelemetry's `JsonEncoding` (plain and nested `Value`); TDD.
- [ ] `SdkLogRecordBuilder.setBody(Value<?>)`: keep the structured body (no `asString()` flattening) and export it as an OTLP `AnyValue`; the logging exporter prints a readable form; check the Logs TCK line patterns still match; TDD.
- [ ] The JUL bridge passes `LogRecord.getThrown()` to `setException`; TDD.
- [ ] BUG-20261004-01: `humboldt-otel-interop` provides its own `ComponentLoader` (a loader owned by a module that declares the needed `uses`) through an overridden `getComponentLoader()` of its config properties, so OpenTelemetry components that load services through `ComponentLoader` work on the module path; TDD with a module-path test if the build has one (or explain why it cannot be tested in-repo); close the BUG entry.
- [ ] `SpanDataMapper` is duplicated in `humboldt-otel-interop` and the humboldt-tck bridge → the TCK bridge reuses the interop one if the dependency direction allows it (otherwise explain).

### Task FC2: humboldt — documentation, French text, script

- [ ] TCK script: step 1 `install` also cleans.
- [ ] `humboldt-tck/README.md` "STANDALONE … OUTSIDE the reactor" → in-reactor behind `-Ptck`; remaining French comments (humboldt-tck pom ~416, `humboldt-cdi/pom.xml` ~36, `tck-suite.xml` ~3 which also says 2.1) → English and 2.2.
- [ ] Javadoc citations "MP Telemetry 2.1 §…" (~20, e.g. `HumboldtAutoConfigure`, `HumboldtTelemetryProducers`): cite the 2.2 specification where the section exists unchanged (check against the 2.2 spec asciidoc), keep 2.1 only where a 2.1-specific behaviour is described.
Run the official TCK from a clean build at the end of the lane: 85/85.

### Task FV1: Vauban — intercepted method inherited from a grandparent

- [ ] Write a test first: a bean `C extends B extends A` where an intercepted business method is declared on `A` (not overridden), intercepted through the generated subclass; assert that `InvocationContext.getMethod()` returns the method declared on `A` (declaring class `A`, original name), not the `$$super$…` bridge. Cover also a method declared on `B`, and a private/package-private edge if Vauban supports intercepting them.
- [ ] If the test fails, fix `VaubanInvocationContext.getMethod()` (~95-106) so it resolves the original method along the whole superclass chain (or, better, from information captured at generation time so no runtime search is needed — compile-time over reflection), and log/close a `BUG.md` entry. If the test passes, keep the test as a regression guard and record in the report why the suspicion was wrong.
- [ ] Run vauban's build with tests (`./mvnw -ntp clean verify`) and the CDI Lite TCK (`-Ptck`, read the surefire reports yourself: 774/774 expected per the Core Profile campaign) — never `install` until the controller says so.

### Task FD1: vidocq — shared OpenTelemetry versions, counts and stale documentation

- [ ] One definition of the OpenTelemetry versions (`opentelemetry.version`, `opentelemetry.instrumentation.version`, `opentelemetry.semconv.version`) in the vidocq root pom, used by `vidocq-runtime-humboldt-telemetry-extension`, `vidocq-runtime-it-humboldt-cassini` and `vidocq-runtime-tck-humboldt-telemetry` (remove the local definitions).
- [ ] Root `pom.xml` ~62 `ci.tck.modules` comment "1085" → 1105; README directory-tree comment (~132) lists Fault Tolerance; the French comment in `vidocq-runtime-it-cervantes-jwt/pom.xml` (~586) → English.
- [ ] Stale pre-existing documentation: README Servlet TCK paragraph ("Model 4.1.0", out-of-reactor runner), README Maven `4.0-rc-5` badge (the workspace uses Maven 3.9.16), `CERTIFICATION.md` "once 0.3.0 is released", `tck.adoc` (~99) "challenge drafted" (jakartaee/platform-tck#2730 was filed on 2026-07-14 and accepted), `ROADMAP.md` "out-of-reactor … champollion-tck" line. Verify each fact before writing it.
- [ ] After the brick lanes are done (bricks installed by the controller), run `./mvnw -ntp clean install` and the three changed runners again: JWT 208, OpenAPI 364, Telemetry 85.

---

## Self-review

**Spec coverage.** JWT 2.2 (both algorithms when unset, TCK 2.2, removed packages) → A1–A3. OpenAPI 4.2 (`@Header` example/examples → B3; `Schema` extension semantics → B2; `@Digits` → B5; method-level `@ExternalDocumentation` TCK → B4; `allowEmptyValue`/TYPE deprecations → B3 note + B4 note; TCK 4.2-RC5 → B6; final → E1). Telemetry 2.2 (OTel 1.66 + TCK 2.2-RC3 → C1; `code.function.name` → C2; `inheritContext` → C3; TCK green + docs → C4; final → E1). Unchanged components and Core Profile 11 endorsement → §0.1, D2. Runtime runners + docs → D1/D2. Outside docs → E2. Memory → E3.

**Placeholder scan.** Every code step carries the actual code; the only "verify first" items are deliberate (where `apply` is called in `SchemaGenerator`, whether `ee-security-optional` still exists in the 2.2 jar, how `SpanData` exposes attributes, the TCK scripts' argument conventions) and each says exactly what to grep.

**Type consistency.** `JwtConfig` 7th component `Optional<SignatureAlgorithm> requiredAlgorithm` is used with that name in A2 tests, validator and producer. `KeyResolvers.fromInlinePem/fromLocation` take `Optional<SignatureAlgorithm.Family>` everywhere. `PemKeys.fromPem(String)` (A1) is what `KeyResolvers.parsePem` (A2) calls. `toModelExample(ExampleObject)` (B3) is private per scanner. `CODE_FUNCTION_NAME` (C2) is a `AttributeKey<String>`.
