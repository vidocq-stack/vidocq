# mansart-pool

A virtual-thread-native JDBC connection pool with zero third-party dependency.
**Delivered** (milestones MP1–MP5) and released on Maven Central as
`io.vidocq.mansart:mansart-pool-core` (API in `mansart-pool-api`, pulled transitively).

It is completely decoupled from the rest of Mansart: it only provides a
`javax.sql.DataSource`, usable by any JDBC client.

## Standalone

```java
import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.core.MansartDataSource;

var dataSource = MansartDataSource.of(PoolConfig.builder()
    .jdbcUrl("jdbc:postgresql://db.local:5432/shop")
    .username("shop").password("...")
    .minIdle(2).maxSize(10)
    .build());
```

## Documentation

- [Getting started](https://doc.vidocq.dev/mansart/0.2.0/getting-started) — first
  repository on H2, pool configuration, transactions.
- [Usage — Connection pool](https://doc.vidocq.dev/mansart/0.2.0/usage#connection-pool) —
  full `PoolConfig` surface, virtual-thread sizing guidance, leak detection.
- In a **Vidocq application**, the pool is configured declaratively through the
  `vidocq.pool.*` keys (default and named datasources) — see
  [Usage — Named datasources](https://doc.vidocq.dev/mansart/dev/usage#named-datasources-datastore-in-vidocq)
  and the [REST + database tutorial](https://doc.vidocq.dev/tutorials/0.2.0/rest-and-database).

Benchmarks live in `BENCH.md` at the repo root; the dialect-independent design
notes are in the main [Mansart docs](https://doc.vidocq.dev/mansart/0.2.0/).
