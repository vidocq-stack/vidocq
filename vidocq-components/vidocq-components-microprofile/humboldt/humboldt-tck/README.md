# Humboldt :: TCK Runner

Harness de conformance **MicroProfile Telemetry 2.1** — exécute le TCK
officiel Eclipse contre l'implémentation Humboldt.

> Projet Maven **STANDALONE** (Model Version 4.0.0, sans `<parent>`),
> volontairement EN DEHORS du reactor Humboldt. Contrainte ShrinkWrap
> documentée dans le `pom.xml` et le CLAUDE.md du workspace.

## État M7 — SCAFFOLD (2026-05-21)

✅ **Acquis** :
- Coordonnées TCK confirmées publiques sur Maven Central :
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-tracing-tck:2.1`
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-metrics-tck:2.1`
  - `org.eclipse.microprofile.telemetry:microprofile-telemetry-logs-tck:2.1`
  - Pas d'aggregator unique `microprofile-telemetry-tck:2.1` — c'est split.
- Stack TCK : **TestNG + Arquillian + ShrinkWrap** (pas JUnit).
- `pom.xml` Model 4.0.0 du runner résout toutes ses dépendances.
- `HumboldtTckSmokeTest` valide que Humboldt runtime + TCK classes + OTel
  instrumentation annotation sont sur le classpath.

🚧 **À venir en M7b** :
- **Adapter Arquillian Humboldt** = composer Vauban (CDI Lite, validation
  PLAN §15.1) + Cassini (JAX-RS via Chappe) + Humboldt runtime pour
  faire tourner les `@Deployment` ShrinkWrap des tests TCK.
- **Aliasage `@WithSpan`** : le TCK utilise
  `io.opentelemetry.instrumentation.annotations.WithSpan` — notre interceptor
  Humboldt doit aussi intercepter cette annotation officielle (en plus de
  notre `io.vidocq.humboldt.cdi.WithSpan`).
- **`InMemorySpanExporterProvider` SPI** : le TCK importe sa propre version
  via `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.SdkTracerProviderConfigurer`
  (à vérifier) — il faudra que `humboldt-runtime` respecte ce contrat.

🚧 **À venir en M7c** :
- Premier run de la suite TCK (`-Ptck-official`)
- Identification des tests qui passent vs ceux qui plantent
- Documentation des **challenges TCK** (tests désactivés avec justification) dans `humboldt/TCK.md`
- Gate qualité : score TCK ≥ 95% applicable (~objectif visé)

## Utilisation

```bash
# Depuis humboldt/ (au-dessus de ce dossier)
./run-official-tck-telemetry-2.1.sh         # smoke (HumboldtTckSmokeTest seul)
./run-official-tck-telemetry-2.1.sh all     # suite TCK complète (M7c+)
./run-official-tck-telemetry-2.1.sh -Dtest=BasicAppTest  # test ciblé
```

Le script effectue :
1. `mvn install -DskipTests` sur le reactor Humboldt parent (pour installer
   les snapshots 0.1.0 dans le M2 local que le runner standalone consommera)
2. `cd humboldt-tck && mvn ...` (selon le profile activé)

## Structure

```
humboldt-tck/
├── pom.xml                                      # Model 4.0.0 standalone
├── README.md                                    # ce fichier
├── src/
│   ├── main/java/io/vidocq/humboldt/tck/        # extensions M7b éventuelles
│   └── test/
│       ├── java/io/vidocq/humboldt/tck/
│       │   └── HumboldtTckSmokeTest.java        # smoke autonomous, sans container
│       └── resources/
│           ├── arquillian.xml                   # config Arquillian (M7b)
│           └── tck-suite.xml                    # suite TestNG agrégant les 3 TCK
└── target/
```

## Pourquoi hors-reactor ?

Cf. `pom.xml` header et `humboldt/CLAUDE.md` (section "Contrainte d'architecture
critique : TCK runners hors reactor"). Résumé : ShrinkWrap Maven Resolver 3.3
(dépendance transitive obligatoire du TCK Arquillian) repose sur maven-resolver
1.9 / maven-model 3.9 qui ne savent pas parser les POMs Model 4.1.0 du reactor.
Son `ClasspathWorkspaceReader` scanne le reactor courant et crashe sur tout
POM Humboldt (version implicite via parent).

Même pattern documenté dans cassini-tck, foy-tck, champollion-tck.
