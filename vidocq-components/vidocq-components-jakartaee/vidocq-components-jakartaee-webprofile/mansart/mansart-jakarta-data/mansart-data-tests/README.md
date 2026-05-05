# mansart-data-tests

Tests d'intégration internes Mansart Data. **Pas un livrable** — utilisé pour valider le comportement bout-en-bout sur H2 (in-memory) et PostgreSQL (Testcontainers, profil optionnel).

## Couverture

| Suite | Description |
| --- | --- |
| `MetamodelGenerationTest` | Vérifie que l'APT produit `_<Entity>` correctement (table, colonnes, FK, MethodHandles). |
| `CrudIntegrationTest` | CRUD via repository généré : save, findById, findAll, count, existsById, deleteById, delete(entity), upsert MERGE. |
| `DerivedQueryTest` | Dérivation par nom de méthode (`findByX`, `findByXLike`, `existsByX`, `countByX`, `deleteByX`, `findAllByOrderByXAsc`). |
| `LifecycleAndVersionTest` | `@Insert`, `@Update`, `@Delete`, `@Save` typés + optimistic locking (`@Version`). |
| `PaginationTest`, `CursorPaginationTest`, `MultiAttributeCursorAndMixedInTest` | `Page`, `CursoredPage`, `PageRequest`, multi-attribut + `In(List)`. |
| `JdqlQueryTest`, `JdqlExtensionsTest`, `JdqlOrNotInTest`, `JdqlAggregateAndProjectionTest` | `@Query` JDQL (literal, parameters, IN, OR, NOT, agrégats, projections). |
| `InQueryTest` | Opérateur `In` côté dérivation. |
| `RuntimeRepositoryTest` | Voie M7 — `MansartData.runtimeRepository(itf)` quand l'APT n'a pas tourné. |
| `VirtualThreadsConcurrencyTest` | Charge avec 1000 lectures parallèles, vérifie l'absence de pinning (`-Djdk.tracePinnedThreads=full`). |
| `PostgresqlCrudIntegrationTest` | CRUD sur PostgreSQL via Testcontainers (profil `-Ppg-it`). |
| `BenchSmoke` | Mesure ronde-trip `findById` (sortie indicative — bench formel dans `BENCH.md`). |

## Lancement

```bash
# H2 in-memory uniquement
./mvnw -pl mansart-data-tests test

# + PostgreSQL via Testcontainers
./mvnw -pl mansart-data-tests -Ppg-it test
```

Les entités/repositories utilisés par les tests sont déclarés dans `src/main/java/io/vidocq/mansart/data/tests/` (`Author`, `Book`, `Article`, et leurs `*Repository`) — l'APT tourne dessus à la compilation du module.

## Résultats actuels

- **79/79 unit tests** ✅
- **6/6 smoke Arquillian** (incl. `RuntimeRepoArquillianTest` côté `mansart-data-tck`) ✅
