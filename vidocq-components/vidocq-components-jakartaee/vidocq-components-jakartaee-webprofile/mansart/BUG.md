> Persistence and Validation were extracted to [Noether](https://codefloe.com/Vidocq/noether)
> on 2026-10-11. Their entries below are historical Mansart evidence;
> original commands, names and revisions are retained, not new Noether results.

# Mansart — Bug tracker

Reproducible bugs in Mansart (Jakarta Data 1.0 + Persistence 3.2). Format per workspace CLAUDE.md:
short id, date, symptom, minimal repro, cause hypothesis, status. Update on each investigation.

---

## MANSART-030 — A repository method no query derives from compiles, then answers 500

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/derived-query-null-keyword`
- **Severity**: medium (the mistake is only seen when the method is first called)
- **Symptom**: a method name the processor cannot derive (an unsupported keyword, a misspelled attribute) compiles
  with a warning, and the generated implementation throws _UnsupportedOperationException: … no matching CRUD or
  derived-query rule_ at run time. Reported by the vidocq-tutorial session (batch 4).
- **Minimal reproduction**: `List<Loan> findByMembr(String member);` on a `BasicRepository<Loan, Long>`.
- **Cause**: MANSART-002 made the processor report the method as a WARNING, not an ERROR, because one test fixture
  (`TransactionalArticleRepository.findByTitleSensitive`) relies on the stub.
- **Fix**: an ERROR by default, since the implementation is generated at build time; the processor option
  `-Amansart.unresolvedMethods=warn` keeps the warning and the stub, and `mansart-data-tests` sets it for its
  fixture. `ProcessorDerivedQueryDiagnosticsTest`.

## MANSART-029 — The Jakarta Data `Null` keyword is not supported in derived queries

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/derived-query-null-keyword`
- **Severity**: medium (a spec keyword; the workaround is a `@Query`)
- **Symptom**: `List<Loan> findByMemberAndReturnedOnNull(String member);` throws _UnsupportedOperationException:
  findByMemberAndReturnedOnNull: no matching CRUD or derived-query rule_. Reported by the vidocq-tutorial session
  (batch 4), worked around with `@Query("where member = :member and returnedOn is null")`.
- **Cause**: `QueryMethodParser` knew `IsNull`, `IsNotNull` and `NotNull`, not `Null`, the keyword of the Jakarta
  Data 1.0 "Query by Method Name" table.
- **Fix**: `Null` is a suffix of `IS_NULL`. `ProcessorDerivedQueryDiagnosticsTest`, and `GadgetQueriesTest` on
  both the generated and the run-time repository.

## MANSART-008 — The annotation processor ignored `@Param`, did not compile `@Find`, and required `FROM`

- **Date**: 2026-10-18
- **Status**: FIXED on `fix/41-param-find-from` (mansart#41)
- **Module**: `mansart-data-processor` (`JdqlParser`, `RepositoryWriter`)
- **Symptom**: in a repository compiled by the APT (from sources or, through `vidocq:generate`, from class files):
  `@Query("… :title") byTitle(@Param("title") String t)` failed with "has no parameter with that name"; a `@Find`
  method got a warning and a body throwing `UnsupportedOperationException`; `@Query("WHERE title = :title")` failed
  with `Expected FROM, got: WHERE`.
- **Minimal reproduction**: `JdqlQueryTest` (`paramAnnotationNamesTheParameter`, `queryWithoutFromTargetsThePrimaryEntity`,
  `findMatchesParametersToAttributes`, `findWithoutParametersHonoursOrderBy`) with the matching `AuthorRepository` methods:
  the first two fail the compilation, the `@Find` ones throw.
- **Cause**: the processor's parser mapped `:name` to Java parameter names only, required `FROM`, and had no `@Find`
  rule. The run-time path (`RuntimeRepositoryProxy`, `JdqlAst`) already handled all three.
- **TCK**: the Jakarta Data TCK runner (73/73 + signature) generates its repositories at run time, so it never ran the
  processor on these shapes; it stays 73/73 with the fix.
- **Fix**: `JdqlParser.queryParameterName` reads `@Param`; `FROM` is optional in the processor's grammar; a `@Find` method
  declared by the application becomes the JDQL `WHERE a = ?1 AND …` from `@By` (else the parameter name) and
  `@OrderBy`, then goes through the `@Query` path (a trailing `PageRequest` included; `Sort`, `Order` and `Limit`
  parameters warn). The processor's warnings name the type and the method.

---

## MANSART-006 — `@TransactionScoped` beans empty on the module path (context never installed)
- **Date**: 2026-07-14 — **Status**: FIXED (root cause upstream in Vauban, VAU-CTX-001)
- **Severity**: high (any `@TransactionScoped` state silently lost — one fresh instance per proxy call)

### Symptom
In `vidocq-runtime-mansart-h2-example`, `ProductResource.create` (`@Transactional`) records an
entry in the `@TransactionScoped` `OperationAudit` bean and reads it back in the same method —
and gets an EMPTY list ("TX audit: []"), silently.

### Cause
Upstream Vauban VAU-CTX-001, two silent fallbacks: on the strict module path the
`TransactionScopedContext` registered by `MansartTransactionsExtension`
(`MetaAnnotations.addContext`) could not be instantiated (private-lookup only, no `opens`,
and the context class is not part of the APT-generated `_VaubanComponents` — the module-info
comment claiming otherwise was aspirational); the failure was swallowed, and the client-proxy
delegate then fell back to per-call `@Dependent` instances instead of throwing
`ContextNotActiveException`.

### Fix / verification
Vauban VAU-CTX-001 (public-constructor path for BCE contexts + loud failures). Mansart side:
new regression coverage in `mansart-transactions-cdi-jpms-it`
(`transaction_scoped_context_works_on_the_module_path`, red before / green after) with a
`TxScopedCounter` fixture; the IT's processor path now includes `mansart-transactions-cdi` so
the APT sees the BCE's `@Enhancement(withAnnotations = TransactionScoped.class)` trigger and
generates the fixture's factory + client proxy (same wiring the vidocq extension codegen
bundles give applications). E2E: the example now logs "TX audit: [created product id=N]".

## MANSART-007 — `@Transactional` does not govern mansart-data connections (no TX↔data bridge)
- **Date**: 2026-07-14 — **Status**: FIXED
- **Severity**: high conceptually (rollback semantics), latent in practice (no reported data loss)

### Symptom
A JTA rollback does not undo repository writes. `RepositoryRuntime` always runs each operation
on its own autocommit connection (`ConnectionScope.withConnection`, `CURRENT` unbound), whether
or not the caller is inside a `@Transactional` method.

### Cause
There is no bridge between mansart-transactions (JTA `TransactionManager`, used by the
`@Transactional` interceptors and `@TransactionScoped`) and mansart-data's `ConnectionScope`:
nothing binds `ConnectionScope.CURRENT` when a JTA transaction starts, and
`ConnectionXAResource` (mansart-transactions-jdbc) is only exercised by tests. `@Transactional`
on repository interfaces currently provides interception and TX lifecycle but not connection
enlistment.

### Notes
Related: dataStore-routed repositories (MANSART-005 path) are instantiated by
`MansartRepoCreator` and are not interceptor-enhanced at all — once the bridge exists, routed
repositories must also join it. Design sketch: a per-(transaction × datasource) connection
holder registered via `TransactionSynchronizationRegistry`, bound to `ConnectionScope.CURRENT`
(or consulted by `withConnection`), committed/rolled back by a `Synchronization` — or full XA
via `ConnectionXAResource` for multi-resource TX.

### Fix / verification (2026-07-14)
Design: `docs/superpowers/specs/2026-07-14-mansart-007-tx-data-bridge-design.md`. Delivered:
- **data-core** — `TransactionBridge` SPI (no TX dependency), consulted by
  `ConnectionScope.withConnection` between the programmatic `CURRENT` and the autocommit path;
  carried by `RepositoryRuntime` / `MansartData.Builder.transactionBridge`.
- **data-cdi** — `JtaTransactionBridge`: one connection per (transaction × datasource) stored in
  the TSR, enlisted XA-first (`instanceof`/`unwrap` `XADataSource` → the driver's XAResource,
  real 2PC) with a `ConnectionXAResource` LRCO fallback for plain datasources (WARN at the 2nd
  plain resource in one TX); closed by an interposed `Synchronization` after completion.
  `JtaBridgeActivator`/`JtaBridgeFactory` gate the optional dependencies (`requires static
  jakarta.transaction` + `mansart-transactions-jdbc`) so TX-less deployments are untouched.
  Wired automatically in `MansartRuntimeProducer` AND `DataStoreResolver` (routed repositories
  join the caller's transaction — the MANSART-005 note above is addressed for the semantics
  that matter; interceptor enhancement of routed beans remains cosmetic). A `@Named` bean
  exposed only as `XADataSource` is now adapted (`XaBackedDataSource`) instead of rejected.
- **transactions-core/jdbc** — `SinglePhaseResource` marker + LRCO ordering in
  `MansartTransaction.commit()`: single-phase resources commit first (after the XA prepares,
  before the COMMITTING record); if one fails, the prepared XA branches are rolled back and the
  caller gets `RollbackException` (previously: commit in enlistment order, heuristic UNKNOWN).

Verified (TDD, red first): `LastResourceCommitOrderTest` (ordering + rollback-on-failure),
`JtaTransactionBridgeTest` 9/9 (rollback/commit via TM and via `@Transactional` interceptor,
multi-datasource mix LRCO+XA, routed repository, XA-only named bean, outside-TX unchanged);
full mansart reactor green; official Jakarta Data TCK EntityTests 73/73; e2e
`vidocq-runtime-mansart-h2-example` on the module path unchanged and functional.

**Phase 2 — DELIVERED (2026-07-14)**: `PoolConfig.xaDataSourceClassName` exposes the driver's
`XADataSource` through the pool's `unwrap` (vidocq: `vidocq.pool[.<name>].xa=true`,
auto-detected from the URL for H2/PostgreSQL, `xaDataSourceClass` override); the vidocq pools
now take the real-XA path. Durable TM via the `mansart.tx.recovery.log` system property
(vidocq: `vidocq.tx.recovery.log`) + boot-time recovery scan in the vidocq
mansart-transactions extension (`MansartTransactionManager.recover()` over every XA-capable
`DataSource` bean, `RecoveryReport` logged). Tests: `XaDataSourceExposureTest` (pool),
`DurableTmBootstrapTest` (producer), vidocq `MansartPoolExtensionTest` xa cases; e2e boot log
shows "durable recovery journal" + "recovery scan clean". Still out of scope: pooling of the
transactional `XAConnection`s themselves (per-transaction connections are correct and simple).

**XA branch identity (2026-07-14, follow-up)**: the TM handed the SAME Xid to every enlisted
resource (bqual frozen at `{0}`) — non-conformant, and `XAER_DUPID` waiting to happen when two
datasources reach the same resource manager. Each enlisted resource now gets its own branch
(same gtrid, distinct bqual); every per-branch call (end/prepare/commit/rollback) reuses the
branch's own Xid, and the recovery reconciliation matches journal records against driver
branches by GLOBAL transaction id (a driver may hold several branches of one transaction,
spread over several drivers — all are resolved). Regression: `BranchXidTest` (red→green),
`JtaTransactionBridgeTest.twoRealXaBranches*` (two real XA branches commit/roll back
atomically).

## MANSART-005 — `@Repository(dataStore = "name")` routing silently lost when the APT impl is a managed bean
- **Date**: 2026-07-14 — **Status**: FIXED
- **Severity**: high (multi-datasource repositories silently read/write the WRONG database)

### Symptom
In `vidocq-runtime-mansart-h2-example`, `GET /api/audit` → 500 `Table "audit_log" not found`:
`AuditEntryRepository` (`@Repository(dataStore = "audit")`) queries the **default** H2 database
instead of the `@Named("audit")` one. Direct injection (`@Inject @Named("audit") DataSource`)
works — only the repository path mis-routes.

### Minimal repro
```
# example on module path, two H2 in-memory pools (default + "audit")
cd vidocq/vidocq-runtime-examples/vidocq-runtime-mansart-h2-example && ../../mvnw vidocq:dev
curl localhost:8080/api/audit   # 500, SELECT runs on jdbc:h2:mem:mansart-vidocq-demo
```

### Cause
Two compounding facts on the compile-time path:
1. `mansart-data-processor` generates `*RepositoryImpl` as `@Singleton` with an **unqualified**
   `@Inject *(RepositoryRuntime runtime)` constructor — that always resolves the `@Default`
   runtime (default `DataSource`), no matter what `dataStore` says.
2. `MansartDataExtension.@Synthesis` **skips** the synthetic routing bean when the impl is
   already a managed bean (`isAlreadyManagedBean`) — so `DataStoreResolver` (the only code that
   honors `dataStore`) is **never invoked** (verified by instrumentation: no `resolve()` call at
   runtime).

Not a July regression: bisected back to vauban@`eefc6ea` + vidocq@`ab1b6bd` (2026-06-17, the
day after the feature demo commit) — already broken. The June E2E validation demonstrably
covered the direct `@Named` injection paths (`/db/audit`), not the repository routing.

### Investigations
- 2026-07-14: full bisection (vauban pre/post TCK campaign, vidocq June state) — rules out the
  Core Profile 11 / assembled-runtime TCK campaigns. Fix direction: veto the managed impl when
  `dataStore` is non-default (`@Enhancement` + `@Vetoed`, same idiom as ravel's
  `ConfigCdiExtension`) and register the synthetic bean unconditionally for those repositories,
  so `MansartRepoCreator`/`DataStoreResolver` route as designed.

### Fix / verification
Two-sided fix:
1. **mansart-data-cdi** — `MansartDataExtension` vetoes (`@Enhancement` + `@Vetoed`) any bean
   class implementing a `@Repository(dataStore = "name")` interface (found by reflection — the
   index-backed lang model returns no super-interfaces for pre-indexed module-path classes),
   and `@Synthesis` registers the routing synthetic bean unconditionally for those interfaces.
   The default-dataStore path is untouched (managed impl keeps winning, `@Transactional`
   interception included). Note: routed repositories are instantiated by `MansartRepoCreator`
   and are NOT interceptor-enhanced (pre-existing property of the synthetic path).
2. **vauban** — `EnhancementApplier.applyEnhancements` now drops a bean descriptor whose
   enhancement adds `@Vetoed` (index-discovered beans ignored the added veto; only the
   pre-discovery archive pass honored it). Regression test `BceVetoedEnhancementTest`.

Verified: new `MultiDataStoreRoutingTest` (mansart-data-tests, Vauban SE, two H2 databases)
red→green; full mansart reactor green; full vauban reactor green; e2e on
`vidocq-runtime-mansart-h2-example` (module path): POST/GET `/api/audit` 201/200 with rows in
the audit database only, `/api/products` CRUD unchanged; CDI Lite TCK re-run (see below).

## MANSART-004 — Jakarta Data 1.0 TCK harness fully green (root cause was upstream in Vauban)
- **Date**: 2026-06-02 — **Status**: FIXED
- **Severity**: high (the official TCK could not run at all — it is the conformance contract)

### Symptom
`run-official-tck-data-1.0.sh` (and `-Ptck-run test`) reported `EntityTests` 73/73 ERROR, each failing
in the TCK's `@BeforeEach setup()` with `expected: not <null>` on an injected read-only repository.
The Vauban TCK enricher returns `null` when bean resolution fails, so the injected `null` was a
**symptom**, not the cause.

### Cause
Two layers, neither in Mansart production code:
1. **Upstream (the real cause) — Vauban `VAU-DISC-001`.** The harness bootstraps Vauban with
   `beanArchive(false)`; `MansartDataExtension` adds the runtime producer via `ScannedClasses.add(...)`.
   Vauban then restricted **all** bean discovery to the scanned set, dropping the test's
   `@Singleton @Produces DataSource` (`H2DataSourceProducer`). `MansartRuntimeProducer` saw an
   unsatisfied `Instance<DataSource>` → threw "No @Default DataSource bean found" → every
   `@Repository`'s `RepositoryRuntime` was unsatisfied → enricher injected `null`. Fixed in Vauban.
2. **Test-only — `MansartCdiBootstrapSmokeTest` under-specified.** It hand-built a container with just
   3 bean classes and expected `AuthorRepository` injectable, but never added the APT-generated
   `AuthorRepositoryImpl` (a managed `@Singleton`). The BCE `@Synthesis` correctly defers to a managed
   impl instead of registering a duplicate synthetic bean, so with the impl absent the repo resolved to
   nothing. Product behaviour is correct; the test now adds `Author`/`AuthorRepository`/`AuthorRepositoryImpl`
   (mirroring `MansartArquillianSmokeTest`'s package sweep).

### Fix / verification
Vauban `VAU-DISC-001` (relaxed `BeanDiscovery` scanned-classes filter to never hide annotated beans) +
the smoke-test correction above. Official TCK now **74/74** (EntityTests 73 + SignatureTests 1, H2 in
memory, Maven 4.0.0-rc-5); module smoke suite 9/9. The earlier "pre-existing/environmental" diagnosis
(baseline-confirmed 73 errors) was correct that it was independent of MANSART-002, but the cause was a
real Vauban discovery bug, not the environment.

## MANSART-002 — derived query with 3+ `And`/`Or` conditions silently mis-parsed

- **Date**: 2026-06-01
- **Status**: FIXED 2026-06-01 (see "Fix"; new `NaryDerivedQueryTest`, full data reactor 147 tests green)
- **Severity**: medium (any repository method with ≥3 conditions; surfaced from Arago, cf. arago `ARAGO-007`)
- **Symptom**: a derived query like
  `List<Seat> findByRoomIdAndSeatRowAndSeatBlockIndexAndReleased(String, int, int, boolean)` **compiled
  fine** but threw `UnsupportedOperationException` at runtime on first call. The 2-condition form
  (`findByRoomIdAndReleased`) worked. Worse, the build was green: the failure only showed at runtime.
- **Cause**: `QueryMethodParser.splitOnToken`/`containsToken` split on only the FIRST valid `And`/`Or`
  and returned exactly two chunks. With 3+ conditions the remainder
  (`SeatRowAndSeatBlockIndexAndReleased`) was bundled into one token, `parsePredicate` could not resolve
  it as a single attribute, the whole parse returned `null`, and `RepositoryWriter` emitted a
  `throw new UnsupportedOperationException(...)` stub **without any compiler diagnostic**.
- **Fix**:
  1. `QueryMethodParser` — new recursive `splitAll` (shortest-valid-left + backtracking) decomposes a
     pure-`And` or pure-`Or` chain into N predicates; `splitOnToken`/`containsToken` delegate to it.
     Mixed `And`/`Or` remains the documented limitation (unchanged).
  2. `RepositoryWriter` — when a method still cannot be derived, emit a `Diagnostic.Kind.WARNING` (via
     `Messager`, plumbed from `MansartProcessor`) so an unresolved query is visible at build time instead
     of only at runtime. (Not an ERROR: a test fixture, `findByTitleSensitive`, intentionally relies on
     the stub to assert `@Transactional` propagation without ever being called.)
- **Note (separate, was pre-existing — now FIXED, see MANSART-004)**: the official Jakarta Data 1.0 TCK
  runner used to fail to bootstrap (all `EntityTests` errored in `@BeforeEach` with the enricher injecting
  `null`). The `null` was a symptom; the root cause was upstream in Vauban (`VAU-DISC-001`). Fixed —
  the suite is now 74/74. Runner still needs Maven 4 (pom pins `maven-compiler-plugin:4.0.0-beta-4`) and
  the `run-official-tck-data-1.0.sh` `sdk env` step still aborts under `set -u`; invoke mvn directly.

## MANSART-001 — `boolean`/`Boolean` entity field breaks metamodel generation

- **Date**: 2026-05-31
- **Status**: FIXED 2026-05-31 (see "Fix" below; 145 tests green incl. new `BooleanAttributeTest`)
- **Severity**: medium (blocked any entity with a boolean column; easy workaround)

### Symptom
An `@Entity` with a `boolean` (or `Boolean`) persistent field makes the APT-generated metamodel
fail to compile:

```
_<Entity>.java: type argument java.lang.Boolean is not within bounds of type-variable V
_<Entity>.java: cannot infer type arguments for io.vidocq.mansart.data.dialect.attribute.NumericAttribute<>
  reason: inference variable V has incompatible bounds
    equality constraints: java.lang.Boolean
    upper bounds: java.lang.Number
<Entity>RepositoryImpl.java: is not abstract and does not override abstract method ...
```

The metamodel writer emits a `NumericAttribute<Boolean>`, but `NumericAttribute<V extends Number>`
excludes `Boolean`, so `javac` rejects the generated `_<Entity>.java` (and the repository impl that
depends on it).

### Minimal repro
```java
@Entity
class Flag {
    @Id String id;
    boolean enabled;   // <-- Boolean also fails
}

@Repository
interface FlagRepository extends BasicRepository<Flag, String> {}
```
`mvn compile` with the Mansart APT on the processor path → compilation failure above.

### Cause hypothesis
`mansart-data-processor/.../MansartMetamodelWriter` (and the parallel runtime path in
`mansart-data-core/.../RuntimeEntityModelBuilder`) classify a `boolean`/`Boolean` field as a numeric
attribute. There is no dedicated boolean attribute type next to
`TextAttribute`/`NumericAttribute`/`EnumAttribute`/`TemporalAttribute` (see
`mansart-data-dialect-spi/.../attribute/`), and boolean is not otherwise routed, so it falls through
to `NumericAttribute`, whose type variable is bounded to `Number`.

### Fix (implemented 2026-05-31)
Added a dedicated `BooleanAttribute<E>` (record, `javaType() → Boolean.class`) to the dialect SPI and
routed `boolean`/`Boolean` fields to it in both code paths:
- `mansart-data-dialect-spi`: new `attribute/BooleanAttribute.java`; added to the `sealed Attribute`
  `permits` list.
- `mansart-data-processor`: new `AttributeKind.BOOLEAN` + `isBoolean(fqn)` check (before NUMERIC) in
  `EntityScanner`; new `case BOOLEAN` in `MansartMetamodelWriter` (emits `BooleanAttribute<>`).
- `mansart-data-core`: `RuntimeEntityModelBuilder.describeField` returns `BooleanAttribute` for
  `Boolean.class` (before the numeric check).

No downstream change was needed: dialects key off `Attribute.javaType()` (PostgreSQL/H2 already map
`Boolean.class → Types.BOOLEAN`), and no exhaustive `switch` over the sealed subtypes exists.

Regression test: `mansart-data-tests` — new entity `BooleanFlag` (primitive `boolean` + boxed
`Boolean`) + `BooleanAttributeTest` asserting both APT (`_BooleanFlag`) and runtime paths type the
fields as `BooleanAttribute`.

### Historical workaround (no longer required)
Before the fix, the flag had to be modelled as a String-backed enum. Arago's `Speaker` initially used
an enum, then moved back to a plain `boolean` once this was fixed.

---

## MANSART-002 — `java.time.Instant` field fails to INSERT on PostgreSQL

- **Date**: 2026-05-31
- **Status**: FIXED 2026-05-31 (regression test `PostgresqlCrudIntegrationTest#instantFieldRoundTripsThroughTimestamptz`)
- **Severity**: high (any entity with an `Instant` column fails to persist on PostgreSQL)

### Symptom
Saving an entity with a `java.time.Instant` field mapped to `TIMESTAMPTZ` fails on PostgreSQL:

```
org.postgresql.util.PSQLException: Cannot convert an instance of java.time.Instant to type Types.TIMESTAMP_WITH_TIMEZONE
```

Not caught earlier because the unit tests run on H2 and the existing test entities use `LocalDate`,
not `Instant`. Surfaced by Arago (`Speaker.invitedAt` is an `Instant`).

### Cause
`PostgresqlDialect.bind` (and `H2Dialect.bind`) bound the value with
`ps.setObject(idx, value, Types.TIMESTAMP_WITH_TIMEZONE)`. The PG JDBC driver cannot convert a raw
`Instant` for `TIMESTAMP_WITH_TIMEZONE` — it expects an `OffsetDateTime` (or `Timestamp`).

### Fix (implemented 2026-05-31)
In both dialects' `bind`, add an `Instant` branch that binds
`instant.atOffset(ZoneOffset.UTC)` instead of the raw `Instant`. The read path (`extract`) already
converted `OffsetDateTime → Instant`, so only writes were affected. Regression: new `Event` entity
(an `Instant` column) + a PG round-trip test under the `pg-it` tag.

---

## MANSART-003 — `@Enumerated` is parsed but ignored; `EnumStorage.ORDINAL` is dead config

- **Date**: 2026-06-01 (surfaced by the Arago app — room `status`/`mode` enums)
- **Status**: FIXED 2026-10-07 (mansart#26, branch `pr/ybl/enumerated-storage`)
- **Severity**: low (every current entity wants STRING storage, which is what they get)

### Symptom
`MansartMetamodelWriter` always emits `EnumStorage.ORDINAL` for an enum attribute
(`MansartMetamodelWriter.java:102`), regardless of the entity's `@Enumerated(EnumType.STRING|ORDINAL)`.
Independently, both dialects bind/read enums **by name only**, unconditionally:
`PostgresqlDialect.bind` → `ps.setString(idx, e.name())` (and `H2Dialect` likewise), and `extract` →
`Enum.valueOf(type, s)`. So:
- `@Enumerated(EnumType.STRING)` works **by accident** — the dialect's name() path matches the intent,
  even though the metamodel says `ORDINAL`.
- `@Enumerated(EnumType.ORDINAL)` would be **silently mis-stored** as the constant name, not the index.
- The `EnumStorage` flag on `EnumAttribute` is never consulted at bind/read time — it is dead config.

### Minimal repro
Any `@Entity` with an enum field: the generated `_Entity` shows `EnumStorage.ORDINAL`; the row stores
the name string. (Arago `Room.status` is `@Enumerated(STRING)` + `VARCHAR(16)` and round-trips fine,
which is why this went unnoticed — the partial index `status IN ('DRAFT','ACTIVE')` only works because
the dialect happens to store names.)

### Cause hypothesis / fix sketch
Two aligned changes: (1) `MansartMetamodelWriter` should read `@Enumerated` and emit the matching
`EnumStorage`; (2) the dialects' `bind`/`extract` should honor `EnumAttribute.storage` — `name()`/
`valueOf` for STRING, `ordinal()`/`values()[i]` for ORDINAL. Add a regression entity with an
`@Enumerated(ORDINAL)` column. Not urgent: no current Vidocq entity needs ORDINAL.

### Fix (2026-10-07)
- `@Enumerated` is read by the processor (`EntityScanner`, with annotation defaults, so a bare `@Enumerated` is
  ORDINAL) and by `RuntimeEntityModelBuilder`; the metamodel writer emits the matching `EnumStorage`.
- No `@Enumerated` keeps storing the name (maintainer decision: existing tables hold names), unlike the JPA
  default. Documented in `reference.adoc`.
- The storage is honoured where the core knows the attribute — insert/update, row mapping, projections, WHERE
  bindings, bulk updates by attribute, `ensureTable` DDL — through one helper, `ColumnValues` (an ORDINAL enum is
  an `Integer` index); the dialects still bind by Java type. Not covered: a raw `SET` clause (`executeUpdateRaw`)
  and aggregates (`scalarSelect`), which carry no attribute.
- `EnumStorageTest` (processor, runtime builder, H2 column contents, derived query on an ORDINAL column), failing
  first. Reactor 312 tests; Jakarta Data TCK 73/73, the same as main.


---

## MANSART-008 — `StoredProcedureQuery` lifecycle: single execution, stale/unsafe results, leaks on failure

- **Date**: 2026-10-09 (parent review of the uncommitted P7 `StoredProcedureQueryImpl`, branch `persistence-work`)
- **Status**: FIXED 2026-10-09 (uncommitted, branch `persistence-work`)
- **Severity**: medium (API-contract violations; none surfaced by the official TCK at 1063 / 2135)

### Symptom
`mansart-jpa-core` `io.vidocq.mansart.jpa.core.query.StoredProcedureQueryImpl` (H2, `StoredProcedureQueryTest`):
1. a second `execute()` → `IllegalStateException`; `setParameter` after an execution → `IllegalArgumentException`;
   a failed call left the query "executed" (not retryable);
2. `setParameter(…)` and `getParameter(int|String, Class)` on a closed entity manager → `IllegalArgumentException`
   instead of `IllegalStateException`; `getParameterValue(Parameter)` of an unbound `IN` → `null` instead of
   `IllegalStateException`;
3. `setFirstResult(1)` without `setMaxResults` → `IllegalArgumentException: fromIndex(1) > toIndex(-2147483648)`
   (`first + Integer.MAX_VALUE` overflow);
4. a procedure returning a `null` scalar → `NullPointerException` (`List.copyOf`);
5. `getResultList()` when the pending result is not a result set → `[]` (javadoc: `null`); `getSingleResult()`
   then → `NoResultException` (javadoc: `null`);
6. an unbound trailing `IN` → `IllegalStateException`, although §3.11.12 says parameters with database defaults
   need no binding;
7. temporal bindings stored the converted `java.sql.*` value (so `getParameterValue` did not return the bound
   `Calendar`), rejected a `java.util.Date` for a `java.sql.Date` parameter, and `setNull` used the registered
   type rather than the temporal type;
8. `closeStatement()` swallowed `SQLException`, and a mapper/runtime exception between `prepareCall` and
   `closeStatement()` leaked the `CallableStatement` (it was an instance field).

### Minimal repro
```java
em.runWithConnection(c -> c.createStatement().execute(
    "CREATE ALIAS ADD_ONE AS 'int addOne(int value) { return value + 1; }'"));
var q = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
q.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
q.setParameter(1, 41).execute();
q.setParameter(1, 9);           // IllegalArgumentException: The parameter cannot be bound: 1
```

### Cause
The first P7 implementation modelled a one-shot JDBC `CallableStatement` (kept open as a field) instead of the
reusable `Query` contract, and copied `NativeQuery` helpers (`List.copyOf`, `first + max`) unchanged.

### Fix (2026-10-09)
- `execute()` always calls the procedure with the current bindings, replacing the previous results/outputs; a
  failed call leaves the query unexecuted and retryable. Binding or registering a parameter makes the query
  unexecuted, so `getResultList`/`getSingleResult[OrNull]`/`executeUpdate` (which execute only an unexecuted query,
  §3.11.12) never read results of stale inputs, and never run a procedure twice behind the user's back.
- The `CallableStatement` is a local try-with-resources: the primary failure is preserved, a close failure is
  suppressed on it, or reported as `PersistenceException` on its own. All output values are read before closing.
- `checkOpen()` on every parameter lookup; `getParameterValue(Parameter)` of an unbound input → `IllegalStateException`.
- Paging in `long`; results kept in an unmodifiable `ArrayList` copy (nulls allowed).
- `getResultList()` → `null`, `getSingleResult[OrNull]()` → `null` when the pending result is not a result set; the
  single-result methods no longer mutate `maxResults` (the inherited `NativeQuery.limited` did).
- Trailing unbound `IN` parameters are left out of the positional call so database defaults apply (works on every
  dialect; PostgreSQL requires defaulted parameters to be trailing anyway). An unbound `IN` before a sent parameter,
  or an unbound `INOUT`, remains an `IllegalStateException`. Named parameters are still sent positionally in
  registration order (named-notation calls would depend on the TCK's procedure argument names: not changed).
- Temporal bindings keep the value as given plus its `TemporalType`; the JDBC value and `setNull` type derive from
  the temporal type; `java.util.Date`/`Calendar` parameters accept either temporal value; plain `Calendar` /
  `java.util.Date` values are sent as timestamps.
- 9 new `StoredProcedureQueryTest` scenarios, red first (9 failures/errors), green after.
- Verification (2026-10-09, Temurin 25.0.4, PostgreSQL 17): clean JPA reactor 453 tests, 0 failures; full official
  TCK 2135 tests, 1063 passed, 1068 errors, 4 skipped — identical test by test to the preserved 1063 baseline
  (`core.StoredProcedureQuery` 38 / 40, the two XML-override tests wait for P10).

---

## MANSART-009 — Pool: "No suitable driver found" when the driver is in a child class loader

- **Date**: 2026-10-13
- **Status**: FIXED 2026-10-13 (mansart#42)
- **Severity**: high (the pool is unusable under Vidocq with the driver in the application layer)

### Symptom
`MansartDataSource.getConnection()` fails with `PoolException: failed to open new connection: No suitable driver
found for jdbc:...` when the JDBC driver jar is visible to the application's class loader but not to the pool's, as
under Vidocq with the driver in the application layer and the pool in the runtime layer.

### Minimal repro
`DriverInApplicationLoaderTest` (`mansart-pool-tests`): the pool in one `URLClassLoader`, H2 in a child one.

### Cause
`DriverManager.getConnection` skips a registered driver its caller's class loader cannot load (caller check), and
the pool's loader cannot see the application's driver.

### Fix
The pool resolves the driver itself at its first connection: `java.sql.Driver` providers of the context class loader
captured at construction first, then `DriverManager`, and keeps it (`DriverLocator`, `ConnectionPool.driver()`).

## MANSART-010 — Transactions: recovered Xids compare by array identity

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `fix/sonar-reliability-security`)
- **Severity**: low (the transaction manager matches journal records through `XidKey`, by content)
- **Module**: `mansart-transactions-core` (`FileRecoveryLog.ScannedXid`)

### Symptom
Two scans of the same recovery journal line return `Xid`s that are not `equals` and have different hash codes, so a
caller of `MansartTransactionManager.recover()` cannot match a recovered `Xid` against another one (in a set, a map, or
an `XAResource.recover` result held as `Xid`s). `toString()` prints array identities instead of the identifiers.

### Minimal repro
`RecoveryLogTest.scannedXidsCompareByContent`: write one journal line, scan twice, compare the two `xid()`s.

### Cause
`ScannedXid` is a record holding `byte[]` components: the generated `equals`/`hashCode`/`toString` use the array
references (SonarQube java:S6218).

### Fix
`ScannedXid` overrides `equals`, `hashCode` and `toString` on the format id and the contents of both arrays, as
`MansartXid` already does. Regression test: `RecoveryLogTest.scannedXidsCompareByContent`.

## MANSART-011 — Dialect SPI: `Pagination.Keyset` compares its cursor by array identity

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `fix/sonar-reliability-security`)
- **Severity**: low (no Mansart code compares keysets today; the SPI is public, a dialect or a caller may)
- **Module**: `mansart-data-dialect-spi` (`Pagination.Keyset`)

### Symptom
Two `Pagination.Keyset` values with the same limit, order and cursor values are not `equals` and have different hash
codes; `toString()` prints the array identity instead of the cursor values.

### Minimal repro
`PaginationKeysetTest` (`mansart-data-tests`): two keysets built from two `Object[]{42L, "b"}` arrays.

### Cause
`Keyset` is a record holding an `Object[]` component: the generated `equals`/`hashCode`/`toString` use the array
reference (SonarQube java:S6218).

### Fix
`Keyset` overrides `equals`, `hashCode` and `toString` with `Arrays.equals`/`Arrays.hashCode`/`Arrays.toString` on the
cursor. Regression test: `PaginationKeysetTest`.
## MANSART-012 — `MansartData.runtimeRepository` returned the generated implementation (shared cache)

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: low (the result depended on the order of the calls on one `MansartData`)
- **Module**: `mansart-data-core` (`MansartData`)

### Symptom
On one `MansartData`, `runtimeRepository(R.class)` after `repository(R.class)` returned the annotation-processor
`RImpl` instead of the runtime implementation, and `repository(R.class)` after `runtimeRepository(R.class)` returned
the runtime implementation instead of `RImpl`.

### Minimal repro
`MansartDataRepositoriesTest` (`mansart-data-tests`): call both methods for `AuthorRepository` on one `MansartData`.

### Cause
Both methods cached their instance in the same map, keyed by the repository interface only.

### Fix
`runtimeRepository` keeps its instances in a map of its own. Regression test: `MansartDataRepositoriesTest`.

## MANSART-013 — Annotation processor: the `First` keyword of derived queries was ignored

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: medium (generated repositories throw or return too many rows; the runtime proxy was right)
- **Module**: `mansart-data-processor` (`RepositoryWriter`)

### Symptom
In a repository implemented by the annotation processor:
- `Optional<E> findFirstByOrderByNameDesc()` threw `NonUniqueResultException` as soon as two rows existed;
- `List<E> findFirst2By…()` returned every matching row;
- `E findFirstBy…()` (single-entity return) generated code that did not compile.
`findOneBy…` behaved like `findFirstBy…` (`NonUniqueResultException` on several matches).

### Minimal repro
`DerivedFirstTest` (`mansart-data-tests`), with `AuthorRepository.findFirstByOrderByNameDesc`,
`findFirst2ByNameNotOrderByNameAsc` and `findFirstByNameStartsWithOrderByNameAsc`, through both implementations.

### Cause
`RepositoryWriter` parsed the method name with the `QueryMethodParser.parse` overload that drops the `First<N>` limit,
and emitted `runtime.queryOne(…)` for `findFirstBy`/`findOneBy`: one result or `NonUniqueResultException`, and an
`Optional` even for a single-entity return.

### Fix
`RepositoryWriter.derivedBody` reads the limit and, for `First`, `First<N>` and `One`, runs the query as a list in the
`OrderBy` order and keeps its first result(s), shaped as the method returns them (`Optional`, `Stream`, `List`, or the
entity, `EmptyResultException` when there is none), as the runtime proxy does. `writeMethod` is split
(`signature`, `appendTypeVariables`, `derivedBody`, `firstResults`). Regression test: `DerivedFirstTest`.

## MANSART-014 — Annotation processor: a missing single result threw `MansartDataException`

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: low (the exception type differs from the specification; the runtime proxy was right)
- **Module**: `mansart-data-processor` (`RepositoryWriter`, `JdqlParser`)

### Symptom
A generated method returning one entity (derived query or `@Query`) or one projected value threw
`MansartDataException` ("No result", "@Query returned no result", "Projection returned no result") when nothing
matched. Jakarta Data 1.0 specifies `jakarta.data.exceptions.EmptyResultException`, which `MansartDataException`
does not extend, so a caller catching it missed the case.

### Minimal repro
`EmptySingleResultTest` (`mansart-data-tests`): `AuthorRepository.findByIdGreaterThan`, `jdqlOneNamed` and
`jdqlNameOfId` without a match, through both implementations.

### Fix
The three generated `throw`s use `EmptyResultException`. Regression test: `EmptySingleResultTest`.

## MANSART-015 — Projections to a primitive or a primitive array failed

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: medium (`@Query("SELECT stock …") int stockOf(…)` failed at run time)
- **Modules**: `mansart-data-core` (`JdqlExecutor`), `mansart-data-processor` (`JdqlParser`)

### Symptom
- Runtime proxy: `@Query("SELECT id WHERE name = ?1") long idOf(String)` failed with `MansartDataException: SQL error`
  caused by the driver's "converting to class long" (H2: feature not supported).
- Annotation processor: `@Query("SELECT id ORDER BY id") long[] ids()` asked the driver for a `long[]` per row
  (`ClassCastException` inside H2), the array return falling into the single-value branch.

### Minimal repro
`EmptySingleResultTest.projectsToPrimitives` (`mansart-data-tests`): `AuthorRepository.jdqlIdOf` and `jdqlAllIds`,
through both implementations.

### Cause
The runtime path passed the primitive return (or array component) type to `ResultSet.getObject(int, Class)`, which
drivers only support for reference types; the processor had no array branch for single-column projections.

### Fix
`JdqlExecutor.projectionElement` boxes the element type; `JdqlParser.emitProject` reads an array's column as the
boxed component type and copies it into the array. Regression test: `EmptySingleResultTest.projectsToPrimitives`.

## MANSART-016 — Annotation processor: lifecycle methods on several entities did not compile

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: medium (a repository declaring one fails the build; the runtime proxy was right)
- **Module**: `mansart-data-processor` (`RepositoryWriter`)

### Symptom
`@Insert List<E> createAll(List<E>)`, `@Insert E[] createArray(E[])`, `@Update`/`@Save` on a list and
`@Delete void removeAll(List<E>)` generated `runtime.insertStrict(_E.$MODEL, (E) arg0)`, a cast of the list or
array to the entity: the generated class did not compile (`incompatible types`).

### Minimal repro
`ArticleBatchRepository` + `LifecycleBatchTest` (`mansart-data-tests`), through both implementations.

### Fix
`RepositoryWriter.lifecycleBody` recognises an array or `Iterable` parameter and `batchLifecycleBody` applies the
strict operation to each entity in order, returning nothing, a list or an array. Regression test: `LifecycleBatchTest`.

## MANSART-017 — `CrudRepository.insert`, `insertAll`, `update` and `updateAll` threw `UnsupportedOperationException`

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: high (four of the methods every `CrudRepository` inherits did not work, on both paths)
- **Modules**: `mansart-data-processor` (`RepositoryWriter`), `mansart-data-core` (`RuntimeRepositoryProxy`)

### Symptom
On a repository extending `CrudRepository<E, K>`, `insert`, `insertAll`, `update` and `updateAll` threw
`UnsupportedOperationException`: the annotation processor warned "Mansart could not derive a query for
'jakarta.data.repository.CrudRepository.insert'" and generated a throwing stub; the runtime proxy reported
"compile-time Impl missing AND not handled by runtime fallback".

### Minimal repro
`ArticleCrudRepository` + `LifecycleBatchTest.crudRepositoryInsertsAndUpdates` (`mansart-data-tests`), through both
implementations.

### Cause
Both paths implement the inherited methods by name (`save`, `saveAll`, `findById`, `findAll`, `delete…`, `count`,
`existsById`), a list that only covered `BasicRepository`; the lifecycle annotations these four carry are ignored on
inherited methods on purpose (`BasicRepository.save` and `delete` have other semantics).

### Fix
`RepositoryWriter.crudBody` and `RuntimeRepositoryProxy.crudDispatcher` implement the four methods with
`insertStrict` and `updateStrict` (one entity, or each entity of the list in order). `inheritedDispatcher` is split
(`findAll`, `deleteAll`) to stay under the complexity limit. Regression test:
`LifecycleBatchTest.crudRepositoryInsertsAndUpdates`.

## MANSART-018 — `@Find`: the runtime proxy ignored `@By` and `@OrderBy`; the processor failed on `@By(By.ID)`

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-processor`)
- **Severity**: medium (wrong rows or order on the runtime path; a build failure on the processor path)
- **Modules**: `mansart-data-core` (`RuntimeRepositoryProxy`), `mansart-data-processor` (`RepositoryWriter`)

### Symptom
- Runtime proxy: `@Find @OrderBy(value = "seatRow", descending = true) List<LabSeat> inBlock(@By("seatBlockIndex") int
  block)` returned the rows of every block (the parameter, named neither as an attribute nor matching a single
  attribute of its type, was not bound) and in no particular order (`@OrderBy` was not applied).
- Annotation processor: `@Find Optional<LabSeat> seat(@By(By.ID) Long id)` stopped the compilation with
  `@Find parse error on seat: Expected comparison operator after 'id', got: ( — JDQL: WHERE id(this) = ?1`.

### Minimal repro
`LabSeatRepository.inBlock` and `seat` + `FindByAndOrderByTest` (`mansart-data-tests`), through both implementations.

### Cause
`RuntimeRepositoryProxy`'s `@Find` binding read `@Param` only and its dispatcher started from an empty `OrderBy`;
`RepositoryWriter.findQuery` copied `By.ID` (`id(this)`) into its JDQL as an attribute name.

### Fix
The runtime binding reads `@By` (then `@Param`, then the parameter name; `By.ID` is the entity's id) and applies the
method's `@OrderBy` before any dynamic sort. The processor maps `By.ID` to the entity's `@Id` field; `findQuery` is
split (`findWhere`, `findAttribute`, `findOrderBy`, `appendOrderList`). Regression test: `FindByAndOrderByTest`.

## MANSART-019 — Runtime proxy: a single-entity `findFirst` without a match returned `null`

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-margin`)
- **Severity**: low (the generated implementation already threw)
- **Module**: `mansart-data-core` (`RuntimeRepositoryProxy`)

### Symptom
`Author findFirstByNameStartsWithOrderByNameAsc("Zz")` returned `null` on the runtime path when nothing matched;
Jakarta Data 1.0 and the generated implementation throw `EmptyResultException`.

### Minimal repro
`DerivedFirstTest.findFirstWithoutAMatchThrowsEmptyResultException` (`mansart-data-tests`), through both implementations.

### Fix
`RuntimeRepositoryProxy.derivedFirst` throws `EmptyResultException` for a single-entity return (an `Optional` stays
empty). `derivedDispatcher` is split (`DerivedPlan`, `executeDerived`, `derivedFind`, `derivedFirst`, `derivedOrder`,
`window`) to stay under the complexity limit.

## MANSART-020 — Runtime proxy: a `@Find` method returning a `Collection` or an `Iterable` failed

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-margin`)
- **Severity**: medium (`ClassCastException` on every call)
- **Module**: `mansart-data-core` (`RuntimeRepositoryProxy`)

### Symptom
`@Find Collection<LabSeat> inRoom(@By("roomId") String room)` threw `ClassCastException: LabSeat cannot be cast to
java.util.Collection` on the runtime path: only `List` returns were recognised, other return types got the first row.

### Minimal repro
`FindByAndOrderByTest.returnsEveryMatchAsACollection` (`mansart-data-tests`), through both implementations.

### Fix
`RuntimeRepositoryProxy.shapeFindResult` returns the list for `Collection` and `Iterable` returns too, as derived
queries already did.

## MANSART-021 — `@Insert` of an entity already stored inserted a copy

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-margin`)
- **Severity**: medium (silent duplicate rows; Jakarta Data specifies `EntityExistsException`)
- **Module**: `mansart-data-core` (`RepositoryRuntime`)

### Symptom
With a generated id, `insert(stored)` (or `@Insert`, `insertAll`) on an entity read or inserted before stored a new
row under a new id and overwrote the entity's id, instead of throwing `EntityExistsException`.

### Minimal repro
`LifecycleBatchTest.insertingAStoredEntityAgainFails` (`mansart-data-tests`), through both implementations.

### Cause
The INSERT leaves out a generated id column, so the database never saw the conflicting id.

### Fix
`RepositoryRuntime.insertStrict` throws `EntityExistsException` when the entity's generated id is set and a row with
that id exists. An id set on an entity that matches no row is still replaced by the generated one.

## MANSART-022 — Annotation processor: an inherited `@Find` with `@By(By.ID)` crashed the compilation

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `test/coverage-margin`, SonarQube java:S2259 on mansart#53)
- **Severity**: medium (build failure)
- **Module**: `mansart-data-processor` (`RepositoryWriter`)

### Symptom
A repository inheriting `@Find Optional<E> byKey(@By(By.ID) Long key)` from a plain interface (not a repository)
stopped the compilation: `NullPointerException: Cannot invoke "TypeElement.getEnclosedElements()" because "entity"
is null`.

### Minimal repro
`LabSeatLookups` + `LabSeatRepository extends BasicRepository<LabSeat, Long>, LabSeatLookups` and
`FindByAndOrderByTest.bindsTheIdOfAnInheritedFindMethod` (`mansart-data-tests`).

### Cause
`RepositoryWriter.entityForRepo` looks the entity up on the method's declaring interface, which has none here.

### Fix
`entityForRepo` falls back to the entity of the repository being written. Regression test:
`FindByAndOrderByTest.bindsTheIdOfAnInheritedFindMethod`.

## MANSART-023 — JDQL: a literal shaped like `pkg.Class.MEMBER` loaded the class it named

- **Date**: 2026-10-28
- **Status**: FIXED 2026-10-28 (branch `fix/jdql-enum-literal-lookup`, SonarQube/Opengrep unsafe reflection)
- **Severity**: high (security: class loading chosen by runtime input)
- **Module**: `mansart-data-core` (`JdqlExecutor`)

### Symptom
Any string or identifier literal of a JDQL statement with at least one dot and an uppercase last segment, such as
`WHERE label = 'com.example.payload.Gadget.RUN'`, made Mansart call `Class.forName("com.example.payload.Gadget", false,
TCCL)` (then the same name with each dot in turn replaced by `$`), whatever the attribute compared. `JdqlExecutor.run`
takes statements as text at run time — the Vidocq dev console's JDQL tab sends them over HTTP — so the caller chose
which classes were looked up, and an enum it named was taken as the value even for an attribute of another type.

### Minimal repro
`JdqlEnumLiteralTest.aLiteralNamingAnotherClassIsNeverLoaded` (`mansart-data-tests`): with a context class loader
that records each requested name, the statement above asked it for `com.example.payload.Gadget`.

### Cause
`JdqlExecutor.resolveLiteral` resolved enum literals by loading the class named by the literal's qualifier
(`loadClassForEnum`), with no link to the entity model.

### Fix
`resolveLiteral` takes the type of the attribute the literal meets, from the entity model. A literal is an enum
constant only when that type is an enum and the qualifier is that enum's canonical (`pkg.Outer.Inner`) or binary
(`pkg.Outer$Inner`) name; the constant is looked up among that enum's constants. Otherwise the literal stays text.
`loadClassForEnum` is removed: no class is loaded from a name in the statement. Regression tests:
`JdqlEnumLiteralTest` (foreign class never requested, top-level and nested enum literals in comparisons, `IN` and
`SET`, a literal naming another enum refused for the attribute's enum).

## MANSART-024 — Runtime repository: a generic repository contract, or a default method, broke the runtime path

- **Date**: 2026-10-29
- **Status**: FIXED 2026-10-29 (branch `fix/runtime-method-selection`, generated-code audit)
- **Severity**: high (the runtime path — the one the TCK and every pre-compiled repository take — failed where the
  generated implementation worked)
- **Module**: `mansart-data-core` (`RuntimeRepositoryProxy`, `RuntimeRepositoryClassGenerator`)

### Symptom
- `@Repository interface GenericArticleRepository extends ArticleBase<Article>`, with
  `interface ArticleBase<T> extends CrudRepository<T, Long>`: `runtimeRepository` threw
  `MansartDataException: … does not extend BasicRepository<E, K> / CrudRepository<E, K>`.
- `@Repository interface DefaultedArticleRepository extends ArticleTitles, CrudRepository<Article, Long>` that
  implements `ArticleTitles.featured()` with a default body: the runtime implementation overrode the default body
  with a stub that threw `UnsupportedOperationException: … not handled by runtime fallback`.

### Minimal repro
`RepositoryShapesTest` (`mansart-data-tests`), parameterized over the generated and the runtime implementation.

### Cause
- `resolveEntityAndKey` walked the raw super-interfaces and only took a `Class` as entity argument, so the type
  variable `T` of `ArticleBase<T>` was never bound to `Article`.
- `RuntimeRepositoryClassGenerator.collectAbstractMethods` walked `getDeclaredMethods()` up the hierarchy and kept
  every non-default method: the abstract method a default body implements, but also static, private and synthetic
  methods. The annotation processor keeps the abstract members only (`getAllMembers`).

### Fix
- The entity and key are resolved through the hierarchy with the bindings of each parameterized super-interface.
- The generator implements the abstract methods of `getMethods()` (a default body or a bridge hides what it
  implements), the processor's rule; the dispatcher table keeps every abstract declaration of the hierarchy, so that
  the `Proxy` safety net still finds its dispatcher, and no longer scans static, private or synthetic methods.

## MANSART-025 — Runtime repositories: the `Proxy` fallback was silent

- **Date**: 2026-10-29
- **Status**: FIXED 2026-10-29 (branch `fix/runtime-generation-warning`, generated-code audit)
- **Severity**: medium (silent performance and AOT regression)
- **Module**: `mansart-data-core` (`RuntimeRepositoryProxy`)

### Symptom
A repository compiled without `mansart-data-processor` is implemented at run time, and when the Class-File API
generator failed on it (`ReflectiveOperationException`, `RuntimeException` or `LinkageError`: a repository package not
open to Mansart under strict Java Modules, a loader that cannot link the generated class), a
`java.lang.reflect.Proxy` served it instead. Both fallbacks happened without a word in the log: an application could
run on reflection without knowing that its build did not run the processor.

### Minimal repro
`RuntimeGenerationWarningTest` (`mansart-data-tests`): a repository interface defined by a loader that cannot link the
generated class is served by a `Proxy`, and nothing is logged.

### Cause
`RuntimeRepositoryProxy.create` swallowed the generator's failure (`catch (… _)`) and logged neither path.

### Fix
`RuntimeGeneration` announces each repository implemented at run time with a framed `WARNING` (`MANSART — RUNTIME
CODE GENERATION FALLBACK`), and each repository served by the `Proxy` with another (`MANSART — DYNAMIC PROXY
FALLBACK`) that names the cause and carries the exception; once per repository and JVM. Both point to the fix: add
`mansart-data-processor` to the annotation processor path. Documented in `internals.adoc` ("The run-time fallback").

## MANSART-026 — Annotation processor: nested repositories, private repositories and invalid queries

- **Date**: 2026-10-29
- **Status**: FIXED 2026-10-29 (branch `fix/processor-robustness`, generated-code audit)
- **Severity**: medium (build failures and a repository that `MansartData.repository` never finds)
- **Module**: `mansart-data-processor` (`MansartProcessor`, `RepositoryWriter`)

### Symptom
- Two repositories nested in different types under the same simple name (`Kennel.Pets`, `Shelter.Pets`) both
  generated `demo.PetsImpl`: the second failed with "Attempt to recreate a file for type demo.PetsImpl".
- A nested repository's implementation was named `PetsImpl`, but `MansartData.repository(Class)` and the CDI
  extension's sibling check look for `<binary name>Impl` (`Kennel$PetsImpl`): never found.
- A private nested repository got a generated class that cannot implement it, and the build failed with
  "demo.Hidden.Pets has private access in demo.Hidden".
- An invalid `@Query` made the processor throw out of javac (an `IllegalStateException` with its stack trace) instead
  of reporting a compile error.

### Minimal repro
`ProcessorNestedRepositoryTest` and `ProcessorComparisonOperatorsTest.rejectsALoneExclamationMark`
(`mansart-data-tests`).

### Cause
`RepositoryWriter.writeIfRepository` named the implementation `<simple name>Impl`. `MansartProcessor.writeRepository`
generated an implementation for every `@Repository`, whatever its visibility, and caught only `IOException`.

### Fix
- The implementation is named after the binary name (`Outer$PetsImpl` for a nested repository; unchanged for a
  top-level one).
- A repository that a top-level class of its package cannot implement (private, local, nested in a private type) is
  left to the run-time path, with a NOTE.
- A `RuntimeException` while generating is reported as a compile error on the repository. The source is rendered in
  memory before it is written, so no partial file is left behind.

## MANSART-027 — A stale `mansart-repositories.list` entry left the repository without a bean

- **Date**: 2026-10-29
- **Status**: FIXED 2026-10-29 (branch `fix/stale-repository-entry`, generated-code audit)
- **Severity**: medium (the application fails to start with an unsatisfied dependency)
- **Module**: `mansart-data-cdi` (`MansartDataExtension`)

### Symptom
An index entry `Itf=ItfImpl` whose implementation cannot be loaded (deleted, or left behind by an incremental build
or a repackaged jar) made `@Inject Itf` unsatisfied: the interface was neither registered from the index nor sent
to the runtime path.

### Minimal repro
`StaleRepositoryIndexTest` (`mansart-data-tests`): a context class loader adds an index naming a missing
`DefaultNoteLogGoneImpl`; `container.select(DefaultNoteLog.class)` threw `UnsatisfiedResolutionException`.

### Cause
`discoverRuntimeRepositories` keeps every indexed interface off the runtime path, and `@Synthesis` skipped an entry
silently as soon as either class failed to load, a tolerance meant for the APT-time replay, where neither is loadable.

### Fix
When the interface loads but its implementation does not, the extension logs a WARNING and registers the interface on
the runtime path. An entry whose interface does not load is still skipped silently. Jakarta Data TCK 74/74.

## MANSART-028 — A Jakarta Data metamodel Mansart did not generate made the entity unusable

- **Date**: 2026-10-29
- **Status**: FIXED 2026-10-29 (branch `fix/foreign-metamodel`, generated-code audit)
- **Severity**: medium (any repository of the entity fails)
- **Module**: `mansart-data-core` (`EntityModels`)

### Symptom
An entity `Book` next to a `_Book` class that Mansart did not generate — the Jakarta Data static metamodel written by
hand, or by another annotation processor, which uses the same `_Entity` name — made `EntityModels.of(Book.class)`
throw `MansartDataException: Generated metamodel …_Book has no public static $MODEL field`.

### Minimal repro
`EntityModelsOfTest.aMetamodelWithoutModelFieldIsAnotherToolsAndTheModelIsBuiltAtRunTime` (`mansart-data-tests`),
with the hand-written `_ForeignGadget`.

### Cause
Any `_Entity` class was taken for Mansart's own, so a missing `$MODEL` was reported as a broken generation.

### Fix
A `_Entity` class without a `$MODEL` field, or whose `$MODEL` is not an `EntityModel`, is another tool's: the model is
built at run time, as when there is no such class. An inaccessible `$MODEL` still throws. Jakarta Data TCK 74/74.
