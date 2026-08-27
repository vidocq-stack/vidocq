# Jakarta EE Core Profile 11 — Certification

This document describes how Vidocq claims compatibility with **Jakarta EE Core
Profile 11**, the Eclipse Foundation process to follow, the exact TCK binaries
involved, and the current conformance status. It is the reference for filing the
compatibility request once Vidocq 0.3.0 is released.

> Status: **pre-filing.** All seven constituent specification TCKs pass; the
> profile-level composite TCK is at 10/13 on JDK 25 — every test reachable on
> JDK 25 passes, the remaining 3 being the documented JDK 25 TCK-helper
> incompatibility, for which the challenge is now **filed and accepted**
> (see [Status](#status)). The compatibility request itself is not yet filed —
> it certifies a *released* product, and 0.3.0 is not cut.

## 1. What Core Profile 11 certification requires

Jakarta EE Core Profile 11 bundles seven specifications, all implemented by the
Vidocq ecosystem:

| Specification | Version | Vidocq brick |
|---------------|---------|--------------|
| Jakarta Annotations | 3.0 | jakarta.annotation-api (standard jar) |
| Jakarta Contexts and Dependency Injection (Lite) | 4.1 | Vauban |
| Jakarta Dependency Injection | 2.0 | Vauban |
| Jakarta Interceptors | 2.2 | Vauban |
| Jakarta JSON Processing | 2.1 | Champollion |
| Jakarta JSON Binding | 3.0 | Champollion |
| Jakarta RESTful Web Services | 4.0 | Cassini |

Certification is a **two-tier** exercise (confirmed by the WildFly Preview 34 and
Open Liberty requests, `jakartaee/platform#978` and `#975`):

- **Profile tier** — the `jakarta-core-profile-tck-11.0.0` bundle: composite
  integration tests (a JAX-RS resource that `@Inject`s a CDI bean and returns a
  JSON-B entity) plus the Core Profile API **signature tests**.
- **Constituent tier** — each standalone specification TCK, listed in the request
  under *Additional Specification Certification Requirements*, must pass in the
  implementation. Jakarta **Interceptors 2.2 has no standalone TCK** — it is
  covered by the CDI TCK.

## 2. Prerequisites

- **Released product.** Certification claims a released, publicly downloadable
  version. Target: **Vidocq 0.3.0**.
- **JDK.** WildFly certified Core Profile 11 on JDK 17 and 21. Vidocq is
  JDK-25-native. See [The JDK 25 signature-walker issue](#the-jdk-25-issue) — it
  affects three composite tests and is a TCK-helper incompatibility, not a Vidocq
  defect.
- **EFTL TCK binaries.** The certifying run **must** use the EFTL-signed binaries
  from `download.eclipse.org` (below). Maven Central artifacts are fine for
  development and CI, but cannot back a compatibility claim.

## 3. TCK binaries (EFTL, download.eclipse.org)

Base: `https://download.eclipse.org/jakartaee/`

| TCK | Path | SHA-256 |
|-----|------|---------|
| Core Profile 11 | `coreprofile/11.0/jakarta-core-profile-tck-11.0.0.zip` | `0357bfab7025972edb2bf50277b6b4206b499a2961bc94e783f34782cc4a9bda` |
| Annotations 3.0 | `annotations/3.0/jakarta-annotations-tck-3.0.0.zip` | `9421c6ca66274d32dfb408848f75a42d57f120599fe0d8403c5c5c1141d5ac4d` |
| CDI 4.1 | `cdi/4.1/cdi-tck-4.1.0-dist.zip` | `446029ee1ce694d2a9ae8893d16be7afd7e1c0ed8705064b7095af174cf97ea0` |
| Dependency Injection 2.0 | `dependency-injection/2.0/jakarta.inject-tck-2.0.2-bin.zip` | `23bce4317ca061c3de648566cdf65c74b57e1264d6891f366567955d6b834972` |
| JSON Processing 2.1 | `jsonp/2.1/jakarta-jsonp-tck-2.1.1.zip` | `949f203de84deffa8c7892b555918e42f1dd220ccb7b6800741ea58af62737c1` |
| JSON Binding 3.0 | `jsonb/3.0/jakarta-jsonb-tck-3.0.0.zip` | `954fd9a3a67059ddeabe5f51462a6a3b542c94fc798094dd8c312a6a28ef2d0b` |
| RESTful Web Services 4.0 | `restful-ws/4.0/jakarta-restful-ws-tck-4.0.1.zip` | `b6290c1b5b3d2fdd9cc700a999243492a7e27b94a9b6af1974ff4dc5bfbf98f2` |

Each has a `.sha256` and `.sig` sidecar; the Specification Committee public key is
at `https://jakarta.ee/specifications/jakartaee-spec-committee.pub`.

## 4. How Vidocq runs each TCK

Development runs against Maven Central artifacts; the runners live behind the
Maven `tck` profile (a normal `mvn install` runs none of them).

| TCK | Runner | Command |
|-----|--------|---------|
| CDI 4.1 Lite (+ Interceptors 2.2) | `vauban/vauban-tck-runner` | `mvn -Ptck verify -pl vauban-tck-runner` |
| Dependency Injection 2.0 | `vauban/vauban-atinject-tck-runner` | `mvn -Ptck test -pl vauban-atinject-tck-runner` |
| Annotations 3.0 (signature) | `vidocq-runtime-integration-tests/vidocq-runtime-tck-annotations` | `mvn -Ptck test -pl …/vidocq-runtime-tck-annotations` |
| JSON-P 2.1 / JSON-B 3.0 | `champollion/champollion-tck` | `champollion/run-official-tck-json{p,b}-*.sh` |
| REST 4.0 | `cassini/cassini-tck` | `cassini/run-official-tck-restful-4.0.sh` |
| Core Profile 11 (composite + signature) | `vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile` | `mvn -Ptck test -pl …/vidocq-runtime-tck-coreprofile` |

The Core Profile composite runner boots the **assembled** runtime
(`VidocqBootstrap` + ServiceLoader extensions, Cassini-on-Chappe over real HTTP)
through the embedded Arquillian container — it certifies the shipped product, not
isolated bricks.

## 5. Signature tests

Signature tests are mandatory. They compare the API surface on the runtime's class
path against a recorded signature, using `jakarta.tck:sigtest-maven-plugin` with
`java.base` extracted via `jimage`. **There is no profile-level signature test**:
the Core Profile TCK's own `doc/asciidoc/sigtest.asciidoc` states the profile "has
no API artifact other than the utility api jar that is a combination of the
various component specifications" and defers entirely to each constituent's own
signature test. Status, each verified by actually running it (not just reading a
prior report):

| Spec | Signature test | Result |
|---|---|---|
| Annotations 3.0 | `CAJSigTestIT` (`vidocq-runtime-tck-annotations`) | ✅ 1/1 pass |
| JSON-P 2.1 | `JSONPSigTest` (`champollion-tck`, profile `jsonp-tck`) | ✅ 1/1 pass |
| JSON-B 3.0 | `JSONBSigTest` (`champollion-tck`, profile `jsonb-tck`) | ✅ 1/1 pass |
| RESTful WS 4.0 | `JAXRSSigTestIT` (`cassini-tck`) | ⏭️ excluded — documented TCK Process 1.4.1 challenge (TCK-environment limitation, needs a full `ts_home` layout; see `cassini/main/TCK.md` §2.2) |
| CDI 4.1 Lite (+ Interceptors 2.2) | `cdi-sigtest` profile (`vauban/vauban-tck-runner`, `mvn -Pcdi-sigtest verify -pl vauban-tck-runner`) | ✅ 0 failures against `cdi-api-jdk17.sig` |

The RESTful WS exclusion is a pre-existing, separately-documented challenge (not
new). The CDI signature test checks `jakarta.decorator`, `jakarta.enterprise.**`
and `jakarta.interceptor` against the official `cdi-api-jdk17.sig` (bundled inside
the `jakarta.enterprise:cdi-tck-core-impl:4.1.0` artifact — no separate EFTL
download needed). Vauban depends on the pristine `jakarta.enterprise.cdi-api` /
`jakarta.enterprise.lang-model` jars unmodified, so this asserts the untouched
official API rather than a Vauban-authored reimplementation, same principle as
the Champollion and Annotations signature tests. **All five constituent
signature-test requirements are now satisfied** (four green, one excluded under
a pre-existing documented challenge).

## 6. The JDK 25 issue

Three Core Profile *composite* tests fail on JDK 25 for a reason that is **not a
Vidocq conformance defect**: the TCK helper `ee.jakarta.tck.core.common.Utils`
calls `StackWalker.StackFrame.getDescriptor()` on a walker created **without**
`Option.RETAIN_CLASS_REFERENCE`. That method is documented across all JDKs to throw
`UnsupportedOperationException` in that case; JDK ≤ 21 implemented it leniently
(WildFly certified on 17/21), and JDK 25 enforces the documented contract.

Confirmed with a two-line reproducer: `getDescriptor()` returns the descriptor on
JDK 21 and throws `UnsupportedOperationException: No access to
RETAIN_CLASS_REFERENCE` on JDK 25. This is not an OpenJDK bug to report (JDK 25
follows the spec); it is a TCK-helper defect that surfaces on a JDK-25-native
implementation.

**Resolution path — done:** the challenge was filed and is **accepted**:
[`jakartaee/platform-tck#2730`](https://github.com/jakartaee/platform-tck/issues/2730)
(labels `challenge`, `accepted`; state OPEN — the issue was originally raised
against `eclipse-ee4j/jakartaee-tck` and cloned/transferred to `platform-tck`,
where Platform TCK challenges are tracked). The maintainers confirmed the exact
same fix (`StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)`)
already exists on `platform-tck`'s `main` branch, via a **pre-existing** commit
([`9597841`](https://github.com/jakartaee/platform-tck/commit/9597841d73e5ad16569161a9ae5951fb0665e707),
PR #1736, fixing #1735, merged January 2025) — it predates our challenge and
was never tied to it, but covers the identical root cause. That fix has **not
yet shipped in an official Core Profile TCK release**: `jakarta-core-profile-tck-11.0.0`
was cut before it landed. The challenge stays open pending a corrected TCK
release (tracked on the TCK committee's call agenda as of 2026-07-15); once
released, the 3 affected tests can be re-verified and the challenge closed.
Per the appeals process, an *accepted* challenge already excludes the affected
tests from the compatibility requirement even before the corrected release
ships.

## 7. Public results summary

Certification requires a public, durably-hosted results summary — one document per
JDK, in the shape of `wildfly/certifications`. Template:

```
Product:            Vidocq Runtime 0.3.0
Specification:      Jakarta EE Core Profile 11
TCK:                jakarta-core-profile-tck-11.0.0 (SHA-256 0357bfab…)
JDK:                <e.g. Temurin 25>
OS:                 <e.g. macOS 15 / Linux>
Core Profile TCK:   <n>/<n> passed  (minus challenged: Utils.getDescriptor ×3)
  Annotations 3.0 TCK:  1/1
  CDI 4.1 Lite TCK:     774/774
  Dependency Injection 2.0 TCK: pass
  JSON-P 2.1 TCK:       178/179 (0 fail)
  JSON-B 3.0 TCK:       289/295 (0 fail)
  REST 4.0 TCK:         2538
```

## 8. Filing

File a GitHub issue on `jakartaee/platform` with the `certification` label
(template: the issue body of `#978`), providing: organization, product name +
version + download URL, specification name + version, TCK version + SHA-256 +
download URL, the public results-summary URL(s), the *Additional Specification
Certification Requirements* (the six constituent TCKs above), the EFTL-acceptance
checkbox, and the attestation that all TCK requirements are met (including any
accepted challenges). Approval is by **lazy consensus after 14 days** or a majority
vote of the specification project; on approval Vidocq may use the *Jakarta EE
Compatible* logo and be listed as a compatible product.

## Status

- Constituent TCKs: **all seven green** — Annotations 1/1, CDI 4.1 Lite 774/774
  (incl. Interceptors 2.2), Dependency Injection 2.0 (atinject) pass, JSON-P
  178/179 (0 fail), JSON-B 289/295 (0 fail), REST 4.0 2538.
- Core Profile composite: **10/13** — every test reachable on JDK 25 passes. The
  3 not passing are the JDK 25 `getDescriptor()` TCK-helper incompatibility
  (challenge accepted; §6), unreachable on JDK 25 regardless of conformance. The
  former Cassini gaps (client JSON-B entity (de)serialisation, `Accept`
  negotiation) are fixed — Jakarta REST 4.0 TCK stays 2538/2538.
- Challenge: **filed and accepted** — `jakartaee/platform-tck#2730` (§6). Open
  pending an official corrected Core Profile TCK release; the fix itself
  already exists upstream.
- Signature tests: **all 5 satisfied** (Annotations, JSON-P, JSON-B, CDI 4.1 Lite
  verified green by direct run — CDI via the new `cdi-sigtest` profile in
  `vauban-tck-runner`, 0 failures against `cdi-api-jdk17.sig`; RESTful WS
  excluded via a documented pre-existing TCK-harness challenge). No open items
  remain in this requirement (§5).
- Not started: EFTL re-run, hosted results page, the certification issue — all
  post-0.3.0.
