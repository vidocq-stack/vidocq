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
| Jakarta Expression Language | 6.0 | — | ❌ scope decision needed — [#66](https://codefloe.com/Vidocq/vidocq/issues/66) |
| Jakarta Faces | 5.1 | — | ❌ scope decision needed — [#66](https://codefloe.com/Vidocq/vidocq/issues/66) |
| Jakarta Standard Tag Library | 3.0 | — | ❌ scope decision needed — [#66](https://codefloe.com/Vidocq/vidocq/issues/66) |

(Jakarta Annotations, DI, Interceptors, JSON-P, JSON-B, REST are already covered by
the Core Profile 11 baseline, cf. `CERTIFICATION.md` §1.)

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
| **Umbrella** | [vidocq#67](https://codefloe.com/Vidocq/vidocq/issues/67) |

## 4. Open question: is Web Profile certification all-or-nothing?

Unlike the other gaps, the EL/Faces/JSTL view layer (§1, last three rows) raises a
scope question before an implementation question: these specs are hard to reconcile
with the ecosystem's zero-dynamic-reflection philosophy, and it is not yet confirmed
whether a partial Web Profile implementation can be certified, or whether all
constituent specs are mandatory for the compatibility claim (as they are for Core
Profile 11 — see `CERTIFICATION.md` §1's two-tier profile/constituent model, which
likely extends unchanged to Web Profile). This is tracked in
[#66](https://codefloe.com/Vidocq/vidocq/issues/66) and must be resolved before
committing to a Web Profile certification target (as opposed to just closing gaps
for their own sake).

## 5. Status

- **Planning phase.** No implementation started yet on any gap.
- Architecture already decided for the CDI Full / Portable Extensions gap (§2,
  `vauban/CDI-FULL.md`) — the one piece expected to need the most design work before
  coding starts.
- All other gaps (Security/Authn/Authz, Validation, WebSocket API, EL/Faces/JSTL) are
  at the "decide where it lives" stage — no repo or module assigned yet.
- This document will grow a certification section (TCK binaries, filing process,
  results) in the same shape as `CERTIFICATION.md` once the implementation gaps are
  closed enough to make that concrete.
