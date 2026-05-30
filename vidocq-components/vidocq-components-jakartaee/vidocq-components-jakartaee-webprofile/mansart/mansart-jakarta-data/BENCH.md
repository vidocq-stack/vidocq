# BENCH.md — mansart-jakarta-data

Performance measurement history. Convention: see `../CLAUDE.md` (workspace root).
Any published figure (README, commit, post) must point to an entry here.

Procedure: uncomment the `@Disabled` in `mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/BenchSmoke.java`, then:

```bash
mvn -ntp install -DskipTests
mvn -ntp -pl mansart-data-tests test -Dtest='BenchSmoke#findByIdSingleThread+findByIdVirtualThreads'
```

Capture the `[BENCH] …` lines from stdout and create a new entry below.

> **Caveat**: this harness is a *smoke* test (single shot, not JMH). The figures serve to detect gross regressions (>20%) between commits, not to publish comparative benchmarks. A proper JMH harness will arrive in `mansart-data-bench` in M5+.

---

## BENCH-20260504-01 — findById on H2 in-memory (initial smoke M3a/M3b-1)

- **Date**: 2026-05-04
- **Commit**: `08d0d3b` (main, M3b-1 suite merged)
- **JVM**: Temurin OpenJDK 25 (build 25+36-LTS), `-XX:+UseG1GC` by default
- **Hardware**: Apple M4 Max / 16 cores / 128 GB RAM
- **OS**: Darwin 25.4.0 (macOS 26 / arm64)
- **Backend**: H2 2.3.232 in-memory via `JdbcConnectionPool.create(...)`, `maxConnections=64`
- **Schema**: table `authors (id BIGINT IDENTITY PK, name VARCHAR(200))` seeded with 10,000 rows
- **Exact command**:
  ```bash
  mvn -ntp install -DskipTests
  mvn -ntp -pl mansart-data-tests test -Dtest='BenchSmoke#findByIdSingleThread+findByIdVirtualThreads'
  ```
- **Results**:

  | Scenario | Ops | Wall-clock | µs/op | ops/s | Errors |
  | --- | ---: | ---: | ---: | ---: | ---: |
  | findById single-thread | 50,000 | 121.26 ms | **2.43 µs** | 412,336 | 0 |
  | findById 1,000 VTs × 200 ops | 200,000 | 558.44 ms | **2.79 µs** | 358,144 | 0 |

- **Comparison vs previous run**: *first run*.
- **Notes**:
  - Internal M3 plan target: `< 5 µs/op` excluding JDBC I/O. **Achieved** on H2 in-memory (which still includes full JDBC I/O — preparation, execution, mapping). Comfortable margin.
  - Single-thread vs 1,000 virtual threads: only +15% latency (2.43 → 2.79 µs/op) — the H2 pool (`maxConnections=64`) absorbs the 1,000 VTs without notable contention.
  - 0 errors on 200,000 concurrent reads ⇒ thread-safe stack under Loom.
  - No pinning observed (H2 `Connection` does not place a monitor on `Connection.close()` when returning to the pool, and `ScopedValue` does not introduce `synchronized` blocks).
  - To compare later with HikariCP / Mansart Pool: rerun the measurement with `-Djdk.tracePinnedThreads=full` and capture the output.
