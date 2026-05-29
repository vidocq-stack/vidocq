# Humboldt — Statut TCK MicroProfile Telemetry 2.1

## Cible

100 % de conformité TCK officiel **MicroProfile Telemetry 2.1** sur les 3 suites
(tracing + metrics + logs), avec configuration `OTEL_*` / `MP_TELEMETRY_*`,
propagation W3C TraceContext + Baggage.

## Coordonnées TCK (audit M7.1 — 2026-05-21)

✅ **Public Maven Central** — pas d'install manuel non-public requis :

| Signal | Artifact |
|---|---|
| Tracing | `org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.1` |
| Metrics | `org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.1` |
| Logs | `org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.1` |

❌ Pas d'aggregator unique `microprofile-telemetry-tck:2.1` — c'est split en 3.

❌ Pas de JAR séparé `microprofile-telemetry-api:2.1` — la spec MP Telemetry
2.x délègue intégralement à l'API publique OTel (pas de classes MP-specific
hors annotation `@WithSpan` qui vient de
`io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations`).

## Stack TCK

**TestNG + Arquillian + ShrinkWrap** (pas JUnit). Le TCK importe sa propre
copie de `io.opentelemetry:opentelemetry-sdk` pour ses fixtures internes
(`InMemorySpanExporter`, etc.) — c'est OK côté runner hors-reactor, ça ne
pollue pas le code applicatif Humboldt en production.

## Statut courant

**100 % PASS (2026-05-24)** — TCK MicroProfile Telemetry 2.1 (suite tracing) entièrement vert :

```
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
```

| Signal | Statut | Notes |
|---|---|---|
| Tracing | ✅ **85/85 PASS** | Suite complète TCK officiel — 100 % applicable |
| Metrics | ✅ SDK M4b livré | Instruments Long/Double Counter/Gauge/Histogram + Observable + JVM metrics ; pas de tests TCK dédiés dans `microprofile-telemetry-metrics-tck:2.1` |
| Logs | ✅ SDK M5b livré | JulHandler + bridge log ; `mptelemetry.tck.log.file.path` câblé ; pas de tests TCK dédiés dans `microprofile-telemetry-logs-tck:2.1` |
| Baggage | ✅ propagateur W3C livré M3 | |
| Config | ✅ vars OTEL_* / MP_TELEMETRY_* livré M6c | |

## Roadmap M7

### M7a — Scaffold (✅ terminé)
- pom `humboldt-tck/` Model 4.0.0 standalone hors-reactor
- arquillian.xml placeholder + tck-suite.xml TestNG
- HumboldtTckSmokeTest : valide classpath + AutoConfiguredHumboldt fonctionnel
- Script `run-official-tck-telemetry-2.1.sh` à la racine

### M7b — Adapter Arquillian Humboldt (✅ terminé)

`HumboldtDeployableContainer` + `HumboldtCdiEnricher` + `CassiniHarness` HTTP
+ `HumboldtTelemetryProducers` CDI + bridge OTel SDK `withExtraSpanExporter`.

### M7c — Run + triage (✅ terminé — 85/85 PASS)

#### Run final (2026-05-24) — `mvn -Ptck-official test`

```
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
```

**85/85 PASS — 100 %** sur la suite tracing officielle.

Progression complète de la session 2026-05-21→24 (5 → 85 PASS, +1600 %) :

| Étape | Action | Δ PASS |
|---|---|---|
| M7c.4 | Bump commons-io 2.16.1 — plus d'erreur `Tailer.builder` | infra |
| M7c.1 | `HumboldtTelemetryProducers` CDI (Tracer/Span/Baggage/Meter/Logger) | +~12 |
| M7c.2 | `CassiniHarness` HTTP + `HTTPContext` port — 0 erreur `@ArquillianResource URL` | +~20 |
| M4b | Variants Long/Double Counter/Gauge/Histogram + Observable + JVM metrics | +23 |
| M5b | JulHandler → log bridge ; `humboldt-tck-logs.txt` câblé | +3 |
| M7c.5-6 | Intégration Cyrano + cassini-client dans container Arquillian | +6 |
| M7c.7 | Filtres CLIENT humboldt-rest (`ClientRequestFilter`/`ClientResponseFilter`) | +6 |
| M7c.8 | `http.route` via `UriInfo.getMatchedTemplates()` | +1 |
| M7c.9 | Proxy CDI `@RequestScoped` Span/Baggage | +2 |
| M7c.10-12 | B3/Jaeger propagators, SPI Propagator/Sampler, auto-instrumentation MP Rest Client | +8 |
| M7c (final) | BCE `@WithSpan` + `@SpanAttribute` sur paramètres, `HumboldtTckExecutor` SPI | +4 |

#### Gate qualité

**100 % de tests applicables PASS** ✅

## Contrainte d'architecture

Comme `cassini-tck`, `champollion-tck`, `foy-tck` : `humboldt-tck` est
**hors reactor** (POM Model 4.0.0 standalone, sans `<parent>`), pour contourner
l'incompatibilité ShrinkWrap Maven Resolver 3.3 (deps transitive du TCK) qui
ne sait pas parser les POMs Model 4.1.0. Voir `CLAUDE.md` racine du workspace
pour le détail. Ne pas réintégrer au reactor.

## Format des challenges (template)

Quand un test TCK officiel est désactivé pour cause d'interprétation spec
non-portable, environnement TCK douteux, ou pour une raison hors scope :

```
### [TCK-N] NomDuTest
- **Catégorie** : interprétation-spec / environnement-tck / hors-scope
- **Date** : YYYY-MM-DD
- **Justification** : pourquoi le test est désactivé / contesté
- **Action upstream** : issue ouverte ? PR proposée ? lien
- **Quand le réactiver** : condition de réactivation
```

---

_Aucun challenge fonctionnel — 100 % des tests de la suite officielle passent._
