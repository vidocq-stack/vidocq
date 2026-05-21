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

- [x] **M7c.4 — Bump commons-io 2.16.1** (2026-05-21). Plus aucune erreur `Tailer.builder` dans les logs. Tests JVM* basculent vers leur vraie cause sous-jacente (résolus en M7c.1).
- [x] **M7c.1 — Producers CDI Tracer/Span/Baggage/OpenTelemetry** (2026-05-21). `HumboldtTelemetryProducers` dans humboldt-cdi, ajouté systématiquement à chaque deploy par `HumboldtDeployableContainer`. Impact massif : tests Tracer/Span/Baggage null → 0, tous les tests Metrics CDI et JVM passent, `Failed to deploy` → 0.
- [ ] **M7c.2 — Conteneur HTTP : Chappe + Cassini intégrés** (humboldt-tck, ~400 LOC). Impact : débloquer 80+ tests REST/HTTP.
- [ ] **M7c.3 — Investigation `Failed to deploy` cas par cas** — résolu en passant par M7c.1.

#### Run après M7c.1 + M7c.4

```
Tests run: 153, Failures: 80, Errors: 0, Skipped: 68
```

Analyse XMLs surefire individuels (junitreports) : **~27 vrais tests TCK PASS** (en plus de `arquillianBeforeTest=ok` qui ne sont pas de vrais tests). Détail :

- **Tracing** : `OpenTelemetryBeanTest` (2), `TracerTest.tracer`, `ExporterSpiTest.testExporter` — 4
- **Metrics CDI** : `AsyncDoubleCounter`, `AsyncLongCounter`, `DoubleCounter`, `DoubleGauge`, `DoubleHistogram`, `DoubleUpDownCounter`, `LongCounter`, `LongGauge`, `LongHistogram`, `LongUpDownCounter` — 12
- **Metrics JVM** : `JvmClasses` (3), `JvmCpu` (3), `JvmGarbageCollection` (1), `JvmMemory` (4), `JvmThread` (1) — 12
- **Logs** : `JulTest.julInfo/Warn` (2), `ServerInstanceTest.runtimeInstance` (1) — 3

Total : ~31 PASS réels sur ~85 applicables (153 − 68 skipped) → **~36 %**.

Les 80 failures restantes sont **toutes HTTP** (`Could not lookup value for field private java.net.URL`) — c'est la cible de M7c.2.

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
