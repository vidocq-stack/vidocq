# Vidocq Runtime — Roadmap

Modular Java SE MicroProfile 7.1 runtime built on Vauban (CDI 4.1 Lite).
Each MicroProfile spec is delivered as an independent extension loaded via
ServiceLoader (Quarkus-inspired model). The runtime itself remains minimal:
SPI, lifecycle, packaging (fat jar / jlink via `vidocq-runtime-maven-plugin`).

> Product view: see [`README.md`](README.md). User documentation: `docs/`.

## Status of MicroProfile 7.1 specs

| MicroProfile spec | Implementation | Vidocq extension | Status |
|---|---|---|---|
| **Rest Client 4.0** | [cyrano](../cyrano) | `vidocq-runtime-cyrano-rest-client-extension` | ✅ delivered |
| **Telemetry 2.1** | [humboldt](../humboldt) | `vidocq-runtime-humboldt-telemetry-extension` | ✅ delivered (M7 TCK in progress) |
| **Health 4.0** | [knock](../knock) | `vidocq-runtime-knock-health-extension` | ✅ delivered |
| **Config 3.1** | _smallrye config_ | — | ❌ to package as an extension |
| **Fault Tolerance 4.1** | _smallrye fault tolerance_ | — | ❌ to package as an extension |
| **JWT Auth 2.1** | — | — | ❌ TODO (heisenberg?) |
| **OpenAPI 4.0** | — | — | ❌ TODO |
| **Metrics 5.1** | — | — | ❌ TODO (often merged with Telemetry) |

## Extensions outside the MicroProfile specs

Additional infrastructure bricks delivered as Vidocq extensions, useful to the ecosystem:

| Brick | Vidocq extension | Status |
|---|---|---|
| HTTP/1.1+H2+WS+gRPC server | [chappe](../chappe) | `vidocq-runtime-chappe-webserver-extension` ✅ |
| JAX-RS 4.0 (transport via Chappe) | [cassini](../cassini) | `vidocq-runtime-cassini-rest-extension` ✅ |
| Jakarta Data 1.0 (repositories) | [mansart-jakarta-data](../mansart) | `vidocq-runtime-mansart-data-extension` ✅ |
| Virtual-thread-native JDBC pool | [mansart-pool](../mansart) | `vidocq-runtime-mansart-pool-extension` ✅ |
| JTA transactions | [mansart-transactions](../mansart) | `vidocq-runtime-mansart-transactions-extension` ✅ |
| Jakarta Persistence 3.2 (JPA) | [mansart-persistence](../mansart) | ❌ M7 Mansart pending |

## Jakarta EE Core Profile 11 certification

Beyond MicroProfile 7.1, Vidocq targets **Jakarta EE Core Profile 11**
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
| Certification issue on `jakartaee/platform` | ✅ **filed** — [`jakartaee/platform#1351`](https://github.com/jakartaee/platform/issues/1351) (2026-08-31); awaiting lazy-consensus approval (14 days) or majority vote |

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

- [ ] **`vidocq-runtime-config-extension`** — SmallRye Config wrapper / simple in-house config.
      CDI prerequisite for `@ConfigProperty`; must integrate with the Vauban init lifecycle.
- [ ] **`vidocq-runtime-fault-tolerance-extension`** — `@Retry`, `@Timeout`, `@Bulkhead`,
      `@CircuitBreaker`. Virtual-thread compatibility: avoid ThreadLocal pinning,
      prefer `ScopedValue`. SmallRye wrapper possible.
- [ ] **`vidocq-runtime-jwt-extension`** — Bearer JWT gating auth. Implementation
      likely in a new repo (placeholder `heisenberg`?). MP JWT Auth 2.1.
- [ ] **`vidocq-runtime-openapi-extension`** — OpenAPI 3.1 generation from Cassini
      JAX-RS resources. MP OpenAPI 4.0.
- [ ] **`vidocq-runtime-metrics-extension`** — either separate or merged into humboldt.
      Decision: to be settled based on the evolution of MP Metrics 5.1 vs Telemetry.

### Medium term (integration & packaging)

- [ ] Maven plugin `vidocq-runtime-maven-plugin`: full `vidocq:jlink` command
      (see `JLINK.md`), demonstrated on `vidocq-runtime-examples`.
- [ ] Standalone `vidocq` CLI (similar to `chappe serve`) that scans extensions on the
      classpath and starts the runtime without the Maven plugin.
- [ ] Exhaustive Antora user documentation (`docs/`): getting-started guide,
      extension reference, cross-extension integration recipes.

### Long term (quality)

- [ ] MicroProfile 7.1 TCK per implemented spec. See `TCK.md` for tracking
      (each extension has its out-of-reactor TCK runner, model `champollion-tck`).
- [x] Jakarta EE Core Profile 11 certification — see the dedicated section above
      and `CERTIFICATION.md`. All deliverables complete: certifying EFTL
      re-run, public results page, and `jakartaee/platform#1351` filed
      (2026-08-31). Awaiting specification project approval.
- [ ] End-to-end JMH benchmarks (cold start, cross-extension throughput, memory
      footprint vs Quarkus/Helidon). No perf number in docs without a `BENCH.md` entry.
- [ ] GraalVM native-image AOT: prerequisite = no runtime reflection in any
      extension. Incremental validation (Chappe + Cassini first).

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
