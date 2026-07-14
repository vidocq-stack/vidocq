# Mansart — Bug tracker

Reproducible bugs in Mansart (Jakarta Data 1.0 + Persistence 3.2). Format per workspace CLAUDE.md:
short id, date, symptom, minimal repro, cause hypothesis, status. Update on each investigation.

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
- **Date**: 2026-07-14 — **Status**: OPEN (design work — bridge to specify)
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
- **Status**: OPEN, low severity (no functional impact today — see below)
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
