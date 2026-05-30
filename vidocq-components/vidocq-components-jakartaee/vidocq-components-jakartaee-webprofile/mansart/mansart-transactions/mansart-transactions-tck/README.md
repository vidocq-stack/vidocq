# mansart-transactions-tck

Adapter + harness for the **official Jakarta Transactions 2.0 TCK** against the Mansart implementation.

> **OUTSIDE reactor** — pom.xml in standalone `modelVersion 4.0.0` (no `<parent>`). Cf. `CLAUDE.md`
> at workspace root: as long as upstream ShrinkWrap Maven Resolver doesn't support Maven Model 4.1.0,
> this module stays detached and is never built via `mvn -pl …` from the parent reactor.

## Execution modes

| Mode | Command | What it does |
|---|---|---|
| **smoke** *(default)* | `./run-official-tck-transactions-2.0.sh` | 5 local JUnit tests verifying Mansart wiring (`MansartTckProvider`, `MansartUserTransaction`). Doesn't need official TCK — runnable offline. |
| **tsharness / all** | `./run-official-tck-transactions-2.0.sh tsharness` | Auto-downloads TCK from Eclipse if absent, unpacks under `../.tck-cache/transactions-tck/`, then prints official tsant invocation procedure (cf. below). |
| targeted | `./run-official-tck-transactions-2.0.sh -Dtest=Foo` | Surefire pass-through for debugging specific smoke. |

Output: `target/tck-transactions-output.log` + `target/tck-report-transactions.txt`.

## TCK auto-recovery

The runner calls `../install-tck.sh` which:

1. downloads `https://download.eclipse.org/jakartaee/transactions/2.0/jakarta-transactions-tck-2.0.1.zip`;
2. unpacks into `mansart-transactions/.tck-cache/transactions-tck/`;
3. installs `lib/jtatck.jar` in M2 under `jakarta.transaction:jakarta.transaction-tck:2.0.1`;
4. installs harness auxiliary jars (`tsharness.jar`, `sigtest.jar`, `javatest.jar`) under `io.vidocq.mansart:mansart-tck-*`.

Idempotent (`--force` to re-install, `--verify` to check only).

### Override

```bash
TCK_VER=2.0.1           ./run-official-tck-transactions-2.0.sh tsharness
TCK_URL=file:///…/x.zip ../install-tck.sh --force
```

## Harness architecture

| File | Role |
|---|---|
| `src/main/java/.../MansartTckProvider` | Singleton invoked by harness to retrieve the `TransactionManager`. |
| `src/main/java/.../MansartUserTransaction` | `UserTransaction` facade over Mansart `TransactionManager`. |
| `src/test/java/.../MansartTckSmokeTest` | 5 JUnit tests verifying wiring (lookup → begin → commit/rollback). |

## Why the official TCK is not Surefire-scannable

The **Jakarta Transactions 2.0 TCK** is a historical **Sun tsharness** harness:

```
.tck-cache/transactions-tck/
  bin/{tsant, ts.jte, build.xml, …}    ← Ant scripts + execution descriptor
  src/com/sun/ts/tests/jta/ee/…        ← test sources
  lib/jtatck.jar                        ← support classes (NOT the tests)
  classes/                              ← compiled by tsant build
  dist/                                 ← HTML reports after tsant runclient
```

No JUnit runner can pilot it directly. The official suite is invoked via:

```bash
cd .tck-cache/transactions-tck/bin
# 1. edit ts.jte (JAVA_HOME, jta.classes, Mansart provider)
ant build
ant runclient
# report: ../dist/
```

Official doc is unpacked under `.tck-cache/transactions-tck/docs/html-usersguide/`.

> Upstream tracking: a Maven/Surefire wrapper for Transactions 2.0 TCK doesn't exist
> Eclipse-side. Until it arrives, `tsharness` mode remains a manual guide.

## Status

- ✅ Zip auto-recovery from Eclipse Foundation.
- ✅ Mansart adapter (`MansartTckProvider` + `MansartUserTransaction`).
- ✅ Smoke wiring 5/5 green (launched by default, no external dependency).
- ⏳ Complete tsharness suite: `ts.jte` configuration to finalize (M6b — see `../PLAN.md`).
