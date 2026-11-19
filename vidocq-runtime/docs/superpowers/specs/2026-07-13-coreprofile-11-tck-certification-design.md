# Jakarta EE Core Profile 11 — TCK coverage & certification path

**Date:** 2026-07-13
**Status:** approved design, pre-implementation
**Branch:** `pr/ybl/coreprofile-11-tck`
**Goal:** make the assembled Vidocq runtime pass the Jakarta EE Core Profile 11
TCK (profile bundle + every constituent spec TCK) with zero unjustified
exclusion, and document the Eclipse Foundation certification process — the two
gates the maintainer set before cutting the 0.3.0 release.

---

## 1. Background

Jakarta EE **Core Profile 11** is the small-runtime profile of Jakarta EE 11.
Its composition maps one-to-one onto the Vidocq ecosystem:

| Spec | Version | Vidocq brick |
|------|---------|--------------|
| Jakarta Annotations | 3.0 | vauban (lifecycle/`@Priority`) |
| Jakarta Contexts and Dependency Injection (**Lite**) | 4.1 | vauban |
| Jakarta Dependency Injection | 2.0 | vauban |
| Jakarta Interceptors | 2.2 | vauban |
| Jakarta JSON Processing | 2.1 | champollion |
| Jakarta JSON Binding | 3.0 | champollion |
| Jakarta RESTful Web Services | 4.0 | cassini |

Certifying the profile is a **two-tier** exercise (confirmed by the WildFly
Preview 34 request, `jakartaee/platform#978`, and the Open Liberty request
`#975`):

- **Tier "profile"** — the `jakarta-core-profile-tck-11.0.0` bundle. It does
  *not* re-bundle the constituent TCKs; it validates the *ability to combine*
  the component specs into composite applications (a JAX-RS resource that
  `@Inject`s a CDI bean and returns a JSON-B-serialised entity), plus
  **signature tests** of the Core Profile API packages.
- **Tier "constituent specs"** — each standalone spec TCK, listed in the cert
  request under *Additional Specification Certification Requirements*, must pass
  in the profile implementation. Jakarta **Interceptors 2.2 has no standalone
  TCK** — it is covered by the CDI TCK.

### Current Vidocq coverage

| Spec TCK | Runner | Status (2026-07-13) |
|----------|--------|---------------------|
| REST 4.0 | cassini (`cassini-tck`) + `vidocq-runtime-it-cassini-rest` | ✅ 2538, per-brick + assembled |
| JSON-P 2.1 | champollion (`champollion-tck`) | ✅ 178/179 — **0 fail**, remainder is a TCK skip |
| JSON-B 3.0 | champollion (`champollion-tck`) | ✅ 289/295 — **0 fail**, remainder are TCK skips |
| CDI 4.1 Lite (+ Interceptors 2.2) | `vauban/vauban-tck-runner` | ⚠️ **stale** — last log 2026-04-03, 810 run / 90 fail, *before* the whole assembled-runtime Vauban campaign (synthetic beans, BCE, array types, observers) |
| Annotations 3.0 | — | ❌ no runner |
| DI 2.0 (atinject) | — | ❌ no runner |
| **Core Profile 11 bundle** | — | ❌ **the new module** |

---

## 2. Scope

**In scope (maintainer chose "certification-grade complet"):**

- A new runner for the **Core Profile 11 TCK bundle** on the *assembled*
  runtime (composite tests + signature tests), zero exclusion.
- **Re-baseline and green** the CDI 4.1 Lite TCK (`vauban-tck-runner`).
- New **atinject (DI 2.0)** and **Annotations 3.0** TCK runners in vauban.
- A `CERTIFICATION.md` documenting the full Eclipse certification process and
  prerequisites.
- `TCK.md` updated with a Core Profile section.

**Out of scope (YAGNI — maintainer chose "doc + prérequis, dépôt plus tard"):**

- Filing the actual certification request.
- Re-running against the EFTL-signed binary (documented, executed post-release).
- A hosted public results page (only its template lives in the doc).
- The 0.3.0 release itself (follows this chantier).
- No separate Interceptors runner (covered by the CDI TCK).

---

## 3. Architecture

### 3.1 Tier "profile" — reuse the assembled-runtime container

**Decision:** the Core Profile bundle runs through the existing
`vidocq-runtime-arquillian` shared container (the one the MicroProfile campaign
hardened), **not** a bare JAX-RS harness.

- *Rationale:* it certifies the real boot path — `VidocqBootstrap` +
  ServiceLoader extensions + cassini-on-chappe serving real HTTP — which is the
  product actually shipped. It is the established precedent (8 MP runners), and
  the container already materialises ShrinkWrap archives to disk with a
  deployment TCCL and serves real HTTP (the Rest Client TCK exercised that at
  235/235).
- *Rejected alternative:* a standalone bare-metal JAX-RS runner. It would not
  exercise the assembled runtime, so it would not back a compatibility claim
  about the shipped product.

New module: `vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile`,
gated behind the Maven `tck` profile (a plain `install` neither downloads nor
runs it), consistent with the 8 MP runners.

**Deployment model:** Core Profile composite tests deploy JAX-RS applications
and drive them over HTTP. The assembled container already listens on chappe and
routes to cassini, so the composite endpoints are reachable exactly like the
REST / Rest Client TCK deployments. Protocol and enrichment reuse the MP
campaign wiring (Local protocol, per-test RequestContext recycling, CDI field +
parameter enrichment).

### 3.2 Signature tests

The Core Profile TCK bundle ships **sigtest**-based signature checks for the
profile API packages. They compare the API surface visible on the runtime's
module path against the recorded signature. They are mandatory for
certification. Wiring:

- The Core Profile API is `jakarta.platform:jakarta.jakartaee-core-api:11.0.0`.
  Vidocq must expose exactly those packages (via the individual spec API jars it
  already depends on) with no extra/missing public members.
- The signature runner is part of the bundle; the runner module invokes it
  against the assembled module path. Any mismatch is a real conformance defect
  to fix (usually an API jar version drift), not something to exclude.

### 3.3 Tier "constituent specs" — per-brick runners in vauban

Convention: a spec TCK lives in the brick that implements the spec. Vauban is
the CDI / DI / Interceptors / Annotations engine, so the three
CDI-family TCKs live there.

| Module | Action | Notes |
|--------|--------|-------|
| `vauban/vauban-tck-runner` | refresh | CDI 4.1 Lite (`cdi-tck-core-impl` 4.1.0), Lite suite. Covers Interceptors 2.2. **Critical path.** |
| `vauban/vauban-atinject-tck-runner` | new | DI 2.0 — drive `jakarta.inject:jakarta.inject-tck` by building the Car/Seat/Tire graph through the Vauban injector. Small, self-contained. |
| `vauban/vauban-annotations-tck-runner` | new | Annotations 3.0 — `@PostConstruct` / `@PreDestroy` / `@Priority` / common annotations processing. |

REST / JSON-P / JSON-B: **no change** — their existing green runners are
referenced as the profile's constituent evidence.

Note on reactor placement: like the other out-of-reactor TCK runners
(`cassini-tck`, `champollion-tck`, `vauban-tck-runner`), the two new vauban
runners are standalone `modelVersion 4.0.0` modules excluded from the parent
`<modules>`, driven by a `run-*.sh` script — decoupling the TCK release cadence
from the runtime, per the workspace convention.

### 3.4 Develop against Maven, certify against EFTL

Development and CI iterate against Maven Central artifacts
(`jakarta.tck:*`, `cdi-tck-*`, `jakarta.inject:jakarta.inject-tck`). The
`CERTIFICATION.md` records that the **final certification run must use the
EFTL-signed binaries** from `download.eclipse.org` (Core Profile TCK 11.0.0
SHA-256 `0357bfab7025972edb2bf50277b6b4206b499a2961bc94e783f34782cc4a9bda`) —
Maven Central jars cannot back a compatibility claim. This mirrors the decision
already recorded for the MicroProfile TCKs.

---

## 4. `CERTIFICATION.md` contents

The certification-process deliverable, at the vidocq repo root:

1. **Prerequisites** — a *released* product (0.3.0), target JDKs (WildFly
   certified on 17 **and** 21; Vidocq is Java 25 → certify on 21 and 25).
2. **TCK binaries** — the EFTL-signed Core Profile bundle + each constituent
   spec TCK, with download URLs and SHA-256 fingerprints (Annotations, CDI,
   DI, JSON-P, JSON-B, REST).
3. **Signature tests** — mandatory, part of each run.
4. **Public results summary** — one `.adoc` per JDK, in the shape of
   `wildfly/certifications` (product, version, JDK, OS, per-suite pass counts).
   Template included; hosting deferred.
5. **Filing** — a GitHub issue on `jakartaee/platform` with the `certification`
   label, the EFTL-acceptance checkbox, and the "all TCK requirements met"
   attestation.
6. **Approval** — lazy consensus after 2 weeks, or majority vote of the spec
   project; then the *Jakarta EE Compatible* logo becomes usable.

---

## 5. Risk & sequencing

**CDI Lite is the critical path.** The stale 90-fail log predates the entire
Vauban campaign; the failing families (SyntheticBean, SyntheticObserver,
Registration, InterceptorLifeCycle, AroundConstruct) are exactly what that
campaign reworked. The true number is unknown until re-run.

Delivery order:

1. **Re-baseline CDI Lite** (`vauban-tck-runner`, `clean test`) → real number,
   then fix any genuine gaps. Sizes the rest of the chantier.
2. **atinject + Annotations** runners (small, vauban).
3. **Core Profile 11 bundle** runner on the assembled runtime (composites +
   signature).
4. **`CERTIFICATION.md`** + `TCK.md` update.
5. *(post-chantier)* release 0.3.0; certification filing later.

Merge order follows the workspace rule: vauban first (any engine fixes), then
the vidocq runner + docs.

---

## 6. Success criteria

- CDI 4.1 Lite TCK: green on the Lite suite, exclusions only for
  documented out-of-Lite-scope tests.
- atinject (DI 2.0) TCK: green.
- Annotations 3.0 TCK: green.
- Core Profile 11 bundle (composites + signature): green on the assembled
  runtime, zero unjustified exclusion.
- REST / JSON-P / JSON-B: existing runs referenced, still green.
- `CERTIFICATION.md` complete and accurate; `TCK.md` updated.
- All verified by running the TCKs personally (not agent reports), logs kept.
