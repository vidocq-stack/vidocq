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

## M1 — Common + Context (Virtual-Threads) _(en cours)_

- [x] `humboldt-sdk-common` : `Clock` (system), `IdGenerator` (Random128 — traceId 32 hex, spanId 16 hex), `Resource` (immutable, merge sémantique OTel)
- [x] `humboldt-context` : `HumboldtContextStorageProvider` + `HumboldtContextStorage` (ThreadLocal, conforme contrat OTel, log WARNING sur attach/detach désordonné)
- [x] Binding ServiceLoader : `provides` JPMS + fallback `META-INF/services`
- [x] Tests : attach/close, nested LIFO, isolation virtual thread, propagation via `Context.wrap()`
- [ ] ADR M8 (différée) : éventuelle migration vers `ScopedValue` (JEP 506) si benchmark mémoire critique sur >100K VTs

## M2 — SDK Trace _(terminé 2026-05-20)_

- [x] `humboldt-sdk-trace` : `SdkTracerProvider` (builder Resource + Sampler + IdGenerator + Clock + N processors, cache Tracer par scope name), `SdkTracer`, `SdkSpanBuilder`, `SdkSpan` (mutable jusqu'à end, synchronized, ignore mutations post-end)
- [x] Modèle données immutable : `SpanData` (record 12-field), `EventData`, `LinkData`, `StatusData`, `InstrumentationScope`
- [x] 4 Samplers : `AlwaysOn`, `AlwaysOff`, `ParentBased(root)`, `TraceIdRatioBased(ratio)` (consistance per-trace, seuil sur les 64 bits bas du traceId)
- [x] `SpanProcessor` : `SimpleSpanProcessor` (synchrone, ignore non-samplés), `BatchSpanProcessor` (queue bornée, **virtual thread worker**, batch sur threshold/scheduleDelay/flush/shutdown)
- [x] Exporters utilitaires : `InMemorySpanExporter` (tests), `LoggingSpanExporter` (System.getLogger)
- [x] `CompletableResultCode` — équivalent SDK OTel sans dep (async result avec succeed/fail/whenComplete/join)
- [x] Tests E2E : 8 samplers + 9 tracer/span (parent-child, links, kind/status, events, recordException, cache scope, Resource, noParent) + 3 processors (drop unsampled, batch threshold, drain on shutdown) = **20 tests**
- [ ] Audit TCK : reporté à M7 (runner officiel hors-reactor)

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
