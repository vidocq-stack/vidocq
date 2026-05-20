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

## M3 — Propagator W3C + Exporter OTLP HTTP-JSON _(terminé 2026-05-20)_

- [x] `humboldt-propagator-w3c` : façade `W3CPropagators.get()` composant W3CTraceContextPropagator + W3CBaggagePropagator de l'API OTel publique (aucune réimplémentation — les deux sont concrètes côté API). 5 tests : fields composite, inject traceparent W3C format, extract restore SpanContext remote, baggage roundtrip, singleton stable
- [x] `humboldt-exporter-otlp-http` MVP : encoder OTLP/JSON manuel (`OtlpJsonEncoder` via StringBuilder, schéma resourceSpans/scopeSpans/spans/attributes, AnyValue stringValue/boolValue/intValue/doubleValue/arrayValue, JSON escape minimal), transport `java.net.http.HttpClient` avec executor virtual threads, builder (endpoint, headers, requestTimeout, connectTimeout, maxRetries), retry exponentiel borné (100ms × 2^attempt, plafond 5s) sur 5xx et erreurs réseau
- [x] Tests E2E avec fake server JDK `com.sun.net.httpserver.HttpServer` in-process : POST OTLP/JSON valide, retry 503→503→200, header Authorization custom, backoff capped. 4 EncoderTest unitaires (single span, parentSpanId, events+links+status, escape JSON, empty collection)
- [ ] **M3b** (différé) : OTLP/HTTP-protobuf, transport via chappe-client, tests E2E Jaeger via testcontainers — voir [`PLAN.md`](PLAN.md) §3.4
- [ ] **Gate TCK tracing** : reporté en M7 (runner officiel hors-reactor)

## M4 — SDK Metric (MVP synchrone) _(terminé 2026-05-20)_

- [x] `humboldt-sdk-metric` MVP : `LongCounter` (monotonic, ignore négatif), `DoubleHistogram` (refuse négatif/NaN), `SdkMeterProvider` builder-based (Resource + N MetricReader), `SdkMeter` cache par scope. `upDownCounterBuilder`/`gaugeBuilder` lancent UOE explicite.
- [x] Aggregators CUMULATIVE : `SumAggregator` (ConcurrentHashMap<Attributes, LongAdder>), `ExplicitBucketHistogramAggregator` (15 bornes par défaut OTel : 0/5/10/25/50/75/100/250/500/750/1000/2500/5000/7500/10000)
- [x] `PeriodicMetricReader` sur virtual thread (scheduleDelay défaut 60s, flush() trigger immédiat, drain final on shutdown)
- [x] `OtlpHttpMetricExporter` (POST `/v1/metrics`, encoder JSON manuel, retry exponentiel borné, executor virtual threads) + `OtlpJsonMetricEncoder` (sum + histogram + gauge, temporality int CUMULATIVE=2, isMonotonic, count string-encoded)
- [x] `InMemoryMetricExporter` pour tests (ne purge pas sur shutdown)
- [x] Refactor : `CompletableResultCode` et `InstrumentationScope` déplacés vers humboldt-sdk-common (briques partagées trace/metric/log, évite couplage cross-SDK)
- [x] Tests : 17 nouveaux (6 SdkMeterProvider — counter/négatif/histogram/Resource/cache/UOE, 1 OtlpHttpMetric E2E end-to-end avec fake server) — **total 74/74 PASS**
- [ ] **M4b** (différé) : Observable instruments (Gauge/Counter/UpDownCounter), variantes Long/Double manquantes (DoubleCounter, LongHistogram, LongUpDownCounter), ExponentialHistogramAggregator, ViewRegistry/advice, DELTA temporality
- [ ] **Gate TCK metrics** : reporté en M7 (runner officiel hors-reactor)

## M5 — SDK Log _(terminé 2026-05-20)_

- [x] `humboldt-sdk-log` MVP : SdkLoggerProvider (builder, cache scope, MeterBuilder-like LoggerBuilder anonymous), SdkLogger, SdkLogRecordBuilder (timestamp/observedTimestamp TimeUnit & Instant, severity Number + severityText, body, context capture du Span courant), LogRecordData record immutable
- [x] Processors : SimpleLogRecordProcessor (synchrone), BatchLogRecordProcessor (worker virtual thread, scheduleDelay défaut 1s, threshold/flush/shutdown drain — pattern aligné BatchSpanProcessor)
- [x] InMemoryLogRecordExporter (tests, ne purge pas sur shutdown)
- [x] OtlpHttpLogExporter (POST /v1/logs JDK HttpClient + executor VT, retry mutualisé via OtlpHttpSpanExporter.computeBackoffMillis) + OtlpJsonLogEncoder (resourceLogs/scopeLogs/logRecords avec severityNumber/severityText/body.stringValue/attributes/traceId/spanId/flags)
- [x] Tests : 7 SdkLoggerProvider (emit/captures span context/severity indépendante/cache scope/batch drain/sync immédiat/timestamp TimeUnit) + 1 OtlpHttpLog E2E = **8 nouveaux** → total **82/82 PASS**
- [ ] **M5b** (différé) : bridges `java.util.logging` (Handler) + SLF4J (Appender) → OTel — pour capturer les logs existants sans modifier les appels code
- [ ] **Gate TCK logs** : reporté en M7 (runner officiel hors-reactor)

## M6 — CDI + JAX-RS + Runtime (découpé en M6a/b/c)

### M6a — humboldt-cdi (interceptor `@WithSpan`) _(terminé 2026-05-21)_

- [x] Annotation `@WithSpan(value, kind)` avec `@InterceptorBinding` Jakarta standard — applicable sur méthode OU type (héritée)
- [x] `WithSpanInterceptor` `@AroundInvoke` : résout l'annotation (méthode > classe), crée le span via `Tracer.spanBuilder(name).setSpanKind(kind).startSpan()`, attache au Context (`try-with-resources Scope`), `recordException()` + status ERROR sur Throwable, `span.end()` en finally. Priorité `Interceptor.Priority.APPLICATION + 1`.
- [x] Tracer résolu via `GlobalOpenTelemetry.get()` (hook `openTelemetry()` protected — surchargeable pour tests sans init globale, ou futur `@Inject` Tracer en M6b)
- [x] 6 tests sans container CDI (InvocationContext synthétique) : default name = `Class.method`, explicit value+kind SERVER, exception → ERROR status + event "exception" avec stack, span current pendant méthode, parent/child traceId share avec span outer, annotation classe utilisée si méthode sans annotation
- [x] **Décision** : intégration Vauban runtime à valider en M6b. M6a utilise jakarta.cdi-api + jakarta.interceptor-api standard, donc compatible avec n'importe quel container Lite ou Full.

### M6b — humboldt-rest (filter JAX-RS via Cassini) _(à venir)_

- [ ] `ContainerRequestFilter` extract W3C `traceparent` → start SERVER span (nom = HTTP method + route ou URI template)
- [ ] `ContainerResponseFilter` → set `http.response.status_code`, `span.end()`
- [ ] Conventions OTel : attrs `http.request.method`, `url.path`, `network.protocol.version`
- [ ] Tests via cassini standalone (chappe transport) + assertions sur spans capturés

### M6c — humboldt-runtime + extension MPS _(à venir)_

- [ ] `humboldt-runtime` autoconfig : lit env vars `OTEL_*` / `MP_TELEMETRY_*` (via ravel), assemble SdkTracerProvider/MeterProvider/LoggerProvider + OTLP HTTP exporters par défaut, installe propagators W3C
- [ ] Extension `vidocq-mps-humboldt-extension` (hors-reactor humboldt, vit dans vidocq-mps)
- [ ] **Validation Vauban runtime** : confirmer CDI 4.1 Lite suffit pour `@WithSpan` (risk PLAN §15.1) — sinon escape hatch documenté

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
