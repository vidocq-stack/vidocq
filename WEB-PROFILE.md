# Jakarta EE Web Profile 11 — planning

This document tracks the planning work for going beyond **Jakarta EE Core Profile 11**
(see `CERTIFICATION.md`) towards **Jakarta EE Web Profile 11** certification. It is a
gap analysis and a pointer to the per-gap tracking issues and design docs — not yet a
certification process document (that will follow the same shape as `CERTIFICATION.md`
once the gaps below are closed).

> Status: **planning.** Umbrella tracking issue:
> [vidocq#67](https://codefloe.com/Vidocq/vidocq/issues/67).

## 1. What Web Profile 11 requires beyond Core Profile 11

Web Profile 11 is a superset of Core Profile 11 (all seven Core Profile specs,
already tracked in `CERTIFICATION.md`) plus:

| Specification | Version | Vidocq brick | Status |
|---|---|---|---|
| Jakarta Servlet | 6.1 | `foy` | ✅ delivered, official TCK passes |
| Jakarta Data | 1.0 | `mansart-jakarta-data` | ✅ delivered |
| Jakarta Transactions (JTA) | 2.0 | `mansart-transactions` | ✅ delivered (minimal JTA) |
| Jakarta Persistence (JPA) | 3.2 | `mansart-persistence` | ⏸️ suspended (M7, `mansart-data` covers current runtime needs) |
| Jakarta Contexts and Dependency Injection | 4.1 **Full** | Vauban | ❌ Vauban implements CDI **Lite** only — see [`vauban/CDI-FULL.md`](https://codefloe.com/Vidocq/vauban/CDI-FULL.md), [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38) |
| — Portable Extensions (part of CDI Full) | — | Vauban | ❌ isolated legacy module, architecture decided — [vauban#39](https://codefloe.com/Vidocq/vauban/issues/39) |
| Jakarta Security | 4.0 | — | ❌ no implementation — [#63](https://codefloe.com/Vidocq/vidocq/issues/63) |
| Jakarta Authentication | 3.1 | — | ❌ no implementation — [#63](https://codefloe.com/Vidocq/vidocq/issues/63) |
| Jakarta Authorization | 3.1 | — | ❌ no implementation — [#63](https://codefloe.com/Vidocq/vidocq/issues/63) |
| Jakarta Validation (Bean Validation) | 3.1 | — | ❌ no implementation — [#64](https://codefloe.com/Vidocq/vidocq/issues/64) |
| Jakarta WebSocket | 2.2 | Chappe (protocol only) | ❌ Chappe has raw RFC 6455, not the annotated API — [#65](https://codefloe.com/Vidocq/vidocq/issues/65) |
| Jakarta Concurrency | 3.1 | — | ❌ no implementation, excellent virtual-threads fit — [#72](https://codefloe.com/Vidocq/vidocq/issues/72) |
| Jakarta Enterprise Beans Lite | 4.0 | — | ❌ no implementation — [#71](https://codefloe.com/Vidocq/vidocq/issues/71) |
| Jakarta Expression Language | 6.0 | — | ❌ scope decision needed — [#66](https://codefloe.com/Vidocq/vidocq/issues/66) |
| Jakarta Faces | 5.1 | — | ❌ scope decision needed — [#66](https://codefloe.com/Vidocq/vidocq/issues/66) |
| Jakarta Standard Tag Library | 3.0 | — | ❌ scope decision needed — [#66](https://codefloe.com/Vidocq/vidocq/issues/66) |
| Jakarta Server Pages | 4.0 | — | ❌ scope decision needed, precompile-only candidate — [#73](https://codefloe.com/Vidocq/vidocq/issues/73) |
| Jakarta Debugging Support for Other Languages | 2.0 | — | ❌ SMAP support, comes with Server Pages — [#73](https://codefloe.com/Vidocq/vidocq/issues/73) |

(Jakarta Annotations, DI, Interceptors, JSON-P, JSON-B, REST are already covered by
the Core Profile 11 baseline, cf. `CERTIFICATION.md` §1.)

> Revision note (2026-09-06): the first version of this table omitted Enterprise
> Beans Lite 4.0, Concurrency 3.1, Server Pages 4.0 and Debugging Support 2.0. They
> are all required by Web Profile 11 and are now tracked (#71, #72, #73).

## 2. Why CDI Full is the pivotal gap

Almost every other gap above eventually needs CDI beans for injection (Security's
`HttpAuthenticationMechanism`, Validation's method-validation interceptor, WebSocket's
`@ServerEndpoint`, Faces' managed beans). Sequencing therefore matters: CDI Full is
tracked first, and specifically its **Portable Extensions** sub-problem, because it is
the one piece of CDI Full that does not fit the existing static-codegen pipeline (APT +
Class-File API) used everywhere else in the ecosystem. Full analysis, including the
architecture decision (an isolated, opt-in runtime-codegen module using the Class-File
API at runtime instead of a third-party bytecode library), lives in
[`vauban/CDI-FULL.md`](https://codefloe.com/Vidocq/vauban/CDI-FULL.md).

## 3. Tracking issues

| Gap | Issue |
|---|---|
| CDI 4.1 Full (decorators, conversation scope, EL, `BeanManager`) | [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38) |
| Portable Extensions — isolated legacy runtime-codegen module | [vauban#39](https://codefloe.com/Vidocq/vauban/issues/39) |
| Jakarta Security 4.0 / Authentication 3.1 / Authorization 3.1 | [vidocq#63](https://codefloe.com/Vidocq/vidocq/issues/63) |
| Jakarta Validation 3.1 (Bean Validation) | [vidocq#64](https://codefloe.com/Vidocq/vidocq/issues/64) |
| Jakarta WebSocket 2.2 (annotated API) | [vidocq#65](https://codefloe.com/Vidocq/vidocq/issues/65) |
| Jakarta EL 6.0 / Faces 5.1 / JSTL 3.0 (scope decision) | [vidocq#66](https://codefloe.com/Vidocq/vidocq/issues/66) |
| Jakarta Enterprise Beans Lite 4.0 | [vidocq#71](https://codefloe.com/Vidocq/vidocq/issues/71) |
| Jakarta Concurrency 3.1 | [vidocq#72](https://codefloe.com/Vidocq/vidocq/issues/72) |
| Jakarta Server Pages 4.0 + Debugging Support 2.0 (precompile-only?) | [vidocq#73](https://codefloe.com/Vidocq/vidocq/issues/73) |
| **Umbrella** | [vidocq#67](https://codefloe.com/Vidocq/vidocq/issues/67) |

## 4. Open question: is Web Profile certification all-or-nothing?

Unlike the other gaps, the legacy view layer (EL/Faces/JSTL/Server Pages, §1) raises a
scope question before an implementation question: these specs are hard to reconcile
with the ecosystem's zero-dynamic-reflection philosophy (Server Pages in particular
compiles Java *at runtime* in its classic form — see [#73](https://codefloe.com/Vidocq/vidocq/issues/73)
for the precompile-only alternative), and it is not yet confirmed
whether a partial Web Profile implementation can be certified, or whether all
constituent specs are mandatory for the compatibility claim (as they are for Core
Profile 11 — see `CERTIFICATION.md` §1's two-tier profile/constituent model, which
likely extends unchanged to Web Profile). This is tracked in
[#66](https://codefloe.com/Vidocq/vidocq/issues/66) and must be resolved before
committing to a Web Profile certification target (as opposed to just closing gaps
for their own sake).

Context for that decision: Quarkus and Helidon deliberately target Core Profile +
MicroProfile and *not* Web Profile. Faces, Server Pages and Enterprise Beans are the
parts of Jakarta EE that cloud-native deployments have largely moved away from. A
"Web Profile minus the view layer" (Core Profile + Servlet + Data + Persistence +
Security + Validation + WebSocket + Concurrency) is not certifiable as such, but is
what is actually deployed on Kubernetes today. Both targets are legitimate; the choice
between them is the real strategic question behind #66.

## 5. Kubernetes as the deployment platform — what it does and does not replace

Vidocq already follows the "one application = one process = one OCI image" model
(Java SE runtime, fat jar / jlink via `vidocq-runtime-maven-plugin`, extensions
discovered by `ServiceLoader`) rather than the legacy "application server hosting N
archives" model. Targeting Kubernetes as the deployment platform for a Web Profile
runtime is therefore not a new architecture — it is the mainstream one (Quarkus,
Helidon, Open Liberty, Payara Micro all do this). What matters is being precise about
**which application-server responsibilities Kubernetes actually takes over, and which
it does not**, because the temptation is to assume it solves more than it does.

### 5.1 Replaced by Kubernetes (and mostly already in place)

| Legacy application-server responsibility | Kubernetes equivalent | Vidocq status |
|---|---|---|
| Deployment, hot-deploy, multi-application hosting | `Deployment`, rolling updates, one image per app | ✅ existing model |
| Admin console / server configuration | `ConfigMap` / `Secret` → MicroProfile Config | ✅ `ravel` |
| Health checks / monitoring | readiness & liveness probes, OpenTelemetry | ✅ `knock`, `humboldt` |
| High availability / scaling | replicas, HPA | ✅ free |
| TLS termination, routing | Ingress / Gateway API | ✅ free |
| Cluster singletons, leader election (e.g. one active timer scheduler for Enterprise Beans `@Schedule`) | **Lease API** | ❌ to do — one of the few places where Kubernetes genuinely replaces app-server infrastructure ([#71](https://codefloe.com/Vidocq/vidocq/issues/71)) |

### 5.2 Not replaced by Kubernetes — still our problem

1. **Per-instance state.** `HttpSession`, `@SessionScoped`, `@ConversationScoped`
   (CDI Full), Faces view state, `@Stateful` session beans. Kubernetes replicates
   nothing. The only options are sticky sessions at the Ingress (fragile, lost on
   rescheduling), an external store (Valkey / Infinispan) behind the `SessionStore`
   SPI that `foy` already exposes, or a stateless-only discipline. This is directly
   tied to the CDI Full work: a conversation scope across replicas is state to be
   externalised, and Kubernetes does not change that.
2. **Transactions and XA recovery.** After a crash a transaction manager must replay
   its logs, which requires durable storage (`StatefulSet` + `PersistentVolumeClaim`,
   as Narayana does under Quarkus) or an architecture that avoids XA altogether (LRA,
   outbox). `mansart-transactions` is a *minimal* JTA; whether to go to full 2PC with
   recovery or stay local-resource-only is an open decision. Kubernetes does not help
   here.
3. **End-user identity.** Kubernetes manages *workload* identity (ServiceAccounts),
   not *user* identity. Jakarta Security's `IdentityStore` needs an external OIDC
   provider (Keycloak, Dex, …). A good fit for an OIDC-backed `IdentityStore`, but
   nothing Kubernetes-native ([#63](https://codefloe.com/Vidocq/vidocq/issues/63)).
4. **Runtime compilation.** Classic Server Pages engines run `javac` at request time;
   this conflicts with immutable images and with the static-generation philosophy.
   Build-time precompilation is the only sensible path
   ([#73](https://codefloe.com/Vidocq/vidocq/issues/73)).
5. **Persistence 3.2.** Suspended in `mansart`; probably the largest single chunk of
   work after CDI Full, and entirely orthogonal to Kubernetes.

### 5.3 Plausible shape

- One application = one Java module + Vidocq extensions → minimal jlink image →
  distroless OCI image. This is the existing packaging path.
- An opt-in, zero-dependency **`vidocq-runtime-kubernetes-extension`** talking to the
  Kubernetes REST API through `java.net.http` (no fabric8 client): Lease-based leader
  election, downward API for pod identity, an external `SessionStore` implementation,
  PVC-backed transaction log if full JTA recovery is pursued.
- **No operator / CRD.** Tempting, but scope creep: no value before there is a user
  base, and irrelevant to Web Profile conformance.

### 5.4 Honest assessment

Technical feasibility is not the risk — nothing above is exotic. The risk is
**volume**: roughly ten specifications to implement from scratch under the
zero-dependency rule, each with its own TCK (Faces, Persistence and Enterprise Beans
being notoriously heavy), plus the philosophical tension of making inherently stateful
specifications behave in a replicated, stateless-by-default environment. Kubernetes
solves the *operations* half of the "application server" problem; the *semantics* half
(state, transactions, identity) remains ours to implement.

## 6. Status

- **Planning phase.** No implementation started yet on any gap.
- Architecture already decided for the CDI Full / Portable Extensions gap (§2,
  `vauban/CDI-FULL.md`) — the one piece expected to need the most design work before
  coding starts.
- All other gaps (Security/Authn/Authz, Validation, WebSocket API, Concurrency,
  Enterprise Beans Lite, EL/Faces/JSTL/Server Pages) are at the "decide where it
  lives" stage — no repo or module assigned yet.
- Kubernetes is the assumed deployment platform (§5); no Kubernetes-specific code
  exists yet.
- This document will grow a certification section (TCK binaries, filing process,
  results) in the same shape as `CERTIFICATION.md` once the implementation gaps are
  closed enough to make that concrete.
