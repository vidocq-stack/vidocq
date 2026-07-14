# MANSART-007 — JTA ↔ mansart-data connection bridge (design)

**Goal**: `@Transactional` (and any active JTA transaction) governs mansart-data connections:
repository writes are committed/rolled back by the transaction outcome, with real XA two-phase
commit whenever the datasource supports it, multi-datasource included.

**Non-goals (phase 2, tracked in BUG.md MANSART-007 notes)**: XA-aware pooling in
`mansart-pool` / vidocq (`vidocq.pool.<name>.xa=true`, generated `XADataSource` holders),
boot-time recovery scan (`XAResource.recover()` reconciliation with `FileRecoveryLog`),
interceptor enhancement of dataStore-routed repository beans.

## Current state (why it is broken)

`RepositoryRuntime` runs every operation through `ConnectionScope.withConnection(dataSource, …)`,
which — when the programmatic `ConnectionScope.CURRENT` is not bound (always, in the CDI path) —
opens a fresh autocommit connection per operation. Nothing links it to the JTA transaction the
`@Transactional` interceptors manage, so a rollback does not undo repository writes.
`MansartTransactionManager` already implements 2PC + recovery log; `ConnectionXAResource`
(LRCO wrapper) exists but is only used by tests.

## Architecture

```
mansart-data-core          mansart-data-cdi                    mansart-transactions-*
─────────────────          ────────────────                    ──────────────────────
TransactionBridge (SPI) ←─ JtaTransactionBridge ──enlists──→   MansartTransaction (2PC)
ConnectionScope            (TM/TSR, XA-first,                  ConnectionXAResource
  consults bridge           ConnectionXAResource                 (+ SinglePhaseResource marker)
RepositoryRuntime           fallback)                          MansartTransaction commit:
  carries the bridge       JtaBridgeActivator                    single-phase resources first
MansartData.Builder          (Class.forName guards)
  .transactionBridge(…)    XaBackedDataSource (wrapper)
```

### 1. SPI in `mansart-data-core` (no new dependency)

```java
public interface TransactionBridge {
    /** Connection enlisted in the caller's active transaction for this datasource,
     *  or null when no transaction is active. Callers must NOT close it — the bridge
     *  owns its lifecycle (closed after transaction completion). */
    Connection connectionFor(DataSource dataSource);
}
```

- `MansartData.Builder.transactionBridge(TransactionBridge)` — optional, defaults to null.
- `RepositoryRuntime(dataSource, dialect, bridge)` — existing two-arg constructor delegates
  with `null` (source compatibility for direct `MansartData` users).
- `ConnectionScope.withConnection(TransactionBridge, DataSource, SqlAction)` — new overload;
  resolution order:
  1. `CURRENT` bound (existing programmatic `inTransaction`) → join, unchanged;
  2. `bridge != null` and `bridge.connectionFor(ds) != null` → run the action on that
     connection, **no close, no CURRENT bind** (each repository operation re-asks the bridge;
     the TSR lookup is O(1));
  3. otherwise → existing per-operation autocommit connection.
- `ConnectionScope.inTransaction` (programmatic local-TX API) is deliberately unchanged.

### 2. `JtaTransactionBridge` in `mansart-data-cdi`

State per (transaction × datasource), stored in the `TransactionSynchronizationRegistry`
(already transaction-scoped): `record Enlisted(Connection connection, AutoCloseable closer)`.

`connectionFor(ds)`:
1. `tm.getTransaction()` null or status not ACTIVE/MARKED_ROLLBACK → `null` (caller falls back
   to autocommit — outside-TX semantics unchanged).
2. `tsr.getResource(key(ds))` → reuse.
3. Else enlist, XA-first:
   - **Real XA** when the datasource is XA-capable — `ds instanceof XADataSource`, or
     `ds.isWrapperFor(XADataSource.class)`/`unwrap` (pools), or the datasource is our
     `XaBackedDataSource` wrapper: `xaDs.getXAConnection()` →
     `tx.enlistResource(xaConnection.getXAResource())` (driver XAResource: real prepare,
     recoverable in-doubt branches); operations run on `xaConnection.getConnection()`;
     `closer = xaConnection::close`.
   - **LRCO fallback** for a plain datasource: `ds.getConnection()` →
     `tx.enlistResource(new ConnectionXAResource(c))`; `closer = c::close`.
     A WARN is logged when a transaction enlists a **second** LRCO (non-XA) resource —
     one non-XA resource per TX is safe (last-resource commit), two or more are best-effort.
4. `tsr.putResource(key, enlisted)` + `tsr.registerInterposedSynchronization` whose
   `afterCompletion` closes the enlisted connection (the TM has already committed/rolled back
   through the XAResource; `ConnectionXAResource` restores autocommit itself).

Enlistment failures (`SQLException`, `RollbackException`, `SystemException`) surface as
`MansartDataException` — the operation fails inside the transaction, standard interceptor
handling marks it for rollback.

**Optional-dependency guards** — `mansart-data-cdi/module-info`: `requires static
jakarta.transaction; requires static io.vidocq.mansart.transactions.jdbc;`. Only
`JtaBridgeFactory` references those types; `JtaBridgeActivator.tryCreate(Instance<Object>)`
first probes `Class.forName("jakarta.transaction.TransactionManager")` and
`Class.forName("…jdbc.ConnectionXAResource")` and returns `null` when either is absent
(Weld TCK runs, TX-less deployments). When present, the factory resolves
`Instance<TransactionManager>`/`Instance<TransactionSynchronizationRegistry>`; if not
resolvable in CDI → `null` bridge (no TM extension installed).

### 3. Wiring (no application/vidocq change needed)

- `MansartRuntimeProducer.runtime(Instance<DataSource> defaultDs, Instance<Object> lookup)`
  → `builder.transactionBridge(JtaBridgeActivator.tryCreate(lookup))`. The extra parameter is
  `Instance<Object>` on purpose — a `Instance<TransactionManager>` parameter would make the
  producer signature unloadable when `jakarta.transaction` is absent.
- `DataStoreResolver.buildRuntime(lookup, key)` → same activator → routed repositories
  (`@Repository(dataStore = "x")`) join the caller's transaction too.
- `DataStoreResolver.cdiLookup` — the `@Named` XADataSource case stops being an error:
  when no `DataSource` bean matches but an `XADataSource` bean does, it is wrapped in
  `XaBackedDataSource` (`mansart-data-cdi`; `getConnection()` = `getXAConnection().getConnection()`
  for the outside-TX path, `isWrapperFor/unwrap(XADataSource.class)` = true so the bridge takes
  the real-XA route).

### 4. LRCO ordering in `MansartTransaction` (mansart-transactions-core/jdbc)

New marker interface `io.vidocq.mansart.transactions.core.SinglePhaseResource` (extends
`XAResource`), implemented by `ConnectionXAResource`. In the 2PC path of
`MansartTransaction.commit()`, after `STATUS_PREPARED` and **before** the `PREPARED`/`COMMITTING`
recovery records:

1. Commit every prepared `SinglePhaseResource` first (`commit(xid, false)` → local JDBC commit;
   its earlier `prepare` was a no-op XA_OK). Its commit is the de-facto decision point.
2. If a single-phase commit fails → **roll back the remaining prepared (real XA) resources**
   — they are still only prepared, rollback is clean — status `ROLLEDBACK`,
   `RollbackException`. This is the LRCO guarantee the current code lacks.
3. Then write `PREPARED`/`COMMITTING` records and commit the real XA resources as today
   (in-doubt branches recoverable from the log).

The 1PC single-resource path is already correct and stays unchanged.

## Semantics summary

| Situation | Behaviour |
|---|---|
| Outside any TX | Unchanged: one autocommit connection per operation |
| Inside a TX, XA-capable DS (H2 `JdbcDataSource`, PG `PGXADataSource`, wrapped) | One `XAConnection` per (TX × DS), real 2PC, recovery-log protected |
| Inside a TX, plain DS | One connection per (TX × DS), LRCO: committed first, XA rolled back if it fails; WARN at the 2nd plain DS in one TX |
| Programmatic `ConnectionScope.inTransaction` | Unchanged, takes precedence (`CURRENT` bound) |
| Routed (`dataStore="x"`) repositories | Join the caller's TX via the same bridge |
| No TM in the deployment | Bridge is null, everything behaves as today |

## Testing (TDD)

- `mansart-transactions-tests` — `LastResourceCommitOrderTest`: recording XAResource fixtures;
  asserts single-phase resources commit before real-XA commits, and that a failing single-phase
  commit rolls back the prepared XA resources (RollbackException). Red first (current code
  commits in enlistment order and escalates to SystemException/UNKNOWN).
- `mansart-data-tests` — `JtaTransactionBridgeTest` (Vauban SE container: mansart-transactions-cdi
  + mansart-data-cdi + H2 `JdbcDataSource` fixtures, which are XADataSource-capable):
  - `rollbackDiscardsRepositoryWrites` — `tm.begin(); repo.save(); tm.rollback()` → 0 rows
    (**red today**: the row survives);
  - `commitAppliesAllWrites` — two saves in one TX → 2 rows after commit, 0 before…
    (visibility asserted from a separate connection);
  - `interceptorRollback` — an `@Transactional` service method that saves then throws → 0 rows;
  - `multiDataSourceRollback` — one TX touching two XA datasources, rollback → 0 rows in both;
  - `routedRepositoryJoinsCallerTransaction` — `@Repository(dataStore = "…")` inside the
    caller's TX, rollback → 0 rows;
  - `outsideTransactionUnchanged` — save without TX → visible immediately.
- Non-regression: full mansart reactor, `mansart-transactions-cdi-jpms-it`, e2e
  `vidocq-runtime-mansart-h2-example` (module path), Jakarta Data TCK smoke if runnable.

## Files touched

| Repo/module | Change |
|---|---|
| mansart-data-core | `TransactionBridge` (new), `ConnectionScope` overload, `RepositoryRuntime` + `MansartData.Builder` carry the bridge |
| mansart-data-cdi | `JtaTransactionBridge`, `JtaBridgeActivator`, `JtaBridgeFactory`, `XaBackedDataSource` (new); `MansartRuntimeProducer`, `DataStoreResolver` wiring; module-info `requires static` |
| mansart-transactions-core | `SinglePhaseResource` (new), `MansartTransaction` LRCO commit ordering |
| mansart-transactions-jdbc | `ConnectionXAResource implements SinglePhaseResource` (+ `requires` core if missing) |
| mansart-data-tests / mansart-transactions-tests | new TDD suites above |

## Phase 2 — delivered (2026-07-14, same day)

- **XA through the pool** (`mansart-pool`): `PoolConfig.xaDataSourceClassName` — the pool
  exposes the driver's `XADataSource` through `unwrap`, built once by reflection
  (config-driven, no driver dependency). Pooled connections are unaffected; transactional XA
  connections are opened outside the pool, one per (transaction × datasource).
  Vidocq side: `vidocq.pool[.<name>].xa=true` auto-detects the class from the JDBC URL
  (H2 → `org.h2.jdbcx.JdbcDataSource`, PostgreSQL → `org.postgresql.xa.PGXADataSource`) and
  `vidocq.pool[.<name>].xaDataSourceClass` overrides it for any other driver.
- **Durable TM + boot-time recovery scan**: the `mansart.tx.recovery.log` system property makes
  `MansartTransactionsProducer` build a `FileRecoveryLog`-backed TM
  (`MansartTransactionManager.durable()`); the Vidocq mansart-transactions extension forwards
  `vidocq.tx.recovery.log` to it at `configure` time and, at `onStart`, collects the XAResource
  of every XA-capable `DataSource` bean and runs `MansartTransactionManager.recover(...)`,
  logging the `RecoveryReport` ("recovery scan clean" on a healthy boot).
- **True XA pooling of transactional connections** (a pool of `XAConnection`s) remains out of
  scope — transactional connections are per-transaction, which is correct and simple; revisit
  only if profiling shows enlistment cost matters.
