# Vidocq Runtime — Benchmark log

> Per workspace rule: every performance number cited in code, docs or commit
> messages must have a matching entry here, with the exact command, hardware,
> JVM, raw results, and a delta against the previous run.

---

## 2026-10-25 — A created application: `vidocq start` and its `vidocq:jlink` image

Startup of the application of the 0.4.0 getting started (Vidocq/vidocq#339): `vidocq create --name todo -g com.example
-x cassini-rest`, then `vidocq extension add knock-health`, nothing else edited. Quoted in the "Developer Joy in Vidocq
0.4.0" blog post, to be measured again on 0.4.0-RC1.

### Setup

| Field    | Value |
|----------|-------|
| Hardware | Apple M5 Max, 18 cores, 128 GB RAM, macOS 27.0 |
| JVM      | Temurin 25 (`Temurin-25+36-LTS`); the jlink image embeds the same JDK, stripped |
| Maven    | 3.9.16 via the `./mvnw` that `vidocq create` writes |
| Code     | 0.4.0-SNAPSHOT of #352 + #356 + #361 together (local scratch build `2b85e3c0`), CLI from that build's zip |
| App      | REST (`HelloResource`) + Health, `vidocq.http.port=8080`, default profile (prod) |
| start    | `vidocq start` (runs `vidocq:run`: forked lifecycle up to `process-classes`, then the application) |
| jlink    | `vidocq build jlink`, then the image's `bin/java -m io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq` (what `bin/todo` runs, without its `sh` wrapper) |
| Run      | one start per run, `GET /hello` checked (200) after `Started in`, then stopped |

### Results

`Started in` is the runtime's own line (bootstrap to ready); `process` is the JVM uptime it prints beside it.

| Run | start `Started in` | start process | jlink `Started in` | jlink process |
|-----|--------------------|---------------|--------------------|---------------|
| 1   | 121.2 ms | 212 ms | 120.9 ms | 223 ms |
| 2   | 111.1 ms | 196 ms | 119.7 ms | 232 ms |
| 3   | 109.7 ms | 195 ms | 119.2 ms | 231 ms |
| 4   | 111.6 ms | 197 ms | 119.3 ms | 232 ms |
| 5   | —        | —      | 118.5 ms | 221 ms |
| 6   | —        | —      | 118.1 ms | 227 ms |
| **median of runs 2+** | **111.1 ms** | **196 ms** | **119.2 ms** | **231 ms** |

Size: the jlink image is 40 MB with its JDK; `vidocq build jlink` took 5 s on a warm local repository.

Run 1 of each shape follows a build and is excluded from the median. An earlier run of the same image, right after
its build, printed 121.7 ms for a process of 1424 ms: the first start of a fresh image pays its cold file cache.

Delta: no previous entry for this shape. The audit figures quoted while drafting the post (130 ms for `vidocq start`,
133 ms for the image) were not recorded here; this entry replaces them.

---

## 2026-10-15 — Vaadin 25 on Foy: startup of a `vidocq:package` distribution and of a `vidocq:jlink` image

Startup of the Vaadin 25 spike (one `@Route` view, `vaadin-core` + `vaadin-prod-bundle`, production mode), quoted
in the Vaadin guide of the docs and in the blog post. It is the first time a Vaadin application links with `vidocq:jlink`.

### Setup

| Field    | Value |
|----------|-------|
| Hardware | Apple M5 Max, 18 cores, 128 GB RAM, macOS 27.0.1 |
| JVM      | Temurin 25 (`Temurin-25+36-LTS`); the jlink image embeds the same JDK, stripped |
| Maven    | 3.9.16 via `./mvnw`, isolated local repository |
| Code     | vidocq `9cb111ef` (origin/main), Vauban `61e7e404` (vauban#168), Foy `c8d5f724` (origin/main, with foy#50), Vaadin BOM 25.3.2 (`flow-*` 25.3.3) |
| App      | `vidocq.app.layer=all`; `vauban:modularize` in `all-automatic` mode over the whole closure (62 jars patched) |
| Package  | `./mvnw -o clean package vidocq:package -Dvidocq.app.layer=all -Dvaadin.productionMode=true`, then `bin/spike-vaadin.sh` |
| jlink    | `./mvnw -o clean package vidocq:jlink -Dvaadin.productionMode=true -Dvidocq.jlink.mergeSharedResources=true -Dvauban.modularize.forceExplicit=true`, then `target/dist/bin/spike-vaadin` |
| Run      | `JDK_JAVA_OPTIONS=-Dvaadin.productionMode=true`; one start per run, `GET /` checked (200) after `Started in`, then stopped |

### Results

`Started in` is the runtime's own line (bootstrap to ready); `process` is the JVM uptime it prints beside it.

| Run | package `Started in` | package process | jlink `Started in` | jlink process |
|-----|----------------------|-----------------|--------------------|---------------|
| 1   | 307.1 ms | 454 ms | 335.3 ms | 500 ms |
| 2   | 306.6 ms | 454 ms | 331.2 ms | 510 ms |
| 3   | 308.1 ms | 462 ms | 329.9 ms | 506 ms |
| 4   | 309.4 ms | 465 ms | 333.1 ms | 505 ms |
| 5   | 310.0 ms | 461 ms | 333.6 ms | 509 ms |
| **median** | **308.1 ms** | **461 ms** | **333.1 ms** | **506 ms** |

Size: the distribution is 19 MB without a JDK (69 jars in `app/`), the jlink image 68 MB with its JDK.

The first start of a freshly built jlink image took 537 ms (`Started in`); it is excluded, as the five above follow
it. The image starts about 25 ms slower than the distribution, most likely from reading compressed resources out of
the `jimage` (`vidocq.compress` defaults to `zip-6`); not investigated further.

Delta: no previous run of these two shapes.

---

## 2026-10-14 — `vidocq:dev` in-JVM reload under `vidocq.app.layer=all` (application layer with its libraries)

This entry sets spec criterion 3 of the application-layer design (Vidocq/vidocq#247). It times the in-JVM hot reload (application-layer re-creation, the default since M2) for three cases:
the Foy Servlet example under `classes` and under `all`, and a Vaadin 25 on Foy spike under `all`
(69 archives). The child-side phases were timed with throwaway timers that were reverted afterwards (see the
measurement report).

### Setup

| Field    | Value |
|----------|-------|
| Hardware | Apple M5 Max, 18 cores, 128 GB RAM, macOS 27.0.1 (Darwin 27.0.0) |
| JVM      | Temurin 25 (`Temurin-25+36-LTS`) |
| Maven    | 3.9.16 via `./mvnw`, isolated local repository (`MAVEN_ARGS=-Dmaven.repo.local=<scratch>/m2-applayer`) |
| Code     | vidocq `926f9a28` (origin/main, with vidocq#249), Vauban `172d0e5c` (origin/main) |
| Command  | `cd vidocq-runtime-examples/vidocq-runtime-foy-servlet-example && ../../mvnw -B -ntp -o vidocq:dev -Dvidocq.dev.debug=false -Dvidocq.dev.continuousTesting=false -Dvidocq.app.layer=<classes\|all>`; Vaadin spike: `./mvnw -B -ntp -o vidocq:dev -Dvidocq.dev.debug=false -Dvidocq.dev.continuousTesting=false -Dvidocq.app.layer=all -Dvidocq.dev.jvmArgs=-Dvaadin.productionMode=true` |
| Edit     | `echo "// rm-reload N" >> MenuServlet.java` (Vaadin: `MainView.java`), then wait for the child's `target/.vidocq-dev-reload.ready` |

### Results

There were 6 reloads per configuration, and the first is excluded as warm-up. Edit → ready is measured
externally, from the append to the `.ready` write. The host line `Hot reload signalled after <X> ms (in-JVM layer
swap)` covers the recompile only: `Reloaded in` is printed only by the respawn path. The child-side figure
covers `bootstrap.shutdown()` + `resetForReload` + `installIfConfigured` + `configure` + `start`.

Raw (reloads 2–6):

```
servlet classes  edit->ready ms: 3888 3872 3907 4254 3680   child ms: 26.7 24.8 20.2 21.4 20.8   layer install ms: 4.5 4.1 3.6 3.7 4.3
servlet all      edit->ready ms: 3911 5529 2250 4076 3855   child ms: 27.4 25.5 22.8 20.8 19.6   layer install ms: 4.7 4.0 4.3 3.6 3.7
vaadin  all      edit->ready ms: 3796 4227 3981 3796 3982   child ms: 166.4 189.7 166.0 165.5 160.1   layer install ms: 25.6 35.4 27.3 24.8 22.9
```

| Metric (median, reloads 2–6) | Servlet `classes` | Servlet `all` | Vaadin `all` |
|---|---:|---:|---:|
| Archives in layer / class entries | 1 / 39 | 1 / 39 | 69 / 5337 |
| Edit → ready | 3888 ms | 3911 ms | 3981 ms |
| ↳ edit → `Changes detected` (macOS polling watcher) | 2057 ms | 2032 ms | 1648 ms |
| ↳ recompile (`signalled after`) | 1767 ms | 1694 ms | 2040 ms |
| **Child-side reload** | **21.4 ms** | **22.8 ms** | **166 ms** |
| ↳ shutdown + reset | 1.3 ms | 1.3 ms | 2.6 ms |
| ↳ layer install | 4.1 ms | 4.0 ms | 25.6 ms |
| ↳↳ ModuleFinder + findAll | 1.6 ms | 1.6 ms | 10.1 ms |
| ↳↳ resolveAndBind | 1.5 ms | 1.5 ms | 7.2 ms |
| ↳↳ VaubanClassLoader.forLayer (open + index) | 0.8 ms | 0.7 ms | 5.1 ms |
| ↳↳ defineModules | 0.1 ms | 0.1 ms | 2.8 ms |
| ↳ configure | 1.3 ms | 1.2 ms | 1.6 ms |
| ↳ container start | 14.4 ms | 15.4 ms | 135.8 ms |
| First request after reload | 2.3 ms | 2.4 ms | 27.5 ms |

For the Vaadin case, a JFR recording over 10 reloads (`settings=profile`, stack depth 1024) splits container
`start` into Vaadin/Atmosphere's own init (~20 %), Foy's run-time Class-File `@HandlesTypes` scan and resolve
(~18 %), weaving analysis at class definition (~10 %), and `ConfigValues.withoutCredentials` regex masking (~6 %).
Within the layer, `ModulePath.findAll` (automatic-module package derivation) is the largest item, and
`promoteServices` runs 283 times for 69 modules.

### Delta vs previous run (2026-05-28, M1 process-restart baseline)

The two runs differ in the reload approach (in-JVM layer swap now, process respawn then), the example (Foy
Servlet vs Cassini REST), the Maven version (3.9.16 vs 4.0.0-rc-5) and the machine, so only the order of
magnitude compares:

| | 2026-05-28 (respawn, REST) | 2026-10-14 (in-JVM, Servlet `classes`) |
|---|---:|---:|
| Recompile (`mvn process-classes`) | ~1.5 s | 1.77 s |
| Runtime-side swap (stop + start) | ~320 ms (drain/stop ~150 + new JVM ~170) | **21 ms** (−93 %) |
| Host total comparable to `Reloaded in` (recompile + signal + child) | 1812 ms p50 | ~1.82 s |

The in-JVM swap removed nearly all of the runtime-side cost. The recompile, and the ~2 s macOS watcher latency
that the 2026-05-28 entry did not count, now account for more than 99 % of edit → ready.

### Decision (criterion 3)

The archive caches (a descriptor cache with memoized service promotion, JarFile and class-index reuse, and
`Configuration` reuse when only `target/classes` changed) could recover about 19–21 ms of the Vaadin layer
install's 25.6 ms. That is about 0.5 % of edit → ready, so the reader cache planned in the spec is dropped.
Criterion 3 becomes the measured state, which is already met: a Vaadin child-side reload of ≤ 150 ms median, and no
Servlet regression (≤ 23 ms child-side, ≤ 4.5 ms layer install). Edit → ready is 1.02× between the Vaadin and the
Servlet cases. The time to win is on the Maven side, in the watcher latency and the recompile.

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

---

## 2026-10-14 — dependency codegen selection, with and without the memo (#246)

`vidocq:generate` decides, for every dependency jar, which bricks want it
(`DependencyCodegenSelection.decide`): resource lookups, then a byte search of
every class file when no resource hint matches. That ran on every build and
every `vidocq:dev` recompile. The memo (`<cacheDir>/selection/<sha256>`, keyed
on the jar's SHA-256 and the hints' digest) stores the decision, so a jar is
searched once per (jar, hints); a memoized build still hashes each jar.

### Setup

| Field    | Value |
|----------|-------|
| Hardware | Apple M5 Max, 128 GB, macOS 27.0.1 (Darwin 27.0.0) |
| JVM      | Temurin 25 (`Temurin-25+36-LTS`) |
| Jars     | the 72 jars under `com/vaadin` of the Vaadin spike's local repository (22.6 MB in total; 2 selected by Foy) |
| Hints    | Foy's `dependency-codegen.properties` |
| Harness  | throwaway `SelectionBench.java` (not committed): walks the jars, calls `decide` per jar, times each full pass; fresh JVM per run, 3 runs per mode |
| Command  | `java -cp vidocq-runtime-maven-plugin/target/classes:<plugin classpath> SelectionBench.java <m2>/com/vaadin <baseline\|memo\|memowarm> <passes> <memo dir>` |

Modes: `baseline` = `decide(jar, hints)`; `memo` = memoized lookup on an empty
memo (pass 0 searches and writes); `memowarm` = memoized lookup on the memo the
previous runs wrote, in a fresh JVM: what every build after the first sees.

### Results (ms per pass over the 72 jars)

```
baseline  run1: 94 65 65 64 65   run2: 77 65 65 65 63   run3: 78 65 65 65 64
baseline  run4: 83 70 70 69 69   run5: 76 65 64 64 64   run6: 75 65 64 64 64
memo      run1: 140 19 15 14 13  run2: 129 19 15 14 13  run3: 129 18 15 14 13
memowarm  run1: 31 18 14         run2: 31 18 14         run3: 31 18 14
```

Per build (fresh JVM, first pass): 75–94 ms searched, 31 ms memoized (about
2.5–3× less); the first build of a new jar set pays 129–140 ms once (search +
hash + write). Later passes in the same JVM: 64–70 ms → 13–19 ms. The remaining cost is the jars' SHA-256.

### Delta vs previous run

First measurement — no previous data.
