# mansart-transactions — Plan

## Mission

Complete **Jakarta Transactions 2.0** implementation, but built up by standalone usable milestones:
1PC first (majority of apps), then 2PC + recovery (the rest). Virtual-thread-native from
the first line. Reuses `mansart-pool` for JDBC integration tests.

## Why a new project

- Narayana (RedHat) and Atomikos are massive, inherited from EE7 and EJB, design predates Loom.
- Bitronix is dead.
- None use `ScopedValue` — all rely on `ThreadLocal`, source of pinning under virtual
  threads as soon as we combine TX + JDBC.
- The Mansart need: a lightweight TM, which just does what `mansart-jakarta-data` /
  `mansart-persistence` consume, and which passes the official TCK.

## Modules

```
mansart-transactions/
├── mansart-transactions-api/        ← re-exposition jakarta.transaction-api
├── mansart-transactions-core/       ← TM/UT/TSR + 2PC + recovery log
├── mansart-transactions-cdi/        ← @Transactional + @TransactionScoped (Vauban BCE)
├── mansart-transactions-jdbc/       ← ConnectionXAResource — adapts a Connection to XAResource (1PC)
├── mansart-transactions-tests/      ← cross-module integration + real H2
└── mansart-transactions-tck/        ← official TCK — OUTSIDE reactor
```

## Roadmap

### M1 — Local single-thread (TM, UT, status)

`MansartTransactionManager.begin/commit/rollback`. `getStatus()` reflects the state
(`STATUS_ACTIVE`, `STATUS_MARKED_ROLLBACK`, `STATUS_NO_TRANSACTION`).
Context stored in `ScopedValue<TransactionContext>` re-assigned around each operation.
TDD tests: `TransactionManagerSmokeTest` (already written, red).

### M2 — Synchronizations

`Transaction.registerSynchronization(Synchronization)`. Respected calling order
(`beforeCompletion` BEFORE resource flush, `afterCompletion(int)` AFTER).
TDD tests: `SynchronizationOrderingTest`.

### M3 — Suspend / resume

`TransactionManager.suspend()` returns the current `Transaction` and clears the context;
`resume(Transaction)` re-installs it. Enables `TxType.REQUIRES_NEW` later.
TDD tests: `SuspendResumeTest` + parallel thread scenarios.

### M4 — Multi-resource (degenerate 2PC → true 2PC)

`Transaction.enlistResource(XAResource)`. In degenerate 1PC (1 resource), commit applies
directly without prepare. As soon as we enlist 2+ resources, we switch to true 2PC:
prepare/commit/rollback in two passes.
TDD tests: `TwoResourceCommitTest`, `TwoResourceRollbackOnPrepareTest`.

### M5 — Recovery log

Append-only disk journal (`tx-recovery.log`). On each prepare, we persist the intent.
On startup, scan + replay: commit or rollback according to log state.
TDD tests: `RecoveryAfterCrashIT` (kill -9 between prepare and commit).

### M5b — Auto-recovery driver-side *(delivered)*

- ✅ `MansartTransactionManager.recover(XAResource...)` — crosses in-doubt records from
  the Mansart journal with Xid returned by `XAResource.recover(TMSTARTRSCAN|TMENDRSCAN)`
  from each provided driver.
- ✅ COMMITTING + Xid present driver-side → `commit(xid, false)` (replay durable commit).
- ✅ PREPARED + Xid present driver-side → `rollback(xid)` (no durable decision).
- ✅ Xid absent driver-side → remains in `RecoveryReport.stillInDoubt` for human inspection.
- ✅ Tolerates `XAException` (driver scan or commit/rollback) — surfaces as still-in-doubt.
- ✅ Idempotent: a second call resolves what the first couldn't (drivers reconnected).
- ✅ 6 TDD tests: COMMITTING→commit, PREPARED→rollback, orphaned Xid, multi-drivers
  dispatch, COMPLETED ignored, no-resources fallback.

### M6 — Official Jakarta Transactions 2.0 TCK *(infra delivered)*

- ✅ `install-tck.sh`: auto-recovery from Eclipse Foundation, idempotent (verify/force/install).
- ✅ Java adapter (`MansartTckProvider`, `MansartUserTransaction`).
- ✅ Smoke wiring 5/5: `./run-official-tck-transactions-2.0.sh smoke` (launched in CI).
- ✅ **M6b**: Maven antrun wrapper for tsharness suite (profile `full-tck`). Mansart-friendly
  `ts.jte` templating (impl.vi=none, jta.classes pointing to M2), exec
  `ant build.all.tests` which compiles all TCK fixtures against Mansart classpath.
  `BUILD SUCCESSFUL` validated on all ~7 JTA EE directories + signature tests.
- ⏳ **M6c**: piloting each `ant runclient` leaf-by-leaf (~40 JTA EE tests) +
  parsing tsharness HTML report to summarize pass/fail in
  `target/tck-report-transactions.txt`. The CDI in M7 + JDBC in M8 already provide all
  the harness SUT-side — just need to iterate on ts.jte per leaf and collect.

### M7 — CDI interceptor + TransactionScoped *(delivered)*

- ✅ `MansartTransactionsProducer` (@ApplicationScoped) — produces `TransactionManager`,
  `UserTransaction`, `TransactionSynchronizationRegistry` from a shared singleton TM
  with portable extension.
- ✅ `TransactionalInterceptor` + 5 subclasses (`Required`/`RequiresNew`/`Mandatory`/`Never`/
  `NotSupported`/`Supports`) — six distinct bindings required because
  `Transactional.value()` is NOT `@Nonbinding` in jakarta.transaction-api 2.0.x. The logic
  stays in the parent; subclasses only carry binding + `@Priority`.
  Covers `rollbackOn` / `dontRollbackOn` (spec §3.7.1).
- ✅ `TransactionScopedContext` (`AlterableContext`) — instance per TX, destruction via
  `Synchronization.afterCompletion()`. Throws `ContextNotActiveException` outside TX.
- ✅ `MansartTransactionsExtension` (BCE) — registers `@TransactionScoped` via
  `MetaAnnotations.addContext(scope, isNormal, contextClass)` (standard CDI 4.1 API).
  Vauban natively honors this API (`VaubanMetaAnnotations` line 51), no need for
  legacy portable `Extension` nor Weld.
- ✅ Vauban tests (CDI 4.1 Lite, native Vidocq container) via `vauban-junit`:
  14 interceptor tests + 4 scope tests = 18/18 green.

### M8 — JDBC adapter `ConnectionXAResource` *(delivered)*

- ✅ `mansart-transactions-jdbc/ConnectionXAResource` — wraps a `java.sql.Connection`
  as `XAResource` (1PC only). Enables `@Transactional` to work with any
  simple `DataSource` (no need for `XADataSource`).
- ✅ `start()` flips `autoCommit=false`, `commit()/rollback()` delegate to JDBC,
  original `autoCommit` restored at cleanup.
- ✅ H2 in-memory tests: COMMIT persists, ROLLBACK cancels, pre-existing autoCommit honored.
- ✅ `H2SingleResourceCommitTest` — unblocked (was `@Disabled` since M2).
- ⏳ For true multi-resource 2PC: requires an `XADataSource` driver (out of scope
  for this module; the TM already handles the protocol).

## Out of scope (for now)

- **JTS** (Java Transaction Service / CORBA OTS) — Jakarta Transactions 2.0 deprecated the API
  related to CORBA protocol. No investment.
- **Cross-process distributed XA** — Mansart is in-process, 2PC stops at the JVM boundary.
  One JVM = one local coordinator.
- **Last-resource commit optimization** (LLR) — can be added in M4+ if needed.

## Inter-Mansart dependencies

- `mansart-transactions` → independent (peer of `mansart-pool` and `mansart-jakarta-data`).
- `mansart-jakarta-data` will consume `mansart-transactions-core` via standard SPI
  `TransactionSynchronizationRegistry` to participate in active TX (M3 of the
  `mansart-jakarta-data` plan).
- `mansart-pool` stays agnostic — it only exposes a `DataSource`. JDBC enlistment
  in an active TX is done by an `XADataSource` adapter wrapper we'll add to
  `mansart-transactions-core` (sub-module or utility).

## Conventions

- Zero reflection, zero runtime bytecode generation — Class-File API if ever necessary.
- Zero external dep except `jakarta.transaction-api`, `jakarta.cdi-api`, `jakarta.inject-api`,
  `jakarta.interceptor-api`, `jakarta.annotation-api`. No Apache Commons, no SLF4J.
- Integration tests on **real** H2 DB — no JDBC mocks.
- Reproducible bugs → `BUG.md`. Perf measurements → `BENCH.md`.
