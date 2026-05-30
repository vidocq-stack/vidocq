# mansart-transactions

**Jakarta Transactions 2.0** implementation for the Vidocq ecosystem — local first (1PC),
then multi-resource (2PC), virtual-thread-native, packaged in multiple lightweight jars.

## Modules

| Module | Role |
| --- | --- |
| `mansart-transactions-api` | Re-exposition `jakarta.transaction-api` 2.0 + Mansart SPI helpers. |
| `mansart-transactions-core` | `TransactionManager` / `UserTransaction` / `TransactionSynchronizationRegistry` — `ScopedValue` implementation. 1PC first, 2PC later. |
| `mansart-transactions-cdi` | CDI 4.1 bootstrap — `@Transactional` interceptor (REQUIRED, REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS) + `@TransactionScoped` scope, plug via `BuildCompatibleExtension`. |
| `mansart-transactions-tests` | H2 integration tests + `mansart-pool` (resource resolution, `Synchronization` callbacks, propagation behaviors). |
| `mansart-transactions-tck` | Official TCK runner — **OUTSIDE reactor** (standalone modelVersion 4.0.0, see root CLAUDE.md). |

## Build

```bash
cd mansart-transactions && sdk env
./mvnw -ntp install -DskipTests   # or: mvn -ntp install -DskipTests
mvn test                          # unit + integration tests
```

## Quick architecture

- **Transaction context** carried by `ScopedValue<TransactionContext>` — one binding per virtual
  thread. No `ThreadLocal`, no pinning.
- **Resources** dynamically enlisted via `Transaction.enlistResource(XAResource)`; in 1PC mode
  only one active resource at a time, prepare/commit degenerate to direct `commit()`.
- **Synchronizations** called at `beforeCompletion` (write-flush) and `afterCompletion`
  (cleanup, user callbacks).
- **2PC** (M4): disk recovery log, restart-safe, injected crash tests.

## Roadmap

- **M1** — `MansartTransactionManager`: `begin/commit/rollback`, `STATUS_ACTIVE` /
  `STATUS_NO_TRANSACTION`. TDD tests: `TransactionManagerSmokeTest`.
- **M2** — `Synchronization`: `registerSynchronization`, `beforeCompletion`,
  `afterCompletion(int status)`.
- **M3** — `suspend` / `resume` (TX inheritance via virtual threads).
- **M4** — Multi-resource: `enlistResource(XAResource)`, `delistResource(XAResource, int)`,
  2-phase prepare/commit/rollback.
- **M5** — Recovery log + crash tests.
- **M6** — Jakarta Transactions 2.0 TCK (smoke then full).
- **M7** — CDI: `@Transactional` interceptor, `@TransactionScoped` scope, Vauban BCE.

## TDD

Each milestone starts with tests describing the expected behavior, then the implementation
makes them pass one by one. The M1 seed is `mansart-transactions-core/src/test/java/.../TransactionManagerSmokeTest.java` —
it currently fails (skeleton) to bootstrap the red → green → refactor cycle.

## Vidocq integration

On the Vidocq Runtime side, activation is done via a single dependency — the
[`vidocq-runtime-mansart-transactions-extension`](https://forge.vidocq.dev/vidocq/vidocq/src/branch/main/vidocq-runtime-core-extensions/vidocq-runtime-mansart-transactions-extension)
extension which transitively pulls `mansart-transactions-cdi` and does a sanity-check
`TransactionManager` at boot (priority 250, between `mansart-pool` and `mansart-data`).
The application then only needs `requires jakarta.transaction;` in its `module-info.java`
to use `@Transactional` and `@TransactionScoped` — the reference example is
`vidocq-runtime-mansart-h2-example`.

## Bugs / Bench

- `BUG.md` — reproducible bugs (internal tracker).
- `BENCH.md` — Narayana / Atomikos / JBoss TM comparison (to come).
