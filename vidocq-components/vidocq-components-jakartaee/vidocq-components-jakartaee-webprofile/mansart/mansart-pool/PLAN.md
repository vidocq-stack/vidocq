# mansart-pool — Plan

JDBC connection pool **virtual-thread-native**, **zero dependency**, independent from other Mansart modules.

## Why

HikariCP, Agroal and c3p0 were designed for platform-thread models:
- HikariCP suffered for a long time from pinning under virtual threads (`synchronized` block on `ConcurrentBag`) — fixed but inherited from pre-Loom design.
- Agroal is better but still tied to WildFly/Quarkus.
- None use `ScopedValue` to propagate connection in a transaction.

Mansart Pool is designed **post-Loom** from the first line: no `synchronized` around blocking operation, `ScopedValue` for context, `StructuredTaskScope` for housekeeping. Serves Mansart needs first (tests, examples, default deployment), stays usable standalone by any JDBC app.

## Positioning in the workspace

**Peer** sub-project of `mansart-jakarta-data` and `mansart-persistence` — not a sub-module. Independent Maven reactor, separate deployment.

```
mansart/
├── mansart-jakarta-data/     ← consumes a standard DataSource (doesn't matter which)
├── mansart-persistence/      ← same
└── mansart-pool/             ← PROVIDES a DataSource (usable outside Mansart)
```

No Maven dependency link between the three — strict decoupling. `mansart-jakarta-data/mansart-data-tests` can **optionally** depend on `mansart-pool` in `<scope>test</scope>` for integration tests.

## Modules

```
mansart-pool/
├── pom.xml                          ← mansart-pool reactor
├── mansart-pool-api/                ← PoolConfig, PoolMetrics, PoolException (zero dep except java.sql)
├── mansart-pool-core/               ← MansartDataSource (impl javax.sql.DataSource)
└── mansart-pool-tests/              ← unit + JMH micro-bench
```

## Public API (mansart-pool-api)

```java
public final class MansartPool {
    public static MansartDataSource of(PoolConfig config) { … }
}

public record PoolConfig(
    String  jdbcUrl,
    String  username,
    String  password,
    int     minIdle,                  // default 0 (truly elastic)
    int     maxSize,                  // default 10
    Duration acquireTimeout,          // default 5s
    Duration idleTimeout,             // default 10min
    Duration maxLifetime,             // default 30min
    Duration validationTimeout,       // default 1s
    ValidationMode validation,        // NEVER | ON_BORROW | PERIODIC (default: ON_BORROW if validationQuery != null)
    String  validationQuery,          // null = uses Connection.isValid()
    Duration leakDetectionThreshold,  // default DISABLED
    Map<String,String> driverProperties
) {
    public static Builder builder() { … }
}

public sealed interface PoolMetrics {
    int active();
    int idle();
    int waiting();
    long totalBorrows();
    long totalTimeouts();
    Duration meanBorrowDuration();
    // … immutable snapshot
}
```

## Runtime architecture

### Structure

```
MansartDataSource (impl DataSource)
    │
    ├── Deque<PooledConnection> idle           (ConcurrentLinkedDeque, lock-free)
    ├── Set<PooledConnection>   inUse          (ConcurrentHashMap.newKeySet)
    ├── Semaphore               permits        (size = maxSize, fair=false)
    ├── ScopedValue<Connection> CURRENT        (to propagate in a tx)
    └── ScheduledTask           housekeeper    (virtual thread, StructuredTaskScope)
```

### Acquisition (`getConnection()`)

```
1. permits.tryAcquire(acquireTimeout)        ← block virtual thread, never synchronized
   ↓ timeout → PoolException(ACQUIRE_TIMEOUT)
2. PooledConnection pc = idle.pollFirst()    ← lock-free
   if pc != null && validate(pc):
       inUse.add(pc); return pc.proxy()
3. pc = createNew()                          ← driver.connect(...)
   inUse.add(pc); return pc.proxy()
```

**No `synchronized` on hot path.** The `Semaphore` is the only blocking point and it's virtual-thread-friendly (uses `LockSupport.park`, not a native mutex).

### Return (`Connection.close()` on proxy)

```
1. pc.reset()                                ← rollback if dirty, autoCommit=true, default isolation
2. inUse.remove(pc)
3. if pc.aliveAndYoung(): idle.offerFirst(pc)
   else: pc.realClose()
4. permits.release()
```

The proxy is generated at build via **Class-File API (JEP 484)** — **NOT** via `java.lang.reflect.Proxy` (Vidocq philosophy). A single `PooledConnectionProxy` class that implements `Connection` and delegates to `pc.delegate`, intercepting `close()` to call the return.

### Housekeeper

Virtual thread started at `MansartDataSource.start()`:
- Every `idleTimeout / 4`, scans `idle` and closes connections inactive for too long.
- Every `maxLifetime / 4`, closes connections that exceeded their lifetime.
- If `leakDetectionThreshold` enabled, scans `inUse` and logs (System.Logger WARNING) connections borrowed for too long with their borrow stack trace.

### `ScopedValue` integration for transactions

```java
public static <T> T inTransaction(MansartDataSource ds, SqlCallable<T> body) throws SQLException {
    Connection c = ds.getConnection();
    c.setAutoCommit(false);
    try {
        T result = ScopedValue.where(MansartPool.CURRENT, c).call(body);
        c.commit();
        return result;
    } catch (Throwable t) {
        c.rollback(); throw t;
    } finally {
        c.close();   // return to pool
    }
}

// In user code, anywhere in child virtual thread:
Connection c = MansartPool.CURRENT.orElseGet(() -> ds.getConnection());
```

`mansart-jakarta-data/RepositoryRuntime` can use this `ScopedValue` when `mansart-pool` is present (detection via `ServiceLoader<TransactionContextProvider>`), and fall back to auto-commit mode otherwise.

## Design decisions

| Choice | Justification |
| --- | --- |
| `Semaphore` instead of `BlockingQueue` | Separates quota management from connection queue; allows on-demand creation. |
| `ConcurrentLinkedDeque` LIFO for `idle` | Reuses hottest connection (CPU cache + driver JIT). |
| Proxy via Class-File API | No `Proxy.newProxyInstance` (runtime reflection); AOT-compatible. |
| No JTA in v1 | Out of scope. If XA needed, Mansart Pool will be enriched in v1.1 or delegated to external pool. |
| Default validation = `Connection.isValid(1)` | JDBC 4 standard, no magic query. Override possible. |
| `System.Logger` (not SLF4J) | Zero-dep + Java Modules-friendly. |
| No prepared statement pool | Modern drivers (PG, H2) do it server-side or driver-side. Keep pool simple. |

## Work plan (milestones)

### MP1 — API + skeleton (week A)
- [ ] Parent `pom.xml` + 3 sub-modules.
- [ ] `module-info.java` for each.
- [ ] `mansart-pool-api`: `PoolConfig`, `PoolConfig.Builder`, `PoolMetrics`, `PoolException`, `ValidationMode`.
- [ ] JUnit 6 tests on builder + config validations (min/max consistency, positive durations, etc.).

### MP2 — Core implementation (week B)
- [ ] `MansartDataSource`: acquisition/return, semaphore, deque, `Connection.isValid()` validation.
- [ ] `PooledConnectionProxy` proxy generated by Class-File API at compile-time (within `mansart-pool-core` module, via custom Maven goal — or postponable to v1.1 and meanwhile a `record`-based wrapper implementing all `Connection` methods by hand).
- [ ] Virtual thread housekeeper.
- [ ] Tests: 1000 parallel borrows on H2 in-memory, verify 0 pinning (`-Djdk.tracePinnedThreads=full`), 0 timeout.

### MP3 — Metrics + leak detection (week C)
- [ ] Live `PoolMetrics` snapshot.
- [ ] Leak detector + structured log.
- [ ] JMH bench: comparison vs HikariCP (acquisition latency p50/p99, throughput under virtual threads).
- [ ] Initial `BENCH.md` entry.

### MP4 — mansart-jakarta-data integration (week D)
- [ ] In `mansart-jakarta-data/mansart-data-tests`, add a `-Pmansart-pool` profile using `MansartDataSource` instead of raw `JdbcDataSource`.
- [ ] `TransactionContextProvider` SPI in `mansart-data-core`, `MansartPoolTransactionContext` implementation in `mansart-pool-core`, ServiceLoader discovery.

## Open decisions (before MP1)

1. **`Connection` proxy generation**: Class-File API at build (clean but heavy to set up for a single proxy) **or** hand-written class implementing ~50 `java.sql.Connection` methods (verbose but simple, no Maven plugin)? Proposal: by hand for MP2, migrate to Class-File API in MP3 if we generate several (proxies for `PreparedStatement`, `Statement`, `ResultSet`).
2. **Driver loading**: direct `Driver.connect(url)` (goes through `DriverManager`) or require an `XADataSource`/`ConnectionPoolDataSource`? Proposal: direct `Driver.connect()` in v1, no XA.
3. **Configuration via `vidocq.properties`?**: if `vidocq` (orchestration sub-project) is present, do we read config from its properties? Proposal: no, no coupling. `mansart-pool` stays usable outside Vidocq. A `vidocq-runtime-mansart-pool` extension can bridge later.

→ These decisions don't need to be settled right away — they concern the pool phase, which starts **after** M3 of `mansart-jakarta-data` (we need the H2 dialect to test seriously).
