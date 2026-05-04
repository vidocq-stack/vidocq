# mansart-data-tck

Runner pour le **TCK officiel Jakarta Data 1.0** (`jakarta.data:jakarta-data-tck:1.0.1`).

## Pourquoi ce module est hors du reactor principal

Module en `modelVersion 4.0.0` standalone (sans `<parent>`). Raison documentée dans le `CLAUDE.md` racine du workspace : ShrinkWrap Maven Resolver 3.3 (transitive du TCK) ne sait pas parser les POMs Maven Model 4.1.0. Tant qu'upstream ShrinkWrap ne supporte pas Model 4.1, ce module reste détaché.

## État (M6 — structure posée)

| Brique | État |
| --- | --- |
| POM standalone Model 4.0.0 + deps Arquillian/TestNG/JUnit5 + TCK officiel | ✅ |
| Script `run-official-tck-data-1.0.sh` | ✅ |
| BCE Mansart (`mansart-data-cdi`) wire les `@Repository` dans Vauban | ✅ M6.1 |
| Connecteur Arquillian Vauban (porté depuis `vauban-tck-runner`) | ✅ M6.2 |
| Suite TCK officielle 1.0.1 — résolue depuis Maven Central + 73 EntityTests discovered | ✅ M6.3 |
| Wiring TCK entities (`NaturalNumbers`, `AsciiCharacters`…) via `TCKArchiveProcessor` | ✅ M6.2 (déjà actif via SPI auto-discovery du jar TCK) |
| TCK 1.0 — pass effectif (73 EntityTests en erreur, gap documenté) | 🚧 M7 — voir `BUG.md` BUG-20260505-01 |

**Blocage actuel** : le module `mansart-data-cdi` ne contient encore que le squelette `MansartDataCdi`. Pour que le TCK puisse découvrir les `@Repository` et les exposer comme beans CDI, il faut implémenter la `BuildCompatibleExtension` qui :

1. Lit `META-INF/mansart-repositories.list` (généré par `mansart-data-processor` — déjà au programme M3, à compléter).
2. Pour chaque interface `@Repository` listée, déclare un bean synthétique `@ApplicationScoped` typé sur l'interface.
3. La méthode `create` instancie `<Itf>Impl(RepositoryRuntime)` ; le `RepositoryRuntime` est lui-même un bean produit à partir d'un `DataSource` injecté.

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
# Smoke harness (Vauban + Mansart, 5 tests, défaut)
mvn test

# Suite TCK officielle (subset EntityTests pour le moment, M6.3)
mvn -Ptck-run test

# (script wrapper équivalent, ajoute aussi quelques garde-fous)
./run-official-tck-data-1.0.sh
```

Prérequis :
- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` du sous-projet ; `cd mansart-jakarta-data && sdk env`)
- TCK officiel installé en local (cf. ci-dessus)
- `mansart-jakarta-data` build et installé (`mvn install -DskipTests` depuis `mansart-jakarta-data/`)

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

- **M6.1** : implémenter le BCE (`mansart-data-cdi`) + faire passer un premier test smoke (Repository injecté, save/findById/count fonctionnent en CDI).
- **M6.2** : faire tourner la suite TCK officielle smoke (sous-ensemble) sur H2.
- **M6.3** : suite TCK complète + table de conformité documentée.
- **M6.4** : variante PostgreSQL (Testcontainers) — actuellement le harness ne cible que H2.
