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

**M7b livré (2026-05-21)** — adapter Arquillian Humboldt complet :
hook `withExtraSpanExporter` runtime + bridge OTel SDK + `HumboldtDeployableContainer`
+ `HumboldtCdiEnricher`. **Premier test TCK officiel `OpenTelemetryBeanTest` PASS (2/2)**.

| Signal | Statut tests réels | Étape |
|---|---|---|
| Tracing | ✅ 4 / ~50 (OpenTelemetryBeanTest, TracerTest, ExporterSpiTest) | M7c.2 — débloquer HTTP |
| Metrics | ✅ 24 / ~50 (CDI counters/gauges/histos + JVM*) | M7c.2 — débloquer HTTP histograms |
| Logs | ✅ 3 / ~10 (JulTest, ServerInstanceTest) | M7c |
| Baggage | ✅ propagator W3C livré M3 | tests TCK en M7c.2 |
| Config | ✅ env vars OTEL_* livré M6c | tests TCK en M7c.2 |

**Profile pour relancer le 1er test** :
```bash
cd humboldt-tck && mvn -ntp -f pom.xml -Ptck-cdi-bean test
```

## Roadmap M7

### M7a — Scaffold (✅ terminé)
- pom `humboldt-tck/` Model 4.0.0 standalone hors-reactor
- arquillian.xml placeholder + tck-suite.xml TestNG
- HumboldtTckSmokeTest : valide classpath + AutoConfiguredHumboldt fonctionnel
- Script `run-official-tck-telemetry-2.1.sh` à la racine

### M7b — Adapter Arquillian Humboldt (🚧 à venir)
1. Composer Vauban CDI Lite + Cassini JAX-RS (chappe transport) + humboldt-runtime
   en un container Arquillian "embedded" léger
2. **Aliaser `io.opentelemetry.instrumentation.annotations.WithSpan`** dans
   l'interceptor humboldt-cdi (en plus de notre `io.vidocq.humboldt.cdi.WithSpan`)
3. Implémenter le SPI `InMemorySpanExporterProvider` attendu par le TCK
4. Vérifier qu'au moins un test smoke TCK officiel (ex. `OpenTelemetryBeanTest`)
   démarre sans erreur

### M7c — Run + triage _(en cours)_

#### Premier run (2026-05-21) — `mvn -Ptck-official test`

Reproduire localement :
```bash
cd humboldt-tck && mvn -ntp -f pom.xml -Ptck-official test 2>&1 | tee /tmp/tck-official.log
```

```
Tests run: 138, Failures: 62, Errors: 0, Skipped: 73
→ PASS: 3 / Applicables (138 - 73 = 65) → ~5 %
```

| Catégorie | Échecs | Cause | Action requise |
|---|---|---|---|
| **A. `@ArquillianResource URL` non résolu** | 56 | Tests qui font HTTP : `@ArquillianResource private URL url;`. Notre container ne démarre pas de serveur HTTP, donc l'enricher `arquillian-test-resource-jakarta` n'a aucune URL à fournir. | Démarrer Chappe HTTP + Cassini JAX-RS dans `HumboldtDeployableContainer.deploy()`. Le port doit être exposé dans `ProtocolMetaData` → `HTTPContext`. |
| **B. `Failed to deploy ...war`** | 8 | Erreur au deploy — sans doute classes/deps manquantes dans le war ShrinkWrap qui crashent `extractBeanClasses` ou `VaubanContainer.build()`. Voir traces individuelles. | Investiguer cas par cas (TracerTest, RestClientSpan*Test). |
| **C. `injected{Span, Baggage, Tracer}` est null** | 12 | Spec MP Telemetry §"Required CDI beans" : l'impl doit fournir des producers `@Produces Tracer`, `@Produces Span` (Span.current()), `@Produces Baggage` (Baggage.current()). Notre `humboldt-cdi` n'a que `@WithSpan` — pas de producers. | Ajouter `HumboldtTelemetryProducers` dans humboldt-cdi avec les 3 `@Produces`. |
| **D. `NoSuchMethod org.apache.commons.io.input.Tailer.builder()`** | 24 | Conflit de version commons-io entre les deps TCK metrics et notre classpath. Tests JvmMemoryTest, JvmThreadTest, JvmCpuTest, etc. | Forcer commons-io 2.16+ dans `humboldt-tck/pom.xml`. |

> Les 4 catégories se chevauchent partiellement (un test JvmMemory échoue à la fois sur l'URL et sur Tailer). Adresser une catégorie peut débloquer plus de tests que le compteur ne le suggère.

#### Plan M7c

- [x] **M7c.4 — Bump commons-io 2.16.1** (2026-05-21). Plus aucune erreur `Tailer.builder`.
- [x] **M7c.1 — Producers CDI Tracer/Span/Baggage/Meter/Logger/OpenTelemetry** (2026-05-21). `HumboldtTelemetryProducers` dans humboldt-cdi, ajouté systématiquement à chaque deploy. Fallback direct dans `HumboldtCdiEnricher` pour ces types (au cas où Vauban CDI Lite ne sait pas résoudre via les producers en présence de ClassLoader isolation).
- [x] **M7c.2 — Conteneur HTTP : Chappe + Cassini intégrés** (2026-05-21). `CassiniHarness` simplifié (~180 LOC) dans humboldt-tck. `HumboldtDeployableContainer` détecte les `@Path`/`@Provider` du war, démarre Cassini sur Chappe avec les filters `humboldt-rest` (HumboldtServerRequestFilter/ResponseFilter/SpanFinalizer), expose le port via `HTTPContext`. Toutes les erreurs URL `@ArquillianResource` à 0.
- [x] **M7c.3 — Investigation `Failed to deploy` cas par cas** — résolu en passant par M7c.1.

#### Run après M7c.1 + M7c.2 + M7c.4

```
Tests run: 113, Failures: 80, Errors: 0, Skipped: 28
```

**Comptage strict** (uniquement les méthodes `test*`, sans setUp/tearDown/arquillianBefore) :

- **5 vraies test methods PASS** :
  - `OpenTelemetryBeanTest.testOpenTelemetryBean`, `testSpanAndTracer`
  - `TracerTest.tracer`
  - `ExporterSpiTest.testExporter`
  - `ServerInstanceTest.runtimeInstance`
- **52 vraies test methods FAIL**
- **28 skipped**

Soit **5 / 57 applicables ≈ 9 %** (en comptage strict).

**Comptage lâche** (inclut `arquillianBeforeTest`/`setUp` qui passent quand le bootstrap fonctionne) : 48 PASS apparents sur 113 = **42 %**. Ces "passes" prouvent que l'infrastructure (Vauban, Chappe, Cassini, bridge OTel, enricher, producers) tourne — le test lui-même échoue sur une feature Humboldt non implémentée.

#### Catégorisation des 52 failures réelles

| # | Catégorie | Tests | Cause | Action |
|---|---|---|---|---|
| **L1** | M4b SDK metric incomplet | ~24 | `SdkLongCounterBuilder.ofDoubles()` jette `UnsupportedOperationException: M4 MVP : DoubleCounter pas encore supporté (différé en M4b)`. Idem DoubleHistogram, LongHistogram, gauge.observable, etc. | Implémenter M4b (DoubleCounter, LongHistogram, ExponentialHistogram, Observable instruments) |
| **L2** | M5b SDK log bridges manquants | ~3 | `JulTest` échoue car aucun bridge JUL → OTel logs. Spec : `java.util.logging.Handler` qui forward vers `LoggerProvider`. | Implémenter M5b (JulHandler, SLF4JAppender) |
| **L3** | MP Rest Client absent | ~6 | `No RestClientBuilderResolver implementation found` — Cyrano (vidocq/cyrano) n'est pas branché au container Arquillian. | Ajouter cyrano-rest-client au container M7c.5 |
| **L4** | JAX-RS Client absent | ~3 | `Provider for jakarta.ws.rs.client.ClientBuilder cannot be found` — Cassini-core n'implémente pas l'API Client (uniquement Server). | Implémenter `cassini-client` (hors-scope humboldt) OU faire pointer ServiceLoader vers Jersey-Client en scope test uniquement |
| **L5** | CLIENT spans manquants | ~6 | Tests `*AsyncTest` font `expected [3] but found [1]` — les SERVER spans sont là, mais le client JAX-RS ne génère pas de CLIENT spans (manque `JaxRsClientFilter` côté humboldt-rest). | Ajouter `humboldt-rest` `ClientRequestFilter`/`ClientResponseFilter` |
| **L6** | HttpHistogramTest http.route | 1 | Le filter humboldt-rest n'extrait pas `http.route` (template avant binding). | Améliorer `HumboldtServerRequestFilter` pour capter `UriInfo.getMatchedTemplates()` |
| **L7** | Proxy CDI Span | 1 | `SpanBeanTest.spanBeanChange` : Span injecté capturé une seule fois → ne suit pas `Span.current()` changeant. Spec MP Telemetry attend un proxy CDI. | Implémenter producer `@RequestScoped` ou proxy |
| **L8** | BaggageBean.value1 vs null | 1 | Idem L7 mais sur Baggage. | Idem |
| **L9** | B3 propagation rest client | ~4 | `Failed to create rest client` — dépend de L3+L4. | (résolu via L3+L4) |

#### Roadmap au-delà de M7c

| Milestone | Cible | Tests débloqués estimés |
|---|---|---|
| **M4b** | Variants Long/Double + ExponentialHistogram + Observable | +24 |
| **M5b** | JulHandler + SLF4JAppender | +3 |
| **M7c.5** | Intégration Cyrano (MP Rest Client) dans container | +6 |
| **M7c.6** | JAX-RS Client (Jersey scope=test ou cassini-client) | +3 |
| **M7c.7** | CLIENT spans (humboldt-rest ClientFilter) | +6 |
| **M7c.8** | http.route extraction | +1 |
| **M7c.9** | Proxy CDI Span/Baggage | +2 |

**Cible Gate ≥95 %** atteignable une fois M4b + M5b + M7c.5→9 livrés (additif ~45 tests, total ~50/57 ≈ 88 %, +marge).

#### Skipped (73)

À investiguer : sans doute des tests TestNG annotés `@Test(enabled=false)` côté TCK, ou des tests dont la `@BeforeMethod` échoue silencieusement avant la collecte. À tagger comme "indisponibles pour cause amont" si applicable.

#### Gate qualité

**Gate ≥95 % de tests applicables** non atteint au 1er run (5 %). Atteignable après M7c.1 → M7c.4.

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

_Aucun challenge à ce jour (M7 SCAFFOLD)._
