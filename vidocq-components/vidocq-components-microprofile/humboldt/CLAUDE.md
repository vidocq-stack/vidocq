# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Pour le TCK officiel (M7+), l'artefact `org.eclipse.microprofile.telemetry:microprofile-telemetry-tck:2.1` devra être disponible (sur Central ou installé dans le M2 local — procédure documentée dans `TCK.md` quand le runner sera créé)

## Commandes essentielles

```bash
# Build du reactor (sans tests)
./mvnw -ntp install -DskipTests

# Build avec tests unitaires
./mvnw -ntp verify

# Build d'un seul module
./mvnw -ntp -pl humboldt-api install

# TCK (à venir en M7)
# ./run-official-tck-telemetry-2.1.sh         # smoke
# ./run-official-tck-telemetry-2.1.sh all     # suite complète
```

> `humboldt-tck` (livré en M7) sera **hors reactor** (`pom.xml` Model 4.0.0 standalone, sans `<parent>`) pour contourner l'incompatibilité ShrinkWrap Maven Resolver 3.3 / Model 4.1.0 — même contrainte que `cassini-tck`, `champollion-tck`, `foy-tck`. Ne pas réintégrer ce module au reactor.

## Architecture

Humboldt est une implémentation MicroProfile Telemetry 2.1 (tracing + metrics + logs) basée sur l'API publique OpenTelemetry, **sans embarquer `opentelemetry-sdk` ni les exporters tiers** — on réécrit tout le SDK derrière la surface OTel API standard.

```
humboldt-api                       ← façade publique + SPI stable (M0)
humboldt-sdk-common                ← Resource, Clock, Attributes, IdGenerator (M1)
humboldt-context                   ← ContextStorage Virtual-Threads-friendly (ScopedValue) (M1)
humboldt-sdk-trace                 ← SdkTracerProvider, SpanProcessor, samplers, BatchSpanProcessor VT (M2)
humboldt-exporter-otlp-http        ← exporter OTLP/HTTP-protobuf via chappe-client (M3)
humboldt-propagator-w3c            ← W3C TraceContext + Baggage (M3)
humboldt-sdk-metric                ← Counter/Histogram/UpDownCounter/Gauge, PeriodicReader (M4)
humboldt-sdk-log                   ← LogRecordProcessor, bridge JUL/SLF4J (M5)
humboldt-cdi                       ← interceptor @WithSpan via Vauban (M6)
humboldt-rest                      ← filter JAX-RS via Cassini (M6)
humboldt-runtime                   ← agrégateur autoconfig (M6)
humboldt-tck                       ← runner TCK officiel hors-reactor (M7)
```

**Flux d'une requête HTTP entrante instrumentée** :
`Chappe Filter → humboldt-rest (Cassini) → humboldt-context (ScopedValue<Context>) → @WithSpan resource method → humboldt-sdk-trace → humboldt-exporter-otlp-http → chappe-client → Collector`

**Décisions structurantes** (cf. `PLAN.md`) :

- **API OTel acceptée en dépendance** (`opentelemetry-api`, `opentelemetry-context`, `opentelemetry-semconv`). SDK et exporters tiers **refusés**.
- **gRPC OTLP = post-MVP** via futur module `chappe-grpc` (cf. `chappe/tasks/todo.md` Phase 7) — jamais via grpc-java/Netty.
- **Doc bilingue en/fr dès M0** sous `docs/en/` et `docs/fr/`.
- **Codegen statique** (Class-File API JEP 484 + APT) plutôt que réflexion runtime ou bytecode agent.

## Contraintes d'architecture à ne pas violer

1. **Aucun `import io.opentelemetry.sdk.*`** dans humboldt — on réécrit ce code, on ne le consomme pas.
2. **Aucune dépendance Netty / grpc-java / OkHttp / Guava** — toute exception doit passer par le `dependency-gatekeeper` agent.
3. **`@WithSpan` doit fonctionner sur virtual threads sans pinning** — utiliser `ScopedValue<Context>` (JEP 506), jamais `ThreadLocal` direct sur le hot path.
4. **`humboldt-tck/pom.xml` reste en Model 4.0.0** une fois créé (contrainte ShrinkWrap).
5. **Conformité MicroProfile Telemetry 2.1** : tout patch sur le core doit préserver le score TCK une fois atteint.

## Conventions

- **Java modules explicites** : tous les modules ont un `module-info.java` minimal, `exports` ciblés.
- **Packages** : `io.vidocq.humboldt.spi.*` = SPI public stable ; `io.vidocq.humboldt.internal.*` = code interne.
- **Maven groupId** : `io.vidocq.humboldt`.
- **Logging** : `System.getLogger(Class.class.getName())` exclusivement, jamais SLF4J/Log4j dans le code humboldt lui-même (humboldt fournit un *bridge* vers SLF4J, il ne le consomme pas).
- **TDD** : test (ou TCK scenario) avant le code. Voir `tasks/todo.md` pour le découpage M0..M9.

## Roadmap

Le plan détaillé est dans `PLAN.md` (§13 jalons M0..M9). En résumé :

- **M0** — squelette JPMS + workflows + doc (en cours)
- **M1** — `humboldt-sdk-common` + `humboldt-context` (Resource, Clock, ScopedValueContextStorage)
- **M2** — `humboldt-sdk-trace` (premier signal complet, TCK tracing partiel)
- **M3** — `humboldt-propagator-w3c` + `humboldt-exporter-otlp-http` (TCK tracing PASS)
- **M4** — `humboldt-sdk-metric` (TCK metrics PASS)
- **M5** — `humboldt-sdk-log` (TCK logs PASS)
- **M6** — `humboldt-cdi` + `humboldt-rest` + `humboldt-runtime` (`@WithSpan`, instrumentation auto)
- **M7** — `humboldt-tck` hors-reactor, TCK 100 %
- **M8** — benchmarks vs SmallRye, ADRs perf
- **M9** — Antora doc complète, release 1.0
