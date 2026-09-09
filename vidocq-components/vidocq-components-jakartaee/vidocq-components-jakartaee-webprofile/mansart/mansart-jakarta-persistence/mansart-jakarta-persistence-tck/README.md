# mansart-jakarta-persistence-tck

Runner for the **official Jakarta Persistence 3.2 TCK** (`jakarta.tck:persistence-tck-spec-tests:3.2.1`).

## Why this module is outside the main reactor

Module in standalone `modelVersion 4.0.0` (without `<parent>`). Reason documented in the workspace root `CLAUDE.md`: ShrinkWrap Maven Resolver 3.3 (transitive from the TCK) doesn't know how to parse Maven Model 4.1.0 POMs. As long as upstream ShrinkWrap doesn't support Model 4.1, this module remains detached.

## Status (as of 2026-09-09)

| Component | Status |
| --- | --- |
| Standalone POM Model 4.0.0 + Arquillian/TestNG/JUnit5 deps + official TCK | ✅ M0 |
| `run-official-tck-persistence-3.2.sh` script | ✅ M0 |
| Official TCK suite 3.2.1 — resolved from Maven Central + 161 `Client` tests | ✅ M0 |
| TCK signature tests / PostgreSQL variant | ⏳ deferred |

## TCK artifact installation

**Good news (M6.3)**: `jakarta.tck:persistence-tck-spec-tests:3.2.1` is on Maven Central. No manual install needed — the `-Ptck-run` profile downloads it automatically.

```xml
<dependency>
    <groupId>jakarta.tck</groupId>
    <artifactId>persistence-tck-spec-tests</artifactId>
    <version>3.2.1</version>
</dependency>
```

## Lancement

```bash
# Default: Run the official Jakarta Persistence 3.2 TCK (161 `Client` classes) on H2 in-memory
./run-official-tck-persistence-3.2.sh
# = mvn -Ptck-run test -DfailIfNoTests=false

# Target a specific test
./run-official-tck-persistence-3.2.sh -Dtest=Client#testFindAll
```

Prerequisites:
- **Java 25** + **Maven 3.9.16** (sub-project `.sdkmanrc`; `cd mansart-jakarta-persistence && sdk env`)
- TCK 3.2.1 resolved automatically from Maven Central (`jakarta.tck:persistence-tck-spec-tests:3.2.1`).

## Harness architecture

```
┌──────────────────────────────────────────────┐
│  Official TCK Jakarta Persistence 3.2 (TestNG) │
│  ─ classes named Client                       │
└──────────────────────┬───────────────────────┘
                       │ Arquillian deployment
                       ▼
┌──────────────────────────────────────────────┐
│  Vauban (CDI 4.1 Lite)                        │
│  ─ discovers Mansart BCE                      │
│  ─ creates synthetic beans for each           │
│    scanned @Entity, @Embeddable, etc.         │
└──────────────────────┬───────────────────────┘
                       │ inject
                       ▼
┌──────────────────────────────────────────────┐
│  Generated EntityManager (mansart-jakarta-persistence-core) │
│  → DialectFactory ServiceLoader              │
│  → H2 in-memory                              │
└──────────────────────────────────────────────┘
```

## Note on Model 4.1.0

The official TCK harness uses ShrinkWrap Maven Resolver 3.3, which cannot parse Maven Model 4.1.0 POMs.
If you attempt to include this module in a parent pom with modelVersion 4.1.0, resolution will silently
fail (no error, but .jar from TCK not extracted). Ensure this module is NOT pulled into a reactor with
modelVersion 4.1.0. This module’s parent is explicitly excluded.

## Roadmap

- **M0 — TCK Runner Setup** ✅ — Client.class inclusion, TCK jar resolved
- **M1 — Core Implementation (persistence-core)** ⏳ — EntityManager generation, H2 Dialect
- **M2 — PostgreSQL Support** ⏳ — Testcontainers, DialectFactory
- **M3 — SignatureTests** ⏳ — sigtest-maven-plugin, jimage.dir, java.specification.version
- **M4 — Integration with Mansart BCE** ⏳ — Entity/EntityGraph injection

This module is fully functional for the baseline TCK. No further development needed until persistence-core is ready.