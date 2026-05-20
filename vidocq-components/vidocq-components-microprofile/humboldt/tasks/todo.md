# Humboldt — Plan de développement

> Vue synthétique pour suivre l'avancement des milestones. Le plan détaillé est dans `PLAN.md` et la liste des jalons dans `ROADMAP.md`.

## M0 — Squelette JPMS et tooling _(terminé 2026-05-20)_

- [x] PLAN.md déposé (1190 lignes, validé par Yann le 2026-05-20)
- [x] `.sdkmanrc`, `mvnw`, `pom.xml` parent Model 4.1.0, `.gitignore`, LICENSE
- [x] `humboldt-api/` : `pom.xml`, `module-info.java`, façade `Humboldt`, SPI stubs (SpanExporterProvider, MetricReaderProvider, LogRecordExporterProvider, SamplerProvider, ResourceProvider), test JUnit
- [x] `.forgejo/workflows/` : ci.yml, pr.yml, notify-slack.yml, update-dep-graph.yml, upstream-pr.yml
- [x] `docs/{en,fr}/antora.yml` + `modules/ROOT/{nav,pages/index}.adoc` avec métaphore Humboldt
- [x] CLAUDE.md, README.md (FR), README_EN.md (EN), BUG.md, BENCH.md, TCK.md, ROADMAP.md
- [x] `git init` + premier commit signé Yann Blazart (`d28de83`)
- [x] Build de vérification `mvn verify` — **2/2 tests PASS, BUILD SUCCESS** (1.5s)

## M1 — Common + Context (Virtual-Threads) _(en cours)_

- [x] `humboldt-sdk-common` : `pom.xml`, `module-info.java`, `Clock` (system), `IdGenerator.Random128` (traceId 32 hex / spanId 16 hex via `ThreadLocalRandom`, jamais tout-zéro), `Resource` (immutable, equals/hashCode, merge OTel-style)
- [x] `humboldt-sdk-common` tests : ClockTest (3), IdGeneratorTest (4 — uniqueness sur 10k itérations), ResourceTest (7)
- [x] `humboldt-context` : `pom.xml`, `module-info.java` avec `provides ContextStorageProvider with HumboldtContextStorageProvider`, `META-INF/services` fallback classpath
- [x] `HumboldtContextStorage` ThreadLocal-backed, conforme contrat OTel (attach/close/current/root), log WARNING sur attach/detach désordonné, close idempotent
- [x] Tests : provider ServiceLoader, current()=root, attach/close, nested LIFO, isolation VT (sans wrap), propagation VT (avec `Context.wrap()`), close idempotent
- [x] pom parent : ajout `<subprojects>` + dependencyManagement entries pour humboldt-sdk-common et humboldt-context
- [x] Build verify reactor complet → **23/23 tests PASS** (2 humboldt-api + 14 sdk-common + 7 context), commit `a7f0e86`

## M2 — SDK Trace _(terminé 2026-05-20)_

- [x] Modèle immutable : SpanData (12-field record), EventData, LinkData, StatusData, InstrumentationScope
- [x] Interfaces : ReadableSpan, SpanExporter, SpanProcessor, CompletableResultCode (async result)
- [x] 4 Samplers : AlwaysOn, AlwaysOff, ParentBased, TraceIdRatioBased (consistant per-trace via 64 bits bas du traceId)
- [x] Exporters utilitaires : InMemorySpanExporter (tests), LoggingSpanExporter (System.getLogger)
- [x] Core : SdkTracerProvider (builder), SdkTracer, SdkSpanBuilder (4 setAttribute primitives), SdkSpan (synchronized mutable jusqu'à end, all defaults Span/SpanBuilder OTel 1.39)
- [x] Processors : SimpleSpanProcessor (sync, ignore non-samplés), BatchSpanProcessor (queue + **virtual thread worker** + threshold/scheduleDelay/flush/shutdown drain)
- [x] Tests : 20/20 (8 Sampler + 9 SdkTracerProvider + 3 SpanProcessor) — parent/child traceId share, links, kind/status, events, recordException stack, drop unsampled, batch threshold, drain shutdown
- [x] pom parent : ajout humboldt-sdk-trace au reactor + dependencyManagement
- [x] Build verify → **43/43 tests PASS** (2 api + 14 common + 7 context + 20 trace), commit M2

## M3 — Propagator W3C + Exporter OTLP HTTP-JSON _(terminé 2026-05-20)_

- [x] `humboldt-propagator-w3c` : façade composite W3CPropagators (TraceContext + Baggage). 5 tests : composite fields, inject/extract traceparent, baggage roundtrip
- [x] `humboldt-exporter-otlp-http` : encoder OTLP/JSON manuel via StringBuilder + transport java.net.http.HttpClient + retry exponentiel borné. 5 EncoderTest + 4 E2E avec fake HttpServer JDK in-process (POST valide, retry 503, headers custom, backoff capped)
- [x] pom parent : ajout des 2 modules au reactor + dependencyManagement
- [x] Build verify → **57/57 tests PASS** (M2 43 + M3 14), 7 modules SUCCESS, 4.1s, commit M3
- [x] Décision documentée : **OTLP/HTTP-protobuf différé en M3b** (chappe-client transport + tests Jaeger testcontainers) — MVP M3 livre OTLP/JSON pour valider l'architecture pipeline E2E

## M3b — OTLP/HTTP-protobuf + chappe-client (post-MVP, conditionnel)

À démarrer quand chappe-client sera assez mature ET que la nécessité protobuf sera prouvée (TCK audit en M7). Voir `PLAN.md` §3.4 (decision A protobuf-java vs B hand-rolled).

## M4 — SDK Metric (MVP synchrone) _(terminé 2026-05-20)_

- [x] Refactor préalable : CompletableResultCode + InstrumentationScope déplacés vers humboldt-sdk-common (évite couplage cross-SDK)
- [x] `humboldt-sdk-metric` : SdkMeterProvider (builder, MeterBuilder anonymous, cache par scope), SdkMeter (counter+histogram builders fonctionnels, upDownCounter/gauge UOE), SdkLongCounter (refuse négatif), SdkDoubleHistogram (refuse NaN/négatif)
- [x] Aggregators : SumAggregator (LongAdder per attribute-set), ExplicitBucketHistogramAggregator (15 bornes par défaut, synchronized record)
- [x] PeriodicMetricReader (worker virtual thread, scheduleDelay/flush/shutdown drain)
- [x] OtlpHttpMetricExporter + OtlpJsonMetricEncoder (sum/histogram/gauge avec temporality int et isMonotonic), InMemoryMetricExporter pour tests
- [x] Tests : 6 SdkMeterProvider + 1 OtlpHttpMetric E2E = **17 tests M4** → **total 74/74 PASS**
- [x] Décision M4b documentée : Observable instruments, double/long manquants, ExponentialHistogram, ViewRegistry, DELTA différés
- [x] pom parent + module-info de l'exporter + commit M4

## M5 — SDK Log (prochain)

À démarrer après M4. Voir `PLAN.md` §13 et `ROADMAP.md`.

## Leçons en cours de session

À documenter au fur et à mesure dans `tasks/lessons.md`.
