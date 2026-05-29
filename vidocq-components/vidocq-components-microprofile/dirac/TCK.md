# Dirac — Statut TCK MicroProfile Metrics 5.1.1

## Cible

100 % de conformité TCK officiel **MicroProfile Metrics 5.1.1** — compteurs, timers,
gauges, histogrammes, endpoint `/metrics` OpenMetrics + JSON, intégration CDI.

## Coordonnées TCK

✅ **Public Maven Central** — pas d'install manuel requis :

| Artifact |
|---|
| `org.eclipse.microprofile.metrics:microprofile-metrics-tck:5.1.1` |

## Exécution

```bash
./run-official-tck-mp-metrics-5.1.sh           # smoke test (DiracTckSmokeTest, hors Arquillian)
./run-official-tck-mp-metrics-5.1.sh all       # suite complète (TestNG + Arquillian)
./run-official-tck-mp-metrics-5.1.sh -Dtest=NomDuTest
```

Rapport généré dans `dirac-tck/target/tck-report.txt`.

## Architecture du runner

| Composant | Rôle |
|---|---|
| `DiracDeployableContainer` | Container Arquillian *embedded* — boot Vauban CDI + Cassini JAX-RS sur Chappe |
| `DiracArquillianExtension` | SPI `LoadableExtension` — enregistre le container |
| Vauban CDI | Résout `@Inject MetricRegistry`, intercepteurs `@Counted`/`@Timed`, BCE `DiracExtension` |
| Cassini + Chappe | Expose `/metrics` (OpenMetrics + JSON) sur port aléatoire ; `@ArquillianResource URL` câblé |

**Contrainte hors-reactor** : `dirac-tck` (POM Model 4.0.0 standalone, sans `<parent>`)
pour contourner ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 — même contrainte que
`cassini-tck`, `champollion-tck`, `foy-tck`, `humboldt-tck`. Ne pas réintégrer au reactor.

## Score actuel

```
Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
```

**127/127 PASS — 100 %** (rapport du 2026-05-24, commit `48f125b`).

```text
# Tests réussis : 127/127
RESULT : PASS
```

Résumé des travaux M8 qui ont conduit au 100 % :

- Résolution des intercepteurs `@Counted`/`@Timed` sur héritage, stéréotypes et méthodes bridge proxy
- Comportement "removed metric" lors d'invocations interceptées (attendu par le TCK)
- Bootstrap des déploiements Arquillian avec valeurs `microprofile-config.properties` scoped par archive
- `DiracExtension` BCE : discovery auto des `@Gauge`, `@Counted`, `@Timed`, `@RegistryType`

## Challenges connus

Aucun — 100 % des tests applicables passent, aucun test désactivé.

## Contrainte d'architecture

`dirac-tck/pom.xml` reste en **Model 4.0.0** standalone (sans `<parent>`).
Voir `CLAUDE.md` racine du workspace pour la justification (ShrinkWrap Maven Resolver 3.3).
