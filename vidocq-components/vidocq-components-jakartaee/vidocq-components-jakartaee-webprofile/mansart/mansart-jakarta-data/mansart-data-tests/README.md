# mansart-data-tests

Internal Mansart Data integration tests. **Not a deliverable** — used to validate end-to-end behavior on H2 (in-memory) and PostgreSQL (Testcontainers, optional profile).

## Coverage

| Suite | Description |
| --- | --- |
| `MetamodelGenerationTest` | Verifies that the APT produces `_<Entity>` correctly (table, columns, FK, MethodHandles). |
| `CrudIntegrationTest` | CRUD via generated repository: save, findById, findAll, count, existsById, deleteById, delete(entity), MERGE upsert. |
| `DerivedQueryTest` | Derivation by method name (`findByX`, `findByXLike`, `existsByX`, `countByX`, `deleteByX`, `findAllByOrderByXAsc`). |
| `LifecycleAndVersionTest` | Typed `@Insert`, `@Update`, `@Delete`, `@Save` + optimistic locking (`@Version`). |
| `PaginationTest`, `CursorPaginationTest`, `MultiAttributeCursorAndMixedInTest` | `Page`, `CursoredPage`, `PageRequest`, multi-attribute + `In(List)`. |
| `JdqlQueryTest`, `JdqlExtensionsTest`, `JdqlOrNotInTest`, `JdqlAggregateAndProjectionTest` | JDQL `@Query` (literal, parameters, IN, OR, NOT, aggregates, projections). |
| `InQueryTest` | `In` operator on derivation side. |
| `RuntimeRepositoryTest` | M7 path — `MansartData.runtimeRepository(itf)` when APT hasn't run. |
| `VirtualThreadsConcurrencyTest` | Load with 1000 parallel reads, verifies no pinning (`-Djdk.tracePinnedThreads=full`). |
| `PostgresqlCrudIntegrationTest` | CRUD on PostgreSQL via Testcontainers (profile `-Ppg-it`). |
| `BenchSmoke` | Measures `findById` round-trip (indicative output — formal bench in `BENCH.md`). |

## Launch

```bash
# H2 in-memory only
./mvnw -pl mansart-data-tests test

# + PostgreSQL via Testcontainers
./mvnw -pl mansart-data-tests -Ppg-it test
```

The entities/repositories used by the tests are declared in `src/main/java/io/vidocq/mansart/data/tests/` (`Author`, `Book`, `Article`, and their `*Repository`) — the APT runs on them at module compilation.

## Current results

- **79/79 unit tests** ✅
- **6/6 smoke Arquillian** (incl. `RuntimeRepoArquillianTest` on `mansart-data-tck` side) ✅
