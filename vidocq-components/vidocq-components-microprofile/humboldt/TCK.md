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

**M7 SCAFFOLD (2026-05-21)** — pom + arquillian.xml + smoke test livrés.
Aucun test TCK officiel ne passe encore (adapter Arquillian Humboldt à
livrer en M7b).

| Signal | Statut tests réels | Étape |
|---|---|---|
| Tracing | 🚧 0/N | M7b (adapter Arquillian) → M7c (premier run) |
| Metrics | 🚧 0/N | M7b → M7c |
| Logs | 🚧 0/N | M7b → M7c |
| Baggage | ✅ propagator W3C livré M3 | tests TCK en M7c |
| Config | ✅ env vars OTEL_* livré M6c | tests TCK en M7c |

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

### M7c — Run + challenges (🚧 à venir)
- `./run-official-tck-telemetry-2.1.sh all` premier run complet
- Identifier les tests qui passent vs ceux qui plantent
- Documenter les challenges (tests désactivés) dans la section ci-dessous
- **Gate** : ≥95 % de tests applicables passent

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
