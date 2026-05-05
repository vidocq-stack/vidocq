# mansart-data-dialects

Sous-reactor regroupant la SPI dialecte et les implémentations livrées.

| Module | Rôle |
| --- | --- |
| [`mansart-data-dialect-spi`](mansart-data-dialect-spi/) | Interfaces `Dialect`, `DialectFactory`, modèle `EntityModel`, `Attribute` (sealed), `Where`, `OrderBy`, `Pagination`, `SqlFragment`, `SqlNames`. |
| [`mansart-data-dialect-h2`](mansart-data-dialect-h2/) | Dialecte H2 (`provides DialectFactory with H2DialectFactory`). |
| [`mansart-data-dialect-postgresql`](mansart-data-dialect-postgresql/) | Dialecte PostgreSQL (`provides DialectFactory with PostgresqlDialectFactory`). |

## Découverte

`mansart-data-core` charge les dialectes via `ServiceLoader.load(DialectFactory.class)` puis sélectionne le premier dont `supports(DatabaseMetaData)` retourne `true`. Le résultat est mis en cache par `DataSource`.

Pour ajouter un dialecte custom :

1. Implémenter `DialectFactory` + `Dialect`.
2. Déclarer `provides io.vidocq.mansart.data.dialect.DialectFactory with X.MyDialectFactory` dans le `module-info.java`.
3. Ajouter `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory` (fallback classpath).

## Différences H2 vs PostgreSQL isolées dans le dialecte

| Aspect | H2 | PostgreSQL |
| --- | --- | --- |
| GENERATED KEY | `Statement.RETURN_GENERATED_KEYS` | `RETURNING id` |
| Upsert (`@Save`) | `MERGE INTO … KEY(…) VALUES …` | `INSERT … ON CONFLICT (…) DO UPDATE SET …` |
| Pagination offset | `LIMIT n OFFSET m` | identique |
| Pagination keyset | `WHERE (col1, col2) > (?, ?)` | identique (row constructor) |
| `LocalDateTime` | `TIMESTAMP` | `TIMESTAMP WITHOUT TIME ZONE` |
| Optimistic lock | colonne `@Version` | idem |
| SQLState unique violation | `23505` | `23505` |

Drivers JDBC en `<scope>provided</scope>` — l'application les apporte.
