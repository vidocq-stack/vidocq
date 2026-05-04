# mansart-data-tck

Runner pour le **TCK officiel Jakarta Data 1.0** (`jakarta.data:jakarta-data-tck:1.0.1`).

## Pourquoi ce module est hors du reactor principal

Module en `modelVersion 4.0.0` standalone (sans `<parent>`). Raison documentée dans le `CLAUDE.md` racine du workspace : ShrinkWrap Maven Resolver 3.3 (transitive du TCK) ne sait pas parser les POMs Maven Model 4.1.0. Tant qu'upstream ShrinkWrap ne supporte pas Model 4.1, ce module reste détaché.

## État (M6 — structure posée)

| Brique | État |
| --- | --- |
| POM standalone Model 4.0.0 + dépendances Arquillian/TestNG/Weld + TCK officiel | ✅ |
| Script `run-official-tck-data-1.0.sh` | ✅ |
| Harness Arquillian (Weld embedded + Mansart wiring) | 🚧 dépend de `mansart-data-cdi` (BCE en placeholder) |
| Suite TCK officielle 1.0.1 — exécution effective | 🚧 bloqué sur le BCE |

**Blocage actuel** : le module `mansart-data-cdi` ne contient encore que le squelette `MansartDataCdi`. Pour que le TCK puisse découvrir les `@Repository` et les exposer comme beans CDI, il faut implémenter la `BuildCompatibleExtension` qui :

1. Lit `META-INF/mansart-repositories.list` (généré par `mansart-data-processor` — déjà au programme M3, à compléter).
2. Pour chaque interface `@Repository` listée, déclare un bean synthétique `@ApplicationScoped` typé sur l'interface.
3. La méthode `create` instancie `<Itf>Impl(RepositoryRuntime)` ; le `RepositoryRuntime` est lui-même un bean produit à partir d'un `DataSource` injecté.

## Installation des artefacts TCK

L'artefact `jakarta.data:jakarta-data-tck:1.0.1` n'est pas distribué sur Maven Central. Il faut l'installer manuellement dans le M2 local depuis la distribution officielle Eclipse Foundation :

```bash
# 1. Télécharger jakarta-data-tck-1.0.1.zip depuis :
#    https://download.eclipse.org/jakartaee/data/1.0/

# 2. Décompresser et installer dans le M2 local
unzip jakarta-data-tck-1.0.1.zip
cd jakarta-data-tck-1.0.1
mvn install:install-file \
    -Dfile=artifacts/jakarta-data-tck-1.0.1.jar \
    -DgroupId=jakarta.data \
    -DartifactId=jakarta-data-tck \
    -Dversion=1.0.1 \
    -Dpackaging=jar
```

## Lancement

```bash
# Smoke test (suite réduite)
./run-official-tck-data-1.0.sh

# Suite complète
./run-official-tck-data-1.0.sh --all

# Test ciblé
./run-official-tck-data-1.0.sh -Dtest=NomDuTest
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
