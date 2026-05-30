# mansart-data-tck

Runner for the **official Jakarta Data 1.0 TCK** (`jakarta.data:jakarta-data-tck:1.0.1`).

## Why this module is outside the main reactor

Module in standalone `modelVersion 4.0.0` (without `<parent>`). Reason documented in the workspace root `CLAUDE.md`: ShrinkWrap Maven Resolver 3.3 (transitive from the TCK) doesn't know how to parse Maven Model 4.1.0 POMs. As long as upstream ShrinkWrap doesn't support Model 4.1, this module remains detached.

## Status (as of 2026-05-05)

| Component | Status |
| --- | --- |
| Standalone POM Model 4.0.0 + Arquillian/TestNG/JUnit5 deps + official TCK | ✅ M6 |
| `run-official-tck-data-1.0.sh` script | ✅ M6 |
| Mansart BCE (`mansart-data-cdi`) wires `@Repository` in Vauban | ✅ M6.1 |
| Arquillian Vauban connector (ported from `vauban-tck-runner`) | ✅ M6.2 |
| Official TCK suite 1.0.1 — resolved from Maven Central + 73 EntityTests | ✅ M6.3 |
| Runtime impl generation (Class-File API) → **73/73 EntityTests PASS on H2** | ✅ M7-25 |
| **PostgreSQL variant via Testcontainers → 73/73 EntityTests PASS on PG** | ✅ M6.5 |
| **TCK SignatureTests → 1/1 PASS** (H2 and PG) | ✅ M7-26 |
| TCK PersistenceTests / NoSQLTests | ⏳ out of scope (needs `mansart-persistence`) |

**No more blockers**: the `MansartDataExtension` BCE (`mansart-data-cdi`) now discovers all `@Repository` interfaces in the TCK deployment and registers synthetic `@Singleton` beans typed on the interface. Implementations are **generated at runtime via Class-File API** (`mansart-data-core/RuntimeRepositoryClassGenerator`, M7-25) — no dynamic proxy, AOT-friendly.

## TCK artifact installation

**Good news (M6.3)**: `jakarta.data:jakarta.data-tck:1.0.1` (note the **dot**, not dash, in the artifactId) **is on Maven Central**. No manual install needed — the `-Ptck-run` profile downloads it automatically.

```xml
<dependency>
    <groupId>jakarta.data</groupId>
    <artifactId>jakarta.data-tck</artifactId>
    <version>1.0.1</version>
</dependency>
```

## Lancement

```bash
## Launch

```bash
# Smoke harness (Vauban + Mansart, 6 tests, default, without the TCK)
./run-official-tck-data-1.0.sh --smoke

# Official TCK EntityTests suite on H2 in-memory (default)
./run-official-tck-data-1.0.sh
# = mvn -Ptck-run test

# Official TCK EntityTests suite on PostgreSQL (Testcontainers)
./run-official-tck-data-1.0.sh --pg
# = mvn -Ptck-run,tck-pg test

# SignatureTests only (on H2)
./run-official-tck-data-1.0.sh --sig
# = mvn -Ptck-run,tck-sig test

# Full suite: EntityTests + SignatureTests (H2 by default, or PG=1 for PostgreSQL)
./run-official-tck-data-1.0.sh --full
PG=1 ./run-official-tck-data-1.0.sh --full

# Target a specific test
./run-official-tck-data-1.0.sh -Dtest=EntityTests#testFindAll
```

Prerequisites:
- **Java 25** + **Maven 3.9.16** (sub-project `.sdkmanrc`; `cd mansart-jakarta-data && sdk env`)
- `mansart-jakarta-data` built and installed (`mvn install -DskipTests` from `mansart-jakarta-data/` — the script does it automatically if needed)
- For `--pg`: **Docker** (Docker Desktop, OrbStack, colima…). Image `postgres:17-alpine` downloaded on first run.
- TCK 1.0.1 resolved automatically from Maven Central (`jakarta.data:jakarta.data-tck:1.0.1`).

## PostgreSQL mode (M6.5)

The Maven profile `tck-pg` (combinable with `tck-run`) substitutes all DataSource wiring:

- Activates `PostgresDataSourceProducer` (Testcontainers `postgres:17-alpine`) instead of `H2DataSourceProducer`.
- Substitutes `mansart-data-dialect-postgresql` for `mansart-data-dialect-h2` in the ShrinkWrap deployment (BCE discovers `PostgresqlDialectFactory` via `META-INF/services/io.vidocq.mansart.data.dialect.DialectFactory`).
- The system property `mansart.tck.dialect=pg` is set by the profile and read by `MansartTckArchiveAppender`.

The PostgreSQL container is started once per JVM (static singleton, `Runtime.addShutdownHook` for cleanup). No manual setup.

**Current score:**

| Platform | EntityTests | SignatureTests | Total | Run |
| --- | --- | --- | --- | --- |
| H2 in-memory | **73 / 73** ✅ | **1 / 1** ✅ | **74/74** | `./run-official-tck-data-1.0.sh --full` |
| PostgreSQL 17 (Testcontainers) | **73 / 73** ✅ | **1 / 1** ✅ | **74/74** | `PG=1 ./run-official-tck-data-1.0.sh --full` |

No dialect-specific fixes were needed: the `PostgresqlDialect` already covered everything the EntityTests exercise (`MERGE`/`INSERT … ON CONFLICT`, `RETURNING id`, `LIMIT/OFFSET`, temporal types, `IDENTITY`, etc.).

## SignatureTests mode (M7-26)

The Maven profile `tck-sig` (combinable with `tck-run` and `tck-pg`) activates binary signature verification of all `jakarta.data.*` packages. The official runner (CTS heritage / sigtest-maven-plugin):

- compares the API present on the deployment classpath to the canonical signature `jakarta.data.sig_21` shipped in TCK jar 1.0.1;
- requires a writable directory to cache JDK modules (automatically set to `target/jimage-cache`).

**Java 25-specific configuration** (already in the pom in the `tck-sig` profile):

- `--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED` + `--add-opens` of the same package: the sigtest runner does `setAccessible` on internal JDK annotations (`@Stable`), refused by default on Java 25.
- `-Djava.specification.version=21`: TCK 1.0.1 only ships `jakarta.data.sig_17` and `jakarta.data.sig_21`; on Java 25 the runner looks for `jakarta.data.sig_25` (NPE). Forcing version to 21 is safe — Jakarta Data 1.0 was frozen under Java 21, the API hasn't evolved since.

## Harness architecture

```
┌──────────────────────────────────────────────┐
│  Official TCK Jakarta Data 1.0 (TestNG)      │
│  ─ scenarios annotated @Repository, @Find, etc.│
└──────────────────────┬───────────────────────┘
                       │ Arquillian deployment
                       ▼
┌──────────────────────────────────────────────┐
│  Weld embedded (CDI 4.1)                     │
│  ─ discovers Mansart BCE                     │
│  ─ creates synthetic beans for each          │
│    scanned @Repository                       │
└──────────────────────┬───────────────────────┘
                       │ inject
                       ▼
┌──────────────────────────────────────────────┐
│  Generated RepositoryImpl (mansart-data-processor) │
│  → RepositoryRuntime (mansart-data-core)     │
│  → DialectFactory ServiceLoader              │
│  → H2 in-memory                              │
└──────────────────────────────────────────────┘
```

## Roadmap

- **M6.1 — M6.4** ✅ delivered (see status table above).
- **M7-1 → M7-25** ✅ delivered — runtime impl generation + 73/73 PASS on H2.
- **M6.5** ✅ delivered — PostgreSQL variant via Testcontainers, 73/73 PASS on PG.
- **TCK SignatureTests** ✅ M7-26 — profile `tck-sig`, 1/1 PASS on H2 and PG.
- **TCK PersistenceTests / NoSQLTests** ⏳ — blocked by absence of `mansart-persistence` (JPA 3.2) and NoSQL out of scope in v1.
