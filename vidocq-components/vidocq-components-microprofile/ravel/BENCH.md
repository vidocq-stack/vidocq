# Ravel — Benchmarks

> In accordance with the cross-cutting rule in the Vidocq workspace `CLAUDE.md`, every
> Ravel performance figure **must** appear in this file with:
> date, hardware/JVM, exact command, raw results, and delta vs previous run.
> External communications (README, commits, ROADMAP) link here rather than
> hard-coding numbers.

## Run #1 — 2026-05-09 — baseline post-TCK 100% PASS (commit `f5603bb`)

### Environment

| Item | Value |
|---|---|
| Date | 2026-05-09 02:50 UTC |
| Hardware | Apple M1 Max, 10 P-cores / 10 L-cores, 32 GiB RAM |
| OS | macOS Darwin 25.4.0 (Kernel `xnu-12377.101.15~1`, ARM64 T6000) |
| JVM | Eclipse Temurin **25.0.3+9 LTS** (mixed mode, sharing) |
| JVM args | `-Xms256m -Xmx256m` (pinned via `@Fork(jvmArgsAppend=…)`) |
| JMH | 1.37 |
| Competitors | Smallrye Config **3.10.2** |
| Mode | `AverageTime` (`-bm avgt`), `ns/op` (`-tu ns`) |
| Warmup | 3 × 1 s |
| Measurement | 5 × 1 s |
| Forks | 1 |
| Threads | 1 |

### Command

```bash
cd ravel-bench
../mvnw -ntp package -DskipTests   # rebuild benchmarks.jar with current ravel-core
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1 -tu ns -bm avgt \
     -rf json -rff target/jmh-result.json
```

Note: the `ConfigImpl` class (`@Param impl`) scopes `ServiceLoader<ConfigProviderResolver>`
calls by FQN to avoid Ravel ↔ Smallrye collisions on the `ConfigProviderResolver` singleton
(see the comment at the top of `ravel-bench/src/main/java/io/vidocq/ravel/bench/ConfigImpl.java`).

### Raw Results

```
Benchmark                          (impl)  Mode  Cnt     Score    Error  Units
ConversionBenchmark.bool            RAVEL  avgt    5     9,862 ±  0,264  ns/op
ConversionBenchmark.bool         SMALLRYE  avgt    5    15,545 ±  0,350  ns/op
ConversionBenchmark.duration        RAVEL  avgt    5   157,558 ±  0,714  ns/op
ConversionBenchmark.duration     SMALLRYE  avgt    5   168,662 ±  5,668  ns/op
ConversionBenchmark.integer         RAVEL  avgt    5    14,760 ±  0,076  ns/op
ConversionBenchmark.integer      SMALLRYE  avgt    5    18,191 ±  0,290  ns/op
ConversionBenchmark.lng             RAVEL  avgt    5    33,366 ±  0,872  ns/op
ConversionBenchmark.lng          SMALLRYE  avgt    5    35,876 ±  0,095  ns/op
ConversionBenchmark.stringArray     RAVEL  avgt    5   507,593 ± 92,541  ns/op
ConversionBenchmark.stringArray  SMALLRYE  avgt    5   699,053 ± 30,111  ns/op
ExpressionBenchmark.deep            RAVEL  avgt    5  1422,975 ± 82,388  ns/op
ExpressionBenchmark.deep         SMALLRYE  avgt    5    16,095 ±  0,114  ns/op
ExpressionBenchmark.literal         RAVEL  avgt    5    14,288 ±  0,154  ns/op
ExpressionBenchmark.literal      SMALLRYE  avgt    5    16,085 ±  0,301  ns/op
ExpressionBenchmark.oneLevel        RAVEL  avgt    5   925,379 ±  4,135  ns/op
ExpressionBenchmark.oneLevel     SMALLRYE  avgt    5    14,562 ±  0,388  ns/op
LookupBenchmark.hit_Optional        RAVEL  avgt    5    22,258 ±  1,202  ns/op
LookupBenchmark.hit_Optional     SMALLRYE  avgt    5    20,107 ±  0,123  ns/op
LookupBenchmark.hit_String          RAVEL  avgt    5    19,270 ±  0,294  ns/op
LookupBenchmark.hit_String       SMALLRYE  avgt    5    14,855 ±  0,169  ns/op
LookupBenchmark.miss_Optional       RAVEL  avgt    5    17,665 ±  4,391  ns/op
LookupBenchmark.miss_Optional    SMALLRYE  avgt    5    16,356 ±  1,535  ns/op
```

Detailed JSON: `ravel-bench/target/jmh-result.json` (not versioned — `target/`).

### Ravel ↔ Smallrye Comparison (Δ = (Ravel − Smallrye) / Smallrye)

| Benchmark | Ravel | Smallrye | Δ | Verdict |
|---|---:|---:|---:|---|
| **Lookup** |  |  |  |  |
| `hit_String` | 19.3 ns | 14.9 ns | +30% | Ravel slower |
| `hit_Optional` | 22.3 ns | 20.1 ns | +11% | Ravel slightly slower |
| `miss_Optional` | 17.7 ns | 16.4 ns | +8% | Ravel slightly slower |
| **Conversion** |  |  |  |  |
| `bool` | 9.9 ns | 15.5 ns | **−37%** | Ravel faster |
| `integer` | 14.8 ns | 18.2 ns | **−19%** | Ravel faster |
| `lng` | 33.4 ns | 35.9 ns | −7% | Ravel faster |
| `duration` | 157.6 ns | 168.7 ns | −7% | Ravel faster |
| `stringArray` | 507.6 ns | 699.1 ns | **−27%** | Ravel faster |
| **Expressions** |  |  |  |  |
| `literal` (no `${...}`) | 14.3 ns | 16.1 ns | −11% | Ravel faster |
| `oneLevel` (`${a}`) | 925 ns | 14.6 ns | **+6240%** | Ravel **63×** slower |
| `deep` (3 levels) | 1423 ns | 16.1 ns | **+8740%** | Ravel **88×** slower |

### Analysis

- **Simple lookups**: Ravel pays ~5 ns on `hit_String` (~30% overhead vs Smallrye).
  Likely cause: source cascade re-sorted on each build vs Smallrye's pre-flattened structure,
  no per-key cache. No immediate gain target: these are 4–5 ns absolute, within the noise
  floor of a real application.
- **Conversions**: Ravel consistently outperforms Smallrye, including +37% on `bool` and
  +27% on `String[]`. Cause: built-in converters as monomorphic inlined methods
  (see `BuiltInConverters`), no intermediate delegate chain.
- **Expressions**: **critical gap on `oneLevel`/`deep`**. Ravel re-resolves the expression
  on every call (`resolveTemplate` → `lookupRaw` cascade), whereas Smallrye caches the
  resolved result. To address in M6+ (LRU cache on `<key, sourceVersion> → resolved`
  that invalidates when the profile or sources change — see the eventual ADR).
- **`literal`**: no expansion so falls back to pure lookup cost, consistent with the
  `hit_String` line (Ravel −11% via the fast-path `raw.indexOf('$') < 0`).

### Delta vs Previous Run

First formal run published in `BENCH.md`. The preliminary figures
mentioned in commit `1497a32` (`hit_String` Ravel ~22 ns, Smallrye ~15 ns,
1 fork × 1 iter) are **within the margins** of this more rigorous run
(3 warmup × 5 measurement → Ravel 19.3 ns ± 0.3, Smallrye 14.9 ns ± 0.2). No
regression relative to that pre-baseline.

### Reproduction

```bash
# From the ravel/ root
./mvnw -pl ravel-bench -am -ntp package -DskipTests
cd ravel-bench
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1 -tu ns -bm avgt \
     -rf json -rff target/jmh-result.json
```

To target a subset: `java -jar target/benchmarks.jar '.*ExpressionBenchmark.*'`
(regex on class name).

To profile hotspots (perfasm on Linux/x86, dtraceasm on macOS):
```bash
java -jar target/benchmarks.jar -prof gc                  # allocs/op
java -jar target/benchmarks.jar -prof stack:lines=10      # stack sampler
```

### Tasks Identified by This Run

1. **(M6 or dedicated ADR)** Implement a per-key expression resolution cache to close the
   60–90× gap on `oneLevel`/`deep`. Must invalidate on profile change and source cascade
   change (rare outside tests).
2. **(M6 backlog)** Profile `LookupBenchmark.hit_String` to identify the source of the 4–5 ns
   gap vs Smallrye — possibly the sorted `List<ConfigSource>` vs a pre-indexed flat structure.
   Do not optimise without a profiler trace.
3. **(future improvement)** Add `-prof gc` to the default runner to track allocation overhead
   per op on hot benchmarks.
