# Humboldt — Roadmap M0 → M9

Plan détaillé : [`PLAN.md`](PLAN.md) (§13 jalons). Cette page est la version courte pour suivre l'avancement global.

## M0 — Squelette JPMS et tooling _(en cours)_

- [x] PLAN.md complet (1190 lignes)
- [x] `.sdkmanrc`, `mvnw`, `pom.xml` parent Model 4.1.0
- [x] `humboldt-api` minimal avec `module-info.java` + façade stub + SPI stubs
- [x] Workflows Forgejo (ci, pr, notify-slack, update-dep-graph, upstream-pr)
- [x] Doc Antora bilingue en/fr
- [x] CLAUDE.md, README FR/EN, BUG/BENCH/TCK/ROADMAP
- [ ] `git init` + premier commit
- [ ] Build de vérification `./mvnw -ntp install -DskipTests`

## M1 — Common + Context (Virtual-Threads)

- [ ] `humboldt-sdk-common` : `Resource`, `Attributes`, `Clock`, `IdGenerator` (Random128)
- [ ] `humboldt-context` : `ScopedValueContextStorageProvider` (ServiceLoader OTel), pas de pinning sur VT
- [ ] Tests : injection / propagation cross-VT via `StructuredTaskScope`

## M2 — SDK Trace

- [ ] `humboldt-sdk-trace` : `SdkTracerProvider`, `SpanProcessor` (Simple + Batch), samplers (`always_on`, `always_off`, `parentbased`, `traceidratio`)
- [ ] `BatchSpanProcessor` sur virtual thread + structured concurrency
- [ ] Tests : in-memory exporter, parent/child spans, span links
- [ ] Audit TCK : identifier les tests tracing-only que l'on peut déjà passer

## M3 — Propagator + Exporter OTLP HTTP

- [ ] `humboldt-propagator-w3c` : TraceContext + Baggage
- [ ] `humboldt-exporter-otlp-http` : protobuf marshalling, HTTP/1.1 sender via chappe-client, retry, BatchSpanProcessor
- [ ] Tests E2E vers Jaeger / OTel Collector via testcontainers
- [ ] **Gate** : TCK tracing full PASS

## M4 — SDK Metric

- [ ] `humboldt-sdk-metric` : Counter, Histogram, UpDownCounter, async Gauge
- [ ] SumAggregator, HistogramAggregator (explicit buckets), ExponentialHistogramAggregator
- [ ] ViewRegistry, PeriodicMetricReader
- [ ] OTLP metric exporter (extension de `humboldt-exporter-otlp-http`)
- [ ] **Gate** : TCK metrics full PASS

## M5 — SDK Log

- [ ] `humboldt-sdk-log` : `LogRecordProcessor`, bridges JUL + SLF4J → OTel
- [ ] OTLP log exporter
- [ ] **Gate** : TCK logs full PASS

## M6 — CDI + JAX-RS + Runtime

- [ ] `humboldt-cdi` : interceptor `@WithSpan` via Vauban (CDI 4.1 Lite à valider)
- [ ] `humboldt-rest` : filter JAX-RS via Cassini, propagation entrante/sortante
- [ ] `humboldt-runtime` : autoconfig (`HumboldtAutoConfigure`), assemblage ServiceLoader
- [ ] Extension MPS `vidocq-mps-humboldt-extension`

## M7 — TCK officiel

- [ ] `humboldt-tck` hors-reactor (POM Model 4.0.0 standalone)
- [ ] Script `run-official-tck-telemetry-2.1.sh`
- [ ] **Gate** : 100 % TCK conformité (ou challenges documentés dans TCK.md)

## M8 — Perf & ADRs

- [ ] Benchmarks JMH vs SmallRye Telemetry + OTel SDK Java reference
- [ ] ADR-001 (codegen statique), ADR-002 (protobuf hand-rolled si nécessaire)
- [ ] Tableau perf publié dans BENCH.md

## M9 — Doc + release 1.0

- [ ] Antora complète (toutes les pages de `docs/{en,fr}/modules/ROOT/pages/`)
- [ ] Guide migration depuis SmallRye Telemetry
- [ ] Release `1.0.0`

---

Pour le détail des risques, dépendances, et arbitrage technique de chaque jalon : voir [`PLAN.md`](PLAN.md).
