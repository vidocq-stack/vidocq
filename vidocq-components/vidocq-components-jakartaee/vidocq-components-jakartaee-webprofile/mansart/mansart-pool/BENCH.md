# mansart-pool — BENCH.md

Any performance measurement landed in the doc/code/commit must have a corresponding entry
here, dated, with hardware, JVM, exact command and raw results (cf. workspace
`CLAUDE.md`).

---

## 2026-05-06 — MP-C-3: initial baseline vs HikariCP

**Goal**: have a reference point after MP-A/B/C-1/C-2. "Quick" run — indicative figures,
**not** publishable. For publishable figures, run the `full` profile (cf. below).

### Hardware / JVM

- macOS Darwin 25.4.0
- JDK 25 Temurin (`25-tem` via SDKMAN, `25+36-LTS`)
- Maven 3.9.16
- HikariCP 6.2.1
- JMH 1.37
- H2 2.3.232 (in-memory, `jdbc:h2:mem:bench-<UUID>;DB_CLOSE_DELAY=-1`)

### Command

```bash
cd mansart-pool
mvn -ntp -pl mansart-pool-bench package -DskipTests
java -jar mansart-pool-bench/target/benchmarks.jar -f 1 -wi 2 -w 1s -i 3 -r 1s
```

(`BenchRunner` `quick` profile equivalent: 1 fork, 2×1s warmup, 3×1s measurement.)

### Results

| Benchmark | pool | Mode | Score | Unit |
|---|---|---|---:|---|
| `BorrowReleaseBench.borrowRelease` | mansart | avgt | **86.6 ± 26.2** | ns/op |
| `BorrowReleaseBench.borrowRelease` | hikari  | avgt | **55.9 ±  1.8** | ns/op |
| `ConcurrentBorrowBench.borrowRelease` (8 threads, maxSize 4) | mansart | thrpt | **3.22 ± 1.60** | ops/µs |
| `ConcurrentBorrowBench.borrowRelease` (8 threads, maxSize 4) | hikari  | thrpt | **4.18 ± 9.45** | ops/µs |

### Quick analysis

- **Single-thread (no contention)**: HikariCP is ~35% faster. Expected: their `ConcurrentBag`
  + state machine are optimized to the cycle after ~10 years. Our `Semaphore + Deque` is
  honest but has an extra indirection (permit decrement + offerFirst on deque).
- **Concurrent 8 threads (forced contention)**: HikariCP ~30% faster. The gap narrows
  because Hikari and Mansart are both limited by the contended path (semaphore acquire on
  our side, internal lock-free on theirs).
- **Note**: JMH doesn't dispatch on virtual threads; the terrain where Mansart is designed to excel
  (massive contention under Loom) is **not** measured here. VT bench to do in a separate harness
  (Thread.ofVirtual() in loop outside-JMH) — TODO MP-D.
- Very wide errors (especially the ±9.4 on Hikari concurrent): direct consequence of the
  3-iteration run. Redo in `full` to stabilize.

### `full` profile (publishable figures)

```bash
java -jar mansart-pool-bench/target/benchmarks.jar -f 5 -wi 5 -w 3s -i 10 -r 3s
```

≈ 10 minutes wall-clock. This is the profile that must feed a new BENCH.md entry
before any external communication (README, blog, presentation).

### Out of scope for this baseline

- Real Postgres bench (Testcontainers) — JDBC overhead would totally dominate the pool.
- Virtual threads bench under massive contention (natural target of Mansart) — dedicated harness.
- Profile leak detection enabled vs disabled — overhead of `new Throwable()` to measure.
- Comparison vs Agroal.

These measurements will come in MP-D or later.
