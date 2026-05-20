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

### M6b — humboldt-rest (filters JAX-RS) _(terminé 2026-05-21)_

- [x] `HumboldtServerRequestFilter` `@Provider` : `TextMapGetter<ContainerRequestContext>` qui adapte `getHeaders()`, extract via composite `W3CPropagators.textMap()`, start SERVER span avec parent extrait, attrs OTel `http.request.method` / `url.path` (normalisé '/' initial — convention OTel) / `url.scheme`. Span name = `{method} {path}`. Span + Scope stockés via `ContainerRequestContext.setProperty(SPAN_PROPERTY / SCOPE_PROPERTY)`
- [x] `HumboldtServerResponseFilter` `@Provider` : récupère le span, set `http.response.status_code` long, status ERROR si ≥500 (4xx ignoré — OTel HTTP semantic), close Scope puis `span.end()` en finally, cleanup propriétés
- [x] Tests : 6 sans container JAX-RS via `java.lang.reflect.Proxy` (route ~6 méthodes utilisées, defaults pour les ~40 autres méthodes abstraites JAX-RS 4.0 — robuste face aux changements d'API entre versions). Couvre : span method+path+scheme, extraction traceparent W3C, status 500 → ERROR, status 404 → UNSET, response filter idempotent sans property, cleanup property
- [ ] **Tests E2E via cassini standalone** (chappe transport) reportés en M7 — nécessitent cassini-snapshot dans le M2 CI

### M6c — humboldt-runtime autoconfig _(terminé 2026-05-21)_

- [x] `EnvConfig` : lecture env vars (`SCREAMING_SNAKE_CASE`) avec fallback system properties (`lower.dot.case`). Helpers `getBoolean/getLong/getDouble` avec defaults. Constructeur `EnvConfig.of(envMap, propMap)` pour tests sans toucher au process global
- [x] `HumboldtAutoConfigure.configure()` : assemble pipeline complet trace+metric+log depuis env vars (`OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` parsing comma-separated key=value, `OTEL_EXPORTER_OTLP_ENDPOINT` avec overrides per-signal `_TRACES/_METRICS/_LOGS_ENDPOINT`, `OTEL_TRACES/METRICS/LOGS_EXPORTER` ∈ `otlp|none|in-memory|logging`, `OTEL_TRACES_SAMPLER` ∈ `always_on/off|traceidratio|parentbased_*`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_EXPORTER_OTLP_HEADERS`)
- [x] `AutoConfiguredHumboldt implements OpenTelemetry, AutoCloseable` : expose getTracerProvider/MeterProvider/LogsBridge/Propagators (interface OTel standard, peut être passé à `GlobalOpenTelemetry.set()` ou aux interceptors), inMemorySpanExporter()/MetricExporter()/LogRecordExporter() pour tests, flush() + shutdown() avec CompletableResultCode agrégé
- [x] Pipeline auto : `in-memory` exporter → Simple processor + PeriodicMetricReader 60min ; `otlp` exporter → Batch processor + PeriodicMetricReader 60s ; `none` → aucun processor enregistré
- [x] Propagators W3C composite (TraceContext + Baggage) installés par défaut
- [x] Tests : 15 (8 EnvConfigTest + 7 HumboldtAutoConfigureTest) — service.name default+override, RESOURCE_ATTRIBUTES parsing 3 paires, pipeline E2E trace+metric+log via 1 seul `configure()`, sampler always_off, traceidratio ratio descriptio, exporter=none désactive, W3C propagators traceparent+baggage exposés
- [ ] **Différé en M7** : `OTEL_EXPORTER_OTLP_TIMEOUT`, `OTEL_EXPORTER_OTLP_PROTOCOL` (json vs protobuf), `MP_TELEMETRY_SDK_DISABLED`, `MP_TELEMETRY_PROPAGATORS`, intégration Ravel pour MP Config

### M6d — Extension MPS + validation Vauban runtime _(structure livrée 2026-05-21)_

- [x] Extension `vidocq-mps-humboldt-extension` créée dans `vidocq-mps` (commit `0274a62`) :
  * Module Maven avec pom hérité de `vidocq-mps-core-extensions`, dep humboldt-runtime/cdi/rest + vauban-core + vidocq-mps-spi
  * `HumboldtExtension implements VidocqExtension` priorité 100 — configure() lit env vars OTel via VidocqConfiguration bridge, beforeStart() = AutoConfiguredHumboldt.configure + GlobalOpenTelemetry.set, onStop() flush + shutdown 5s
  * 13 clés OTel/MP_TELEMETRY_* bridgées (SCREAMING_SNAKE + lower.dot.case)
  * Désactivation via `MP_TELEMETRY_SDK_DISABLED=true`
  * ServiceLoader : `META-INF/services/io.vidocq.mpserver.spi.VidocqExtension` + `provides` JPMS
  * README.md complet avec table des env vars + instrumentation auto activée (`@WithSpan` BCE, filters JAX-RS)
  * Pom parent vidocq-mps : property `humboldt.version=0.1.0-SNAPSHOT` + 4 DM entries (3 humboldt + 1 extension)
- [ ] **M6d.4** — Verify build : bloqué par un bug Maven 4 path pre-existant dans le reactor `vidocq-mps` (paths `vidocq/vidocq/...` au lieu de `vidocq-mps/...`). Confirmé indépendant de l'ajout via `git stash`. Structure de l'extension correcte (calque exact `vidocq-mps-rest-cassini-extension`), compilera dès que le bug Maven sera résolu.
- [ ] **M6d.5 Validation Vauban runtime** : test E2E via `vidocq-mps-integration-tests` qui démarre une app, fait un appel HTTP, vérifie qu'un span SERVER apparaît dans `InMemorySpanExporter`. Confirme que Vauban applique bien la BCE humboldt-cdi sur les beans `@WithSpan` (risk PLAN §15.1).

## M7 — TCK officiel MicroProfile Telemetry 2.1

### M7a — Audit + scaffold _(terminé 2026-05-21)_

- [x] **Audit M7.1** : TCK split en 3 artifacts (tracing/metrics/logs) tous publics sur Maven Central — aucun install manuel requis. Stack **TestNG + Arquillian + ShrinkWrap**. Annotation officielle = `io.opentelemetry.instrumentation.annotations.WithSpan` (différente de notre `io.vidocq.humboldt.cdi.WithSpan` — aliasage en M7b)
- [x] **Scaffold M7.2** : `humboldt-tck/` hors-reactor (pom Model 4.0.0 standalone, sans `<parent>`) avec import 3 TCK + arquillian-testng-container + shrinkwrap-resolver + humboldt-runtime/cdi/rest + opentelemetry-sdk (utilisé seulement par le TCK comme fixture, ne pollue pas l'applicatif Humboldt). Profiles `tck-smoke` (défaut) et `tck-official`
- [x] `arquillian.xml` placeholder (M7b ajoutera le container adapter), `tck-suite.xml` TestNG agrégeant les 3 TCK packages
- [x] `HumboldtTckSmokeTest` : 4 tests (classpath humboldt-runtime, GlobalOpenTelemetry settable + span créé via AutoConfiguredHumboldt, TCK tracing class présente, OTel @WithSpan officiel présent) — **4/4 PASS**
- [x] `run-official-tck-telemetry-2.1.sh` à la racine : install reactor → smoke (default) ou `all` (M7c+)
- [x] TCK.md mis à jour (coordonnées confirmées, roadmap M7b/M7c)

### M7b — Adapter Arquillian Humboldt _(à venir)_

- [ ] Composer Vauban (CDI Lite) + Cassini (JAX-RS via Chappe) + humboldt-runtime en container Arquillian "embedded" léger
- [ ] **Aliaser `io.opentelemetry.instrumentation.annotations.WithSpan`** dans humboldt-cdi (en plus de notre `WithSpan`)
- [ ] Implémenter `InMemorySpanExporterProvider` SPI attendu par le TCK
- [ ] 1er test smoke TCK officiel (`OpenTelemetryBeanTest`) qui démarre

### M7c — Run complet + challenges _(à venir)_

- [ ] `./run-official-tck-telemetry-2.1.sh all` premier run
- [ ] Identifier tests passed / failed / skipped
- [ ] Documenter challenges dans `TCK.md`
- [ ] **Gate qualité** : ≥95 % de tests applicables passent

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
