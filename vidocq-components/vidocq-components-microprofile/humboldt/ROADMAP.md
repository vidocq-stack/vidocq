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
- [x] **M6d.4** — Verify build reactor vidocq-mps complet : SUCCESS, 19 modules ✅.
- [x] **M6d.5 Validation Vauban runtime** _(terminé 2026-05-21)_ — test E2E via `vidocq-mps-it-humboldt-cassini` (nouveau module dans `vidocq-mps/vidocq-mps-integration-tests`, calque `it-rest-cassini`) : **4/4 tests PASS** sur le reactor vidocq-mps complet.
  - ✅ **BCE @WithSpan** : Vauban CDI Lite exécute bien la `BuildCompatibleExtension HumboldtBuildCompatibleExtension`, l'interceptor s'active sur les beans `@WithSpan` OTel. **Risk PLAN.md §15.1 résolu**.
  - ✅ **Filter SERVER span** : `humboldt-rest` capture les requêtes HTTP avec attrs OTel HTTP semantic (`url.path`, `http.response.status_code`, `kind=SERVER`).
  - ✅ **Propagation W3C entrante** : header `traceparent` → span SERVER hérite `traceId` + `parentSpanId`.
  - ✅ **Status ERROR + exception** : interceptor `@WithSpan` set `status=ERROR` + message exception sur Throwable.
  - 2 ajustements requis pour faire passer : `@ApplicationScoped` sur les filters humboldt-rest (Cassini scanne via CDI bean discovery — sans scope les `@Provider` ne sont pas découverts), publication `HumboldtHolder.INSTANCE` static dans l'extension MPS pour accès cross-ClassLoader depuis le Deployment Arquillian isolé.
  - 1 issue résolue en M6d.6 (cf. ci-dessous).
- [x] **M6d.6** _(terminé 2026-05-21)_ — Fix span SERVER manquant sur exception remontée. Diagnostic : `Invoker.java:365` de Cassini fait `return fromJaxRs(mapped.get(), ...)` sans appeler les response filters, violant spec JAX-RS §10.2.7. Workaround dans humboldt-rest : nouveau `HumboldtSpanFinalizer @Provider implements ExceptionMapper<Throwable>` (priorité USER+1000, le plus générique → ne s'exécute que si AUCUN user mapper ne matche) qui termine le span lui-même (recordException + ERROR + span.end()). Test boom strict restauré : **4/4 tests PASS** incluant l'assertion sur span SERVER `/trace/boom` + httpStatus=500 + status=ERROR. Quand le bug Cassini sera corrigé upstream, le workaround devient redondant mais reste inoffensif (le span sera déjà terminé par le response filter, removeProperty rend l'opération idempotente).
- [x] **M6d.7** _(terminé 2026-05-21, refactoré 2026-05-21 en M6d.7-bis)_ — Fix : aligner Vauban+Cassini sur spec JAX-RS 4.0 §11.2.5 (`@Provider`/`@Path` discoverable comme beans CDI managed même sans scope explicite). Bug découvert en CI : la version d'humboldt-rest publiée sur le repo Vidocq snapshots (sans `@ApplicationScoped` sur les filters) cassait les 3 tests `with_span_bce_intercepts_cdi_method`, `rest_filter_creates_server_span_with_otel_http_attrs`, `w3c_traceparent_header_propagates_trace_id` parce que `VaubanBeanProvider.getResourceClasses()` itère sur le BeanManager et ne voit que les classes annotées avec un scope CDI.
  - **Première tentative (M6d.7)** dans `vauban-core/BeanDiscovery.java` (ajout `jakarta.ws.rs.ext.Provider`/`jakarta.ws.rs.Path` à `BEAN_DEFINING_ANNOTATIONS`) → **rejetée** : viole la séparation des préoccupations, Vauban core est un container CDI générique qui ne doit pas connaître JAX-RS. Commit `vauban:82edb62` **reverté** via `vauban:9986c52`.
  - **Solution finale (M6d.7-bis)** dans `cassini-cdi-vauban/CassiniScopeExtension.java` : la BCE existait déjà pour `@Path → @RequestScoped`, étendue pour `@Provider → @Dependent` (sémantique JAX-RS : providers = singletons-équivalents). **Bug latent corrigé en passant** : la BCE n'était JAMAIS découverte car le `provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` manquait dans `module-info.java` ET `META-INF/services/...` — Vauban APT charge les BCE via `ServiceLoader.load(BuildCompatibleExtension.class)` standard (cf. `VaubanProcessor.java:314`), donc sans ces déclarations la BCE était inerte. Les `@Path` d'`it-rest-cassini` fonctionnaient par accident parce qu'ils ont `@RequestScoped` explicite.
  - Cleanup associé dans humboldt-rest : retrait des `@ApplicationScoped` workaround + dep `jakarta.cdi-api` + `requires jakarta.cdi`. **4/4 tests PASS** avec filters `@Provider` seuls.
  - Commits : `cassini:cfbd32b` (BCE + provides + cleanup), `vauban:9986c52` (revert), `humboldt:6a580b0` (cleanup workaround).

## M7 — TCK officiel MicroProfile Telemetry 2.1

### M7a — Audit + scaffold _(terminé 2026-05-21)_

- [x] **Audit M7.1** : TCK split en 3 artifacts (tracing/metrics/logs) tous publics sur Maven Central — aucun install manuel requis. Stack **TestNG + Arquillian + ShrinkWrap**. Annotation officielle = `io.opentelemetry.instrumentation.annotations.WithSpan` (différente de notre `io.vidocq.humboldt.cdi.WithSpan` — aliasage en M7b)
- [x] **Scaffold M7.2** : `humboldt-tck/` hors-reactor (pom Model 4.0.0 standalone, sans `<parent>`) avec import 3 TCK + arquillian-testng-container + shrinkwrap-resolver + humboldt-runtime/cdi/rest + opentelemetry-sdk (utilisé seulement par le TCK comme fixture, ne pollue pas l'applicatif Humboldt). Profiles `tck-smoke` (défaut) et `tck-official`
- [x] `arquillian.xml` placeholder (M7b ajoutera le container adapter), `tck-suite.xml` TestNG agrégeant les 3 TCK packages
- [x] `HumboldtTckSmokeTest` : 4 tests (classpath humboldt-runtime, GlobalOpenTelemetry settable + span créé via AutoConfiguredHumboldt, TCK tracing class présente, OTel @WithSpan officiel présent) — **4/4 PASS**
- [x] `run-official-tck-telemetry-2.1.sh` à la racine : install reactor → smoke (default) ou `all` (M7c+)
- [x] TCK.md mis à jour (coordonnées confirmées, roadmap M7b/M7c)

### M7b — Adapter Arquillian Humboldt _(en cours)_

- [x] **M7b.1** Build baseline reactor (2026-05-21) — 12 modules SUCCESS
- [x] **M7b.2** Audit TCK : SPI `InMemorySpanExporterProvider` (2026-05-21) — découverte du conflit OTel SDK autoconfigure vs philo Humboldt zéro-SDK. Décision Option C : bridge confiné au runner TCK hors-reactor (cf. `tasks/m7b-architecture-analysis.md`)
- [x] **M7b.3** Hook `withExtraSpanExporter` dans `HumboldtAutoConfigure` (2026-05-21) — overload `configure(env, List<SpanExporter>)` qui attache un `SimpleSpanProcessor` par exporter extra. ~14 LOC + 1 test → **16/16 runtime PASS**
- [x] **M7b.4a** Bridge OTel SDK ↔ Humboldt (2026-05-21) — `SpanDataMapper` (conversion humboldt.SpanData → otel.SpanData via `TestSpanData.builder()`) + `OtelSpanExporterBridge` (adapte un OTel `SpanExporter` en Humboldt `SpanExporter`). Dans `humboldt-tck/src/main/`. ~140 LOC + 11 tests → **15/15 humboldt-tck PASS** (4 smoke + 9 mapper + 2 bridge)
- [x] **Aliasage `io.opentelemetry.instrumentation.annotations.WithSpan`** — fait dès M6a (l'interceptor utilise déjà l'annotation officielle, pas notre propre `io.vidocq.humboldt.cdi.WithSpan`)
- [x] **M7b.4b** Container Arquillian "embedded" `HumboldtDeployableContainer` from-scratch (2026-05-21) — décliné en 4 sous-étapes incrémentales :
  - **M7b.4b.1** Squelette : lifecycle start/stop, `LoadableExtension`, `META-INF/services` (~150 LOC)
  - **M7b.4b.2** Boot Vauban CDI : extract Class<?> du war ShrinkWrap, `VaubanContainer.builder().addBeanClass(...).build()` + `AutoConfiguredHumboldt` avec env hardcodée (~80 LOC)
  - **M7b.4b.3** Bridge OTel SDK autoconfigure : parse `META-INF/microprofile-config.properties`, scan `META-INF/services/...ConfigurableSpanExporterProvider`, instancier le provider, wrapper via `OtelSpanExporterBridge`, injecter via le hook M7b.3 (~150 LOC + `MapConfigProperties`)
  - **M7b.4b.4** `HumboldtCdiEnricher implements TestEnricher` : injection `@Inject` sur la classe de test (cas particulier `OpenTelemetry` → `GlobalOpenTelemetry.get()`, autres → `VaubanContainer.current().select(type)`) (~70 LOC)
  - **Tests** : `HumboldtArquillianBootSmokeTest`, `HumboldtCdiBootTest`, `HumboldtOtelBridgeDeployTest`, `HumboldtCdiEnricherTest` → **21/21 PASS humboldt-tck locaux**
- [x] **M7b.5** 1er test TCK officiel `OpenTelemetryBeanTest` (2026-05-21) — **2/2 PASS** ! `org.eclipse.microprofile.telemetry.tracing.tck.cdi.OpenTelemetryBeanTest` (`testOpenTelemetryBean` + `testSpanAndTracer`) passe via le profile `tck-cdi-bean` (`mvn -Ptck-cdi-bean test`). Premier test TCK officiel MP Telemetry 2.1 vert pour Humboldt

### M7c — Run complet + triage _(en cours)_

- [x] **1er run** (2026-05-21) — `mvn -Ptck-official test` : **138 tests / 62 failures / 73 skipped / 3 PASS** (~5 % des 65 applicables). Triage et plan dans `TCK.md`.
- [x] **M7c.4** (2026-05-21) Bump commons-io 2.16.1 dans humboldt-tck/pom.xml — erreurs `Tailer.builder` à 0
- [x] **M7c.1** (2026-05-21) `HumboldtTelemetryProducers` (Tracer/Span/Baggage/OpenTelemetry) dans humboldt-cdi, enregistré automatiquement à chaque deploy. **Débloque ~24 tests** : tous les Metrics CDI + tous les JVM* + Tracing.TracerTest + Tracing.ExporterSpiTest. Stats : 153 / 80 fails / 68 skip → ~31 vrais PASS (~36 % des applicables)
- [x] **M7c.3** Résolu de facto par M7c.1 (`Failed to deploy` → 0)
- [ ] **M7c.2** Conteneur HTTP : Chappe + Cassini intégrés dans `HumboldtDeployableContainer` (~400 LOC) — débloque 80 tests REST/HTTP restants
- [ ] **Gate qualité** : ≥95 % de tests applicables passent (atteignable après M7c.2)

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
