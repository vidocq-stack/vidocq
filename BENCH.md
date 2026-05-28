# Vidocq Runtime — Benchmark log

> Per workspace rule: every performance number cited in code, docs or commit
> messages must have a matching entry here, with the exact command, hardware,
> JVM, raw results, and a delta against the previous run.

---

## 2026-05-28 — `vidocq:dev` reload time (M1 baseline)

Initial measurement after landing the `vidocq:dev` Mojo (Approach A, fast
process-restart). The goal is twofold:
- give M2 (decide whether Approach B is worth building) a concrete number to
  compare against;
- establish a baseline so any future optimisation (`mvnd`, Maven daemon, smarter
  incremental compile, etc.) has something to beat.

### Setup

| Field    | Value |
|----------|-------|
| Hardware | macOS 26.5 (Darwin 25.5.0), Apple silicon |
| JVM      | Temurin 25 (`Temurin-25+36-LTS`) |
| Maven    | 4.0.0-rc-5 via `./mvnw` |
| Command  | `cd vidocq-runtime-examples/vidocq-runtime-cassini-rest-example && ../../mvnw -ntp vidocq:dev -Dvidocq.mainModule=io.vidocq.runtime.examples.rest -Dvidocq.mainClass=io.vidocq.runtime.examples.rest.RestExampleApp` |
| Edit     | `echo "// edit" >> TodoResource.java` (semantically inert, forces watcher) |

### Results

`Reloaded in <X> ms` lines reported by the Mojo across 6 consecutive cycles
(first reload after Mojo startup excluded as warm-up):

```
Reloaded in 1806 ms (pid=83523)
Reloaded in 1801 ms (pid=83878)
Reloaded in 1899 ms (pid=84064)
Reloaded in 1812 ms (pid=84245)
Reloaded in 1812 ms (pid=84452)
Reloaded in 1832 ms (pid=84637)
```

| Metric  | Value |
|---------|------:|
| min     | 1801 ms |
| p50     | 1812 ms |
| p90     | 1899 ms |
| max     | 1899 ms |

Cold child-JVM boot itself, as reported by `VidocqBootstrap`:
`Vidocq - Started in 105–110 ms (process running for ~165 ms)`.

### Breakdown (approximate)

The total ~1.8 s of `Reloaded in …` covers:
- `mvn process-classes` invocation: ~1.5 s (dominated by Maven CLI startup)
- chappe drain + JVM stop: ~150 ms
- new JVM `--module-path` start to `Started in 105 ms`: ~170 ms

So the runtime-side reload is fast; the headline figure is bottlenecked by the
Maven shell-out, not by Vidocq.

### Delta vs previous run

First measurement — no previous data.

### Decision input for M2 (A vs B)

Sub-2 s reload on a real REST example, with zero risk of class-loader leaks or
ScopedValue pinning, is comfortably good enough for an MVP dev mode. Approach
B (in-VM `ModuleLayer`) would only become attractive once we hit ~500 ms, and
the obvious next stop is `mvnd` (Maven Daemon) which historically cuts the CLI
overhead by ~10×. That experiment is the next milestone, not B.
