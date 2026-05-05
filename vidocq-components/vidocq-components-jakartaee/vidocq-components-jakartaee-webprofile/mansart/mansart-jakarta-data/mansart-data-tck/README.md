# mansart-data-tck

Runner pour le **TCK officiel Jakarta Data 1.0** (`jakarta.data:jakarta-data-tck:1.0.1`).

## Pourquoi ce module est hors du reactor principal

Module en `modelVersion 4.0.0` standalone (sans `<parent>`). Raison documentée dans le `CLAUDE.md` racine du workspace : ShrinkWrap Maven Resolver 3.3 (transitive du TCK) ne sait pas parser les POMs Maven Model 4.1.0. Tant qu'upstream ShrinkWrap ne supporte pas Model 4.1, ce module reste détaché.

## État (à jour 2026-05-05)

| Brique | État |
| --- | --- |
| POM standalone Model 4.0.0 + deps Arquillian/TestNG/JUnit5 + TCK officiel | ✅ M6 |
| Script `run-official-tck-data-1.0.sh` | ✅ M6 |
| BCE Mansart (`mansart-data-cdi`) wire les `@Repository` dans Vauban | ✅ M6.1 |
| Connecteur Arquillian Vauban (porté depuis `vauban-tck-runner`) | ✅ M6.2 |
| Suite TCK officielle 1.0.1 — résolue depuis Maven Central + 73 EntityTests | ✅ M6.3 |
| Runtime impl generation (Class-File API) → **73/73 EntityTests PASS sur H2** | ✅ M7-25 |
| **Variante PostgreSQL via Testcontainers → 73/73 EntityTests PASS sur PG** | ✅ M6.5 |
| TCK SignatureTests | ⏳ filtré (`<includes>EntityTests.class</include>`) |
| TCK PersistenceTests / NoSQLTests | ⏳ hors scope (besoin `mansart-persistence`) |

**Plus de blocage** : le BCE `MansartDataExtension` (`mansart-data-cdi`) découvre maintenant toutes les interfaces `@Repository` du déploiement TCK et enregistre des beans synthétiques `@Singleton` typés sur l'interface. Les implémentations sont **générées à runtime via Class-File API** (`mansart-data-core/RuntimeRepositoryClassGenerator`, M7-25) — aucun proxy dynamique, AOT-friendly.

## Installation des artefacts TCK

**Bonne nouvelle (M6.3)** : `jakarta.data:jakarta.data-tck:1.0.1` (note le **point**, pas le tiret, dans l'artifactId) **est sur Maven Central**. Aucun install manuel nécessaire — le profil `-Ptck-run` le télécharge automatiquement.

```xml
<dependency>
    <groupId>jakarta.data</groupId>
    <artifactId>jakarta.data-tck</artifactId>
    <version>1.0.1</version>
</dependency>
```

## Lancement

```bash
# Smoke harness (Vauban + Mansart, 6 tests, défaut, sans le TCK)
./run-official-tck-data-1.0.sh --smoke

# Suite TCK officielle EntityTests sur H2 in-memory (défaut)
./run-official-tck-data-1.0.sh
# = mvn -Ptck-run test

# Suite TCK officielle EntityTests sur PostgreSQL (Testcontainers)
./run-official-tck-data-1.0.sh --pg
# = mvn -Ptck-run,tck-pg test

# Cibler un test précis
./run-official-tck-data-1.0.sh -Dtest=EntityTests#testFindAll
```

Prérequis :
- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` du sous-projet ; `cd mansart-jakarta-data && sdk env`)
- `mansart-jakarta-data` build et installé (`mvn install -DskipTests` depuis `mansart-jakarta-data/` — le script le fait automatiquement si nécessaire)
- Pour `--pg` : **Docker** (Docker Desktop, OrbStack, colima…). Image `postgres:17-alpine` téléchargée au premier run.
- TCK 1.0.1 résolu automatiquement depuis Maven Central (`jakarta.data:jakarta.data-tck:1.0.1`).

## Mode PostgreSQL (M6.5)

Le profile Maven `tck-pg` (cumulable avec `tck-run`) substitue tout le wiring de DataSource :

- Active `PostgresDataSourceProducer` (Testcontainers `postgres:17-alpine`) au lieu de `H2DataSourceProducer`.
- Substitue `mansart-data-dialect-postgresql` à `mansart-data-dialect-h2` dans le déploiement ShrinkWrap (BCE découvre `PostgresqlDialectFactory` via `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory`).
- Le système property `mansart.tck.dialect=pg` est positionné par le profile et lu par `MansartTckArchiveAppender`.

Le container PostgreSQL est démarré une fois par JVM (singleton statique, `Runtime.addShutdownHook` pour la propreté). Aucun setup manuel.

**Score actuel :**

| Plateforme | EntityTests | Temps | Run |
| --- | --- | --- | --- |
| H2 in-memory | **73 / 73** ✅ | ~30 s | `./run-official-tck-data-1.0.sh` |
| PostgreSQL 17 (Testcontainers) | **73 / 73** ✅ | ~150 s | `./run-official-tck-data-1.0.sh --pg` |

Aucun fix dialect-spécifique n'a été nécessaire : le `PostgresqlDialect` couvrait déjà tout ce que les EntityTests exercent (`MERGE`/`INSERT … ON CONFLICT`, `RETURNING id`, `LIMIT/OFFSET`, types temporels, `IDENTITY`, etc.).

## Architecture du harness

```
┌──────────────────────────────────────────────┐
│  TCK officiel Jakarta Data 1.0 (TestNG)      │
│  ─ scenarios annotés @Repository, @Find, etc.│
└──────────────────────┬───────────────────────┘
                       │ Arquillian deployment
                       ▼
┌──────────────────────────────────────────────┐
│  Weld embedded (CDI 4.1)                     │
│  ─ découvre la BCE Mansart                   │
│  ─ crée les beans synthétiques pour chaque   │
│    @Repository scanné                        │
└──────────────────────┬───────────────────────┘
                       │ inject
                       ▼
┌──────────────────────────────────────────────┐
│  RepositoryImpl généré (mansart-data-processor) │
│  → RepositoryRuntime (mansart-data-core)     │
│  → DialectFactory ServiceLoader              │
│  → H2 in-memory                              │
└──────────────────────────────────────────────┘
```

## Roadmap

- **M6.1 — M6.4** ✅ livrés (voir tableau d'état ci-dessus).
- **M7-1 → M7-25** ✅ livrés — runtime impl generation + 73/73 PASS sur H2.
- **M6.5** ✅ livré — variante PostgreSQL via Testcontainers, 73/73 PASS sur PG.
- **TCK SignatureTests** ⏳ — réactiver l'inclusion `**/standalone/signature/SignatureTests.class` dans le profile `tck-run` ; nécessite `jimage.dir` et le harness signature TCK.
- **TCK PersistenceTests / NoSQLTests** ⏳ — bloqué par l'absence de `mansart-persistence` (JPA 3.2) et hors scope NoSQL en v1.
