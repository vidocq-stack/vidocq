# Vidocq Runtime — Roadmap

Modular Java SE MicroProfile 7.2 runtime built on Vauban (CDI 4.1 Lite).
Each MicroProfile spec is delivered as an independent extension loaded via
ServiceLoader (Quarkus-inspired model). The runtime itself remains minimal:
SPI, lifecycle, packaging (fat jar / jlink via `vidocq-runtime-maven-plugin`).

> Product view: see [`README.md`](README.md). User documentation: `docs/`.

## Release plan

Decided on 2026-10-19, revised on 2026-10-27. Each version has a milestone on
[Vidocq/vidocq](https://codefloe.com/Vidocq/vidocq/milestones), which gathers its issues,
those of the bricks included; the board of all of them is the
[ROADMAP 1.0 project](https://codefloe.com/Vidocq/-/projects/890). Production readiness — performance, robustness, security —
comes **before** the Web Profile work.

The estimates are a projection, not a commitment: they are given by quarter (Q1–Q4) or by
half-year (S1, S2), and revisited at each release.

| Version | Content | Estimate |
|---|---|---|
| **0.4.0-RC1** | MicroProfile 7.2 on the staging artifacts; Servlet (Foy), Pages (Ibarra), Expression Language (Peano), Standard Tag Library (Bodoni) | Q4 2026 |
| **0.4.0** | MicroProfile 7.2 on the final artifacts; security baseline: `SECURITY.md` and a CVE process, dependency and code scanning in CI, a self-hosted SonarQube Community Build with the community branch plugin analysing every pull request (report mode first), static analysis in the Maven build for what SonarQube cannot do, the ecosystem's architecture rules as tests, a CycloneDX SBOM per release re-scanned on a schedule; Persistence 3.2 (Noether, Antoine's project) if its first release is out in time, else in 1.0.0 | Q4 2026 |
| **1.0.0-RC1 — production readiness** | The fixes found by the comparative benchmarks (exceptions thrown on the hot path, generated code left unused such as the static JSON-B generation); fuzzing of every parser exposed to the network; soak and chaos tests; end-to-end benchmarks on a dedicated bench with an external load generator, with a published, reproducible method and a performance regression gate in CI; threat model and internal audit; the static analysis findings fixed on the network-facing bricks; the external audit ordered | Q1 2027 |
| **1.0.0 — ready for production** | Core Profile + MicroProfile 7.2 + Persistence 3.2: clean, documented, fast. The fixes of the release candidate and of the external security audit; migration tooling from Spring Boot. The first stable line: the public APIs, configuration keys, extension SPI and Maven plugin goals stay compatible within 1.x | end of S1 2027 |
| **1.1.0 — Web Profile, part 1** | Validation 3.1, Concurrency 3.1, WebSocket 2.2, Security 4.0 + Authentication 3.1 + Authorization 3.1, Transactions full TCK and XA, Debugging Support 2.0; CDI 4.1 Full groundwork (Vauban: Full runner, failure map, deployment and archive model) | S2 2027 |
| **1.2.0 — Web Profile, part 2** | **CDI 4.1 Full**, Enterprise Beans Lite 4.0 | S1 2028 |
| **1.3.0 — Web Profile, part 3** | Faces 4.1, written from scratch | S2 2028 |
| **1.4.0** | Jakarta EE Web Profile 11 certification | S2 2028 |

**Why 1.0.0 at the end of S1 2027.** A 1.0 is a promise of stability, not the end of the feature
list, and a rushed one would get Vidocq taken for a toy. Its scope is kept narrow so the promise
holds: the certified Core Profile, MicroProfile 7.2 and Persistence 3.2. Servlet, Pages,
Expression Language and the Standard Tag Library ship before it, but are not part of that scope.
What 1.0.0 adds is the proof that the runtime holds in production: robustness, performance and
security, measured and published, and evidence gathered from two sides — the project's own
certifications, and users who confirm it runs their workloads. The release candidate can come as
early as mid-January 2027 if the performance fixes land; the time up to 1.0.0 goes to the
external audit and to those outside validations. The Web Profile then comes as 1.x releases,
which add without breaking; a breaking change would make a 2.0. The 0.4.0 dates depend on two
outside events: the MicroProfile 7.2 platform ballot and the first Persistence release. From
1.1.0 on, the dates follow the CDI Full estimates of the Vauban study (below). Gap analysis and
per-spec status of the Web Profile: [`WEB-PROFILE.md`](WEB-PROFILE.md).

### How the estimates are made

Measured on the 31 workspace repositories on 2026-10-19: a release every eight weeks (0.2.0
on 2026-07-04, 0.3.0 on 2026-08-30), and 13 merged work items a week over the last eight
weeks, 21 over the last four (a floor: many commits cite no issue). Sizes: S about a week,
M one to two weeks, L about three weeks, XL six to nine weeks; L and XL carry a 50 % margin,
CDI Full and Faces having the most design risk. The dates are revisited at each release. The 1.0.0
release candidate is the tightest step of the plan: eight to ten weeks of focused work, holidays
included; it holds only with days dedicated to the campaign.

### CDI 4.1 Full

Decided on 2026-10-19: Vauban implements **CDI 4.1 Full**, the version Jakarta EE Web Profile 11
requires; **no move to CDI 5 for now** (CDI 5 belongs to Jakarta EE 12, and its API is not a
superset of 4.1's). Study: [`vauban/docs/superpowers/plans/2026-10-19-cdi-4.1-full-study.md`](https://codefloe.com/Vidocq/vauban/src/branch/main/docs/superpowers/plans/2026-10-19-cdi-4.1-full-study.md)
([vauban#172](https://codefloe.com/Vidocq/vauban/pulls/172)): one shared engine with an optional
`vauban-full` module, portable extensions in an opt-in module generating at run time with the
Class-File API. The CDI 5 study ([vauban#171](https://codefloe.com/Vidocq/vauban/pulls/171)) stays
for later.

| Stage | Content | Engineer-days | Version |
|---|---|---|---|
| F0–F1 | Full runner on the CDI 4.1 Full TCK and failure map; deployment and archive model, effective metadata, invalidation of generated code | 11–17 | 1.1.0 |
| F2 | Portable extensions ([vauban#39](https://codefloe.com/Vidocq/vauban/issues/39)), `BeanManager` creation SPI | 18–30 | 1.2.0 |
| F3–F5 | XML and programmatic enablement, specialization, `InterceptionFactory`; decorators; passivation | 26–50 | 1.2.0 |
| F6 | Cross-feature TCK failures, conformance evidence (CDI 4.1 Full TCK and signature test) | 10–20 | 1.2.0 |
| Web/EE | Sessions, conversations, EL and transactions integration, over Foy and Mansart | 15–35 | 1.2.0 |

Total: 65–120 engineer-days for Full, 15–35 for the web and EE integration; re-estimated after
F0/F1, as the study asks. Keeping the engine independent of the CDI API version leaves the
door open to CDI 5 later, with the Jakarta EE 12 Web Profile.

### 1.0.0 — production readiness, in detail

- **Robustness.** Fuzzing (Jazzer) of the parsers exposed to the network: HTTP/1.1, HTTP/2
  frames and HPACK, TLS record handling, JSON (Champollion), multipart and `web.xml` (Foy),
  JDQL (Mansart). Soak tests of 24 hours under load; chaos tests: slow and stalled clients,
  dropped connections, resets mid-stream, exhausted pools. Today: two slowloris tests in
  Chappe, nothing else.
- **Performance.** End-to-end benchmarks of the assembled runtime (cold start, throughput
  across extensions, memory footprint), compared with other runtimes, recorded in
  `BENCH.md`; a CI job that fails on a regression beyond an agreed threshold. Today: per-brick
  benchmarks only (`BENCH.md` of chappe, champollion, vauban, cassini, cervantes…).
- **Security.** A threat model of the runtime and of each network-facing brick; an internal
  audit against it; an external audit, scoped and time-boxed; every finding fixed or
  documented. Today: signed releases and signed commits, no threat model, no scanning, no SBOM.
  The external audit is ordered in Q4 2026 for an execution in Q1 2027: a vendor books four to
  eight weeks ahead. Its results and fixes ship in 1.0.0.
- **Code quality and static analysis.** One central tool, settled on 2026-10-20 (#269, #284):
  **SonarQube Community Build** — the free, self-hosted edition of SonarQube, from SonarSource
  (Switzerland) — extended with the open-source
  [community branch plugin](https://github.com/mc1arke/sonarqube-community-branch-plugin), which
  adds branch and pull-request analysis to the Community Build. Every pull request of every
  repository is analysed and gets a quality gate on its own new code; the server imports JaCoCo,
  keeps the trends per brick, and is what the developers see in the IDE (SonarQube for IDE in
  connected mode). The plugin is not supported by SonarSource and its version must match the
  SonarQube version, so the pair is pinned and upgraded together, a few months behind the newest
  Community Build; should the plugin stop, the fallback is the plain Community Build analysing
  `main` only, with the same pipeline. Beside it, only what the Community Build cannot do, run
  from Maven: Error Prone and NullAway in `javac` (null-safety at compile time); OpenGrep (the LGPL
  fork of Semgrep, which keeps cross-file taint analysis, a paid feature in SonarQube) with the
  OWASP and Java rules; OSV-Scanner on every dependency, build tooling included, and again, on a
  schedule, on the CycloneDX SBOMs of the released versions (#270), so a CVE published after a
  release is caught between releases; Trivy on the `jlink` and Docker images; ArchUnit for the
  ecosystem's own rules (no reflection in the cores, zero dependency, layering, virtual threads;
  #289); japicmp for the API compatibility the 1.x line promises (#288). Nightly: Infer/RacerD for
  data races on Chappe and Foy, OWASP ZAP against the reference deployment. **Nothing blocks a
  pull request yet**: every analysis starts in report mode (decided 2026-10-20) while the rules
  are calibrated and the existing findings cleaned up (#285, bugs and security hotspots first, in
  the order the threat model gives: the network-facing bricks before the others); switching a
  repository's gate to blocking is a separate decision, reviewed at the 0.4.0 release, with the
  intention of blocking everywhere before 1.0.0-RC1. Set aside, to keep the tool count down:
  SpotBugs, PMD and Qodana (covered by SonarQube's Java rules), Dependency-Track (OSV-Scanner over
  the SBOMs does the job without a server), the paid Developer Edition, CodeQL (CI use allowed
  only for open source hosted on GitHub.com), Qlty (non-open-source licence), Codacy and
  DeepSource (US SaaS).
- **Dashboards.** SonarQube for the code; for the trends of benchmarks, TCK runs and soak tests,
  Grafana over a PostgreSQL the CI feeds, which is also the baseline store the regression gate
  needs. Both on the project's own host, behind the OpenID Connect provider of Codefloe, which is
  active (checked on 2026-10-20: issuer `https://codefloe.com`, authorization-code flow):
  natively for Grafana, through an OpenID Connect proxy for SonarQube, whose Community Build
  signs in with SAML, not OpenID Connect. A dashboard built with Vidocq itself is a showcase for
  after 1.0, not a prerequisite.

## Status of MicroProfile 7.2 specs (and Metrics, a standalone spec)

| MicroProfile spec | Implementation | Vidocq extension | Status |
|---|---|---|---|
| **Rest Client 4.0** | [cyrano](../cyrano) | `vidocq-runtime-cyrano-rest-client-extension` | ✅ delivered |
| **Telemetry 2.2** | [humboldt](../humboldt) | `vidocq-runtime-humboldt-telemetry-extension` | ✅ delivered (TCK green: 85, against TCK 2.2-RC3) |
| **Health 4.0** | [knock](../knock) | `vidocq-runtime-knock-health-extension` | ✅ delivered |
| **Config 3.1** | [ravel](../ravel) | `vidocq-runtime-ravel-config-extension` | ✅ delivered |
| **Fault Tolerance 4.1** | [heisenberg](../heisenberg) | `vidocq-runtime-heisenberg-fault-tolerance-extension` | ✅ delivered |
| **JWT Auth 2.2** | [cervantes](../cervantes) | `vidocq-runtime-cervantes-jwt-extension` | ✅ delivered |
| **OpenAPI 4.2** | [grimm](../grimm) | `vidocq-runtime-grimm-openapi-extension` | ✅ delivered (TCK green: 364, against TCK 4.2-RC5) |
| **Metrics 5.1** (standalone spec, not part of the MicroProfile 7.2 platform) | [dirac](../dirac) | `vidocq-runtime-dirac-metrics-extension` | ✅ delivered (TCK green: 127) |

## Extensions outside the MicroProfile specs

Additional infrastructure bricks delivered as Vidocq extensions, useful to the ecosystem:

| Brick | Vidocq extension | Status |
|---|---|---|
| HTTP/1.1+H2+WS+gRPC server | [chappe](../chappe) | `vidocq-runtime-chappe-webserver-extension` ✅ |
| JAX-RS 4.0 (transport via Chappe) | [cassini](../cassini) | `vidocq-runtime-cassini-rest-extension` ✅ |
| Jakarta Expression Language 6.0 | [peano](../peano) | `vidocq-runtime-peano-el-extension` ✅ |
| Jakarta Data 1.0 (repositories) | [mansart-jakarta-data](../mansart) | `vidocq-runtime-mansart-data-extension` ✅ |
| Virtual-thread-native JDBC pool | [mansart-pool](../mansart) | `vidocq-runtime-mansart-pool-extension` ✅ |
| JTA transactions | [mansart-transactions](../mansart) | `vidocq-runtime-mansart-transactions-extension` ✅ |
| Jakarta Persistence 3.2 (JPA) | Antoine's project, split out of Mansart | ⏳ planned for 0.4.0 if its first release is out in time, else 1.0.0; the TCK runs |

## Jakarta EE Core Profile 11 certification

Beyond MicroProfile 7.2, Vidocq targets **Jakarta EE Core Profile 11**
certification for the assembled runtime. Full process, TCK binaries, and
detailed results: [`CERTIFICATION.md`](CERTIFICATION.md); running instructions:
[`TCK.md`](vidocq-runtime-integration-tests/TCK.md).

| Item | Status |
|---|---|
| Constituent spec TCKs (Annotations, CDI 4.1 Lite + Interceptors 2.2, DI 2.0, JSON-P 2.1, JSON-B 3.0, REST 4.0) | ✅ all seven green |
| Core Profile composite TCK (`vidocq-runtime-tck-coreprofile`) | 10/13 — every test reachable on JDK 25 passes |
| TCK challenge (JDK 25 `Utils.getDescriptor()` incompatibility) | ✅ filed & **accepted** — [`jakartaee/platform-tck#2730`](https://github.com/jakartaee/platform-tck/issues/2730); open pending an official corrected TCK release (fix already exists upstream) |
| Signature tests (Annotations, JSON-P, JSON-B, REST, CDI) | ✅ all 5 satisfied — Annotations/JSON-P/JSON-B/CDI verified green by direct run (CDI via the new `cdi-sigtest` profile in `vauban-tck-runner`); RESTful WS excluded via a pre-existing documented challenge (`CERTIFICATION.md` §5) |
| EFTL-signed binaries re-run | ✅ **certifying run done (2026-08-31)** against the released **Vidocq 0.3.0** Maven Central artifacts: all 7 EFTL zips SHA-256 **and GPG** verified, zero unexpected failures, results byte-for-byte identical to the 2026-08-29 dress rehearsal. Details: `CERTIFICATION.md` §7/Status |
| Public results summary page | ✅ live at [vidocq.dev/certification](https://vidocq.dev/certification/) (`Vidocq/pages` PR #11, merged 2026-08-31) |
| Certification issue on `jakartaee/platform` | ✅ **filed** — [`jakartaee/platform#1351`](https://github.com/jakartaee/platform/issues/1351) (2026-08-31); labeled `accepted` and closed on 2026-09-09 |

## Jakarta EE Web Profile 11

Planned across 1.1.0, 1.2.0 and 1.3.0, certification for 1.4.0 (see the release plan above).
Gap analysis, per-spec status, and tracking issues: [`WEB-PROFILE.md`](WEB-PROFILE.md)
(umbrella issue [vidocq#67](https://codefloe.com/Vidocq/vidocq/issues/67)). The pivotal
gap is CDI 4.1 **Full** (Vauban implements Lite only), whose Portable Extensions
sub-problem has a dedicated design doc: [`vauban/CDI-FULL.md`](https://codefloe.com/Vidocq/vauban/CDI-FULL.md)
([vauban#38](https://codefloe.com/Vidocq/vauban/issues/38), [vauban#39](https://codefloe.com/Vidocq/vauban/issues/39)).

## Reactor modules

```
vidocq-runtime-spi/                       Public extension SPI (Extension, Phase)
vidocq-runtime-core/                      Lifecycle orchestrator, ServiceLoader, scan
vidocq-runtime-extensions/                Delivered extensions, grouped by domain (see table above)
vidocq-runtime-maven-plugin/              Maven plugin: fat-jar + jlink packaging
vidocq-runtime-examples/                  Examples (cassini-rest, mansart-h2, external-rest-lib)
vidocq-runtime-integration-tests/         Arquillian ITs + cross-extension (humboldt+cassini)
```

## Backlog

### Short term (missing MP extensions)

- [x] **`vidocq-runtime-config-extension`** — delivered as `vidocq-runtime-ravel-config-extension` (Ravel, Config 3.1).
      Original goal: SmallRye Config wrapper / simple in-house config.
      CDI prerequisite for `@ConfigProperty`; must integrate with the Vauban init lifecycle.
- [x] **`vidocq-runtime-fault-tolerance-extension`** (delivered as `vidocq-runtime-heisenberg-fault-tolerance-extension`) — `@Retry`, `@Timeout`, `@Bulkhead`,
      `@CircuitBreaker`. Virtual-thread compatibility: avoid ThreadLocal pinning,
      prefer `ScopedValue`. SmallRye wrapper possible.
- [x] **`vidocq-runtime-jwt-extension`** (delivered as `vidocq-runtime-cervantes-jwt-extension`) — Bearer JWT gating auth. MP JWT Auth 2.2.
- [x] **`vidocq-runtime-openapi-extension`** (delivered as `vidocq-runtime-grimm-openapi-extension`) — OpenAPI 3.1 generation from Cassini
      JAX-RS resources. MP OpenAPI 4.2.
- [x] **`vidocq-runtime-metrics-extension`** — delivered separately as `vidocq-runtime-dirac-metrics-extension` (Dirac, Metrics 5.1).

### Medium term (integration & packaging)

- [ ] Maven plugin `vidocq-runtime-maven-plugin`: full `vidocq:jlink` command
      (see `JLINK.md`), demonstrated on `vidocq-runtime-examples`.
- [ ] Standalone `vidocq` CLI (similar to `chappe serve`) that scans extensions on the
      classpath and starts the runtime without the Maven plugin.
- [ ] Exhaustive Antora user documentation (`docs/`): getting-started guide,
      extension reference, cross-extension integration recipes.

### Long term (quality)

- [ ] MicroProfile 7.2 TCK per implemented spec. See `TCK.md` for tracking
      (each extension has its TCK runner: the `vidocq-runtime-tck-*` runners are in this
      reactor behind the `tck` profile; the per-brick runners are in their own repositories, mostly in-reactor behind
      the same `tck` profile; `grimm-tck`, `mansart-data-tck`, `mansart-transactions-tck` and
      `champollion-protobuf-tck` stay outside their brick's reactor).
- [x] Jakarta EE Core Profile 11 certification — see the dedicated section above
      and `CERTIFICATION.md`. All deliverables complete: certifying EFTL
      re-run, public results page, and `jakartaee/platform#1351` filed
      (2026-08-31), accepted and closed on 2026-09-09.
- [ ] End-to-end JMH benchmarks (cold start, cross-extension throughput, memory
      footprint vs Quarkus/Helidon): planned for 1.0.0, see the release plan.
      No perf number in docs without a `BENCH.md` entry.
- [ ] GraalVM native-image AOT: prerequisite = no runtime reflection in any
      extension. Incremental validation (Chappe + Cassini first).
- [ ] **Cross-brick codegen rationalisation study** (tracked in #70) — the ecosystem carries 9
      independent annotation processors (vauban, cassini, cyrano, grimm, dirac,
      champollion-jsonb, champollion-protobuf, mansart-data, vidocq-datasources)
      and 7 Maven plugins, each re-implementing the same build-time mechanics:
      annotated-element scanning and `TypeMirror` validation, Java source emission
      via `StringBuilder` + `Filer.createSourceFile` (no shared renderer),
      hand-written `META-INF/services` files (~10 modules), class/jar scanning on
      the Maven side (`chappe IndexMojo`, `cassini GenerateAdaptersMojo`,
      `mansart RepositoryClassScanner`, `vidocq ServiceUsesScanner`) alongside
      `vauban-indexer` (consumed only by vidocq and the examples), and per-module
      Class-File API helpers (13 modules). ~80 runtime `getAnnotation()` call sites
      remain in the `*-core` modules. Driver: code quality and rationalisation —
      a shared transverse library is preferred over duplicated functions (internal
      dependencies already exist, e.g. `vidocq-parent` → `vauban` → MP bricks).
      Deliverables, in order: (1) inventory of duplicated LOC and divergence risks
      per mechanic; (2) the study's outcome recorded as an ADR in `vidocq-docs`
      (`docs/adr/0005-shared-codegen-library.md`) deciding on a build-time-only
      common library (candidate scope: element scanning, source rendering,
      service-file writer, Class-File API helpers, generalised `vauban-indexer`;
      must settle host repository, coordinates and dependency direction);
      (3) migration plan brick by brick. No refactor before the ADR is accepted.
      Synergy with the GraalVM item above (removing runtime reflection).

## Bugs & incidents

See `CHAPPE-BUGS.md`, `VAUBAN-BUGS.md` at the root for historical anomalies
reported on the underlying bricks. No `BUG.md` for the runtime itself yet —
create one if a Vidocq regression appears.

## Tracking conventions

- **This roadmap**: medium/long-term vision, kept up to date with MP spec status.
- **`tasks/todo.md`** (per subproject): active short-term TODOs, may be absent.
- **`BUG.md`** (per subproject): reproducible regressions, incident traceability.
- Performance numbers go in `BENCH.md` (per subproject), never in README/commit
  messages without a matching entry.
