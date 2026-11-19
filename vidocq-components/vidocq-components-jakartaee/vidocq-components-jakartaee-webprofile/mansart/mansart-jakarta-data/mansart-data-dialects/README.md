# mansart-data-dialects

Sub-reactor grouping the dialect SPI and shipped implementations.

| Module | Role |
| --- | --- |
| [`mansart-data-dialect-spi`](mansart-data-dialect-spi/) | Interfaces `Dialect`, `DialectFactory`, model `EntityModel`, `Attribute` (sealed), `Where`, `OrderBy`, `Pagination`, `SqlFragment`, `SqlNames`. |
| [`mansart-data-dialect-h2`](mansart-data-dialect-h2/) | H2 dialect (`provides DialectFactory with H2DialectFactory`). |
| [`mansart-data-dialect-postgresql`](mansart-data-dialect-postgresql/) | PostgreSQL dialect (`provides DialectFactory with PostgresqlDialectFactory`). |

## Discovery

`mansart-data-core` loads dialects via `ServiceLoader.load(DialectFactory.class)` then selects the first one whose `supports(DatabaseMetaData)` returns `true`. The result is cached per `DataSource`.

To add a custom dialect:

1. Implement `DialectFactory` + `Dialect`.
2. Declare `provides io.vidocq.mansart.data.dialect.DialectFactory with X.MyDialectFactory` in the `module-info.java`.
3. Add `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory` (classpath fallback).

## H2 vs PostgreSQL differences isolated in the dialect

| Aspect | H2 | PostgreSQL |
| --- | --- | --- |
| GENERATED KEY | `Statement.RETURN_GENERATED_KEYS` | `RETURNING id` |
| Upsert (`@Save`) | `MERGE INTO … KEY(…) VALUES …` | `INSERT … ON CONFLICT (…) DO UPDATE SET …` |
| Pagination offset | `LIMIT n OFFSET m` | identical |
| Pagination keyset | `WHERE (col1, col2) > (?, ?)` | identical (row constructor) |
| `LocalDateTime` | `TIMESTAMP` | `TIMESTAMP WITHOUT TIME ZONE` |
| Optimistic lock | `@Version` column | same |
| SQLState unique violation | `23505` | `23505` |

JDBC drivers in `<scope>provided</scope>` — the application brings them.
