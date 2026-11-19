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

---

## BENCH-20261111-01 — Transaction cycle: no reset rollback after a commit (Vidocq/noether#45)

- **Date**: 2026-11-11
- **Commit**: before `824bceeb` (`origin/main`, with `TransactionCycleBench.java` copied in); after `1e017bd4`
  (`perf/pool-reset-rollback`)
- **JVM**: Temurin 25.0.3+9-LTS (default flags)
- **Hardware**: Apple M5 Max / 18 cores (6 Super + 12 Performance) / 128 GB RAM
- **OS**: Darwin 27.2.0 arm64. Shared machine: load average 4.8 to 6.3 during the run (other sessions'
  builds); started after another session's JMH run released the JMH lock
- **Libraries**: H2 2.3.232 in-memory, HikariCP 6.2.1, JMH 1.37
- **Exact command**:
  ```bash
  ./mvnw -o -q -ntp -pl mansart-pool/mansart-pool-bench -am package -DskipTests
  # before: the jar built from origin/main; after: the jar of this branch
  java -jar mansart-pool/mansart-pool-bench/target/benchmarks.jar TransactionCycleBench \
       [-p pool=mansart] -f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc
  ```
- **What it measures**: one resource-local transaction per operation, as a JPA provider runs it: borrow,
  `setAutoCommit(false)`, one prepared statement, `commit()`, `close()`. The connection comes back out of
  auto-commit with nothing pending. `findByIdCommit` selects one row by key; `updateCommit` updates one.
- **Results**:
  ```
  before (origin/main)
  Benchmark                                                 (pool)  Mode  Cnt     Score     Error   Units
  TransactionCycleBench.findByIdCommit                     mansart  avgt   10  1340,751 ±  27,312   ns/op
  TransactionCycleBench.findByIdCommit:gc.alloc.rate.norm  mansart  avgt   10  6153,973 ±  31,873    B/op
  TransactionCycleBench.updateCommit                       mansart  avgt   10  1832,934 ± 144,563   ns/op
  TransactionCycleBench.updateCommit:gc.alloc.rate.norm    mansart  avgt   10  9260,250 ±  76,495    B/op

  after (perf/pool-reset-rollback)
  Benchmark                                                 (pool)  Mode  Cnt     Score     Error   Units
  TransactionCycleBench.findByIdCommit                     mansart  avgt   10   748,506 ±  16,203   ns/op
  TransactionCycleBench.findByIdCommit:gc.alloc.rate.norm  mansart  avgt   10  2745,971 ±   6,375    B/op
  TransactionCycleBench.findByIdCommit                      hikari  avgt   10   753,375 ±  36,522   ns/op
  TransactionCycleBench.findByIdCommit:gc.alloc.rate.norm   hikari  avgt   10  2757,981 ±   0,032    B/op
  TransactionCycleBench.updateCommit                       mansart  avgt   10  1329,176 ±  80,382   ns/op
  TransactionCycleBench.updateCommit:gc.alloc.rate.norm    mansart  avgt   10  6788,231 ±  25,499    B/op
  TransactionCycleBench.updateCommit                        hikari  avgt   10  1360,561 ±  56,993   ns/op
  TransactionCycleBench.updateCommit:gc.alloc.rate.norm     hikari  avgt   10  6892,249 ±  25,502    B/op
  ```
- **Delta vs before** (first run of this benchmark, so the comparison is with origin/main):
  - `findByIdCommit`: −44.2 % time (1340.8 → 748.5 ns/op), −55.4 % allocation (6154 → 2746 B/op, −3408 B).
  - `updateCommit`: −27.5 % time (1832.9 → 1329.2 ns/op), −26.7 % allocation (9260 → 6788 B/op, −2472 B).
  - vs HikariCP after the change: within the error bars on both (749 vs 753 ns/op, 1329 vs 1361 ns/op);
    Mansart allocates 12 B/op less on the read and 104 B/op less on the write.
- **Notes**: the gap was the reset rollback of a connection the client had already committed. On H2 it
  allocates and empties the session's statement cache, so the next transaction parses its statement again; the
  per-transaction saving here (2.5 to 3.4 kB) is of the order Noether measured through JPA (about 4.8 kB,
  noether-persistence/BENCH.md BENCH-20261111-02 on Vidocq/noether `perf/hot-paths`). The statement wrappers
  added by the change cost one small object per statement, included in the "after" figures. The pool still
  pays the `setAutoCommit(true)` that brings the connection back to auto-commit (one H2 commit). Quick profile
  (2 forks × 5 × 2 s) on a loaded shared machine: indicative, not for publication; run the `full` profile
  before quoting these figures outside this file.
