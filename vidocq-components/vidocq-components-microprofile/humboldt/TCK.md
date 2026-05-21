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
| Tracing | ✅ 2/N (`OpenTelemetryBeanTest`) | M7c — run suite complète et triage |
| Metrics | 🚧 0/N | M7c |
| Logs | 🚧 0/N | M7c |
| Baggage | ✅ propagator W3C livré M3 | tests TCK en M7c |
| Config | ✅ env vars OTEL_* livré M6c | tests TCK en M7c |

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

- [ ] **M7c.1 — Producers CDI Tracer/Span/Baggage** (humboldt-cdi, ~50 LOC). Impact : débloquer 12+ tests.
- [ ] **M7c.2 — Conteneur HTTP : Chappe + Cassini intégrés** (humboldt-tck, ~400 LOC). Impact : débloquer 56+ tests.
- [ ] **M7c.3 — Investigation `Failed to deploy` cas par cas** (8 tests).
- [ ] **M7c.4 — Bump commons-io dans le runner TCK** (1 ligne pom). Impact : débloquer 24 tests JVM metrics.

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
