# TCK Jakarta Transactions 2.0 — Mansart status

## Overview

| Layer | Status | Verification |
|---|---|---|
| Eclipse zip auto-recovery | ✅ | `./install-tck.sh --verify` |
| M2 installation (`jakarta.transaction:jakarta.transaction-tck:2.0.1`) | ✅ | `ls $HOME/.m2/repository/jakarta/transaction/jakarta.transaction-tck/2.0.1/` |
| Java adapter (`MansartTckProvider`, `MansartUserTransaction`) | ✅ | `mansart-transactions-tck/src/main/java/` |
| JUnit smoke wiring 5 tests | ✅ 5/5 | `./run-official-tck-transactions-2.0.sh smoke` |
| TCK fixtures build via tsant | ✅ M6b | `./run-official-tck-transactions-2.0.sh tsharness` (auto-template ts.jte + ant build.all.tests) |
| Each leaf run (`ant runclient`) | ⏳ M6c | Manual procedure below, automation to come |
| Jakarta Transactions API Sigtest | ⏳ M6c | Launched at the same time as the corresponding runclient |

## Why no auto-runner for the full suite?

The Jakarta Transactions 2.0 TCK distributed by Eclipse is a historical **Sun tsharness harness**
(format inherited from the Sun Java EE TCK era), with:

- an Apache Ant `bin/build.xml`,
- a `bin/ts.jte` describing ~80 properties (JAVA_HOME, SUT classpath, hostnames, ports, …),
- test sources under `src/com/sun/ts/tests/jta/ee/` that are compiled in place by
  `ant build` then executed by `ant runclient`.

No Surefire-scannable jar exists. Attempting `dependenciesToScan` on `jtatck.jar` only finds
support classes (`com.sun.ts.lib.deliverable.tck.*`) — not the tests.

The M6b work consists of:

1. producing a minimal templated `ts.jte` pointing to `MansartTckProvider` + Mansart jars;
2. wrapping the `ant build` + `ant runclient` invocation in the runner to make it reproducible;
3. parsing the tsharness HTML report and summarizing it in `target/tck-report-transactions.txt`.

Not blocking work for M2..M5 — the smoke wiring already validates that our TM is callable
exactly as the TCK would do it.

## Upstream tracking

- [Jakarta Transactions TCK GitHub](https://github.com/jakartaee/transactions-tck) — a Maven
  format is not announced in the public roadmap.
- Comparable to the Servlet 6.1 TCK (cf. `foy/`) which recently did its Maven migration.

## Manual procedure (summary — see module README for details)

```bash
./install-tck.sh                          # download + unpack
cd .tck-cache/transactions-tck/bin
$EDITOR ts.jte                             # JAVA_HOME, jta.classes, Mansart provider
ant build && ant runclient
xdg-open ../dist/index.html                # HTML report
```
