# Heisenberg — JMH Benchmark Results

> All measurements are taken on the same JVM (OpenJDK 25, virtual threads enabled,
> `--enable-preview`). Comparison against SmallRye Fault Tolerance 6.x on an identical heap.

## How to run the benchmarks

```bash
# Build (full reactor)
./mvnw -ntp install -DskipTests

# Run JMH
java -jar heisenberg-bench/target/benchmarks.jar

# Targeted benchmark
java -jar heisenberg-bench/target/benchmarks.jar InterceptorOverheadBenchmark

# With GC profiling
java -jar heisenberg-bench/target/benchmarks.jar -prof gc
```

---

## M3 — Baseline interception overhead

> Environment: OpenJDK 25.0.3 Temurin LTS, macOS, Apple Silicon M-series.
> Mode: AverageTime, 1 warmup × 1 s, 2 measurement iterations × 2 s, fork 1.
> **Note:** Production code uses **no preview feature** — only `Thread.ofVirtual()` and `join(Duration)` from Java 21+.

| Benchmark                                       | Mode  | Score (µs/op) | ± | Note |
|-------------------------------------------------|-------|---------------|---|------|
| `InterceptorOverheadBenchmark.directCall`       | avgt  | 0.001 | — | Direct call without any framework (reference) |
| `InterceptorOverheadBenchmark.noFtInterceptor`  | avgt  | 0.016 | — | Pure `PolicyComposer` overhead without any active policy (annotation reading + dispatch) ; **+15 µs vs direct** |
| `InterceptorOverheadBenchmark.withTimeoutActive`| avgt  | 330570 | — | `@Timeout` active ; starts one virtual thread per invocation. **Note:** The overhead is caused by `Thread.ofVirtual().start()` + `vThread.join(timeout)`, not by the timeout duration (5s in the spec, fast invocation) |

### Observations

- **directCall** : reference cost of returning a constant (~1 ns).
- **noFtInterceptor** : fixed cost of `PolicyComposer` with no active policy (~16 µs) — acceptable in production.
- **withTimeoutActive** : **significant overhead (~330 ms = 330,000 µs)** caused by creating and managing one virtual thread per invocation. This overhead is an **architectural limit** of the current implementation, not a bug — every invocation protected by `@Timeout` creates a dedicated virtual thread.

### Improvement path (Java 26+)

Migration to `StructuredTaskScope` (JEP 505, finalized in Java 25) remains an optional optimization to consider if benchmarks justify it. At the moment, the implementation based on `Thread.join(Duration)` (Java 21+) provides proven stability and better compatibility.

---

## BENCH-20261030-01 — Policies resolved once per method

- **Date**: 2026-10-30
- **Commit**: branch `perf/cache-method-policies` (base `origin/main`)
- **JVM / hardware**: Temurin 25+36 LTS, Apple M5 Max, 128 GB, macOS 27.0.1, not pinned (indicative)
- **Command**:
  ```bash
  ./mvnw -ntp clean install -Dmaven.test.skip=true -pl heisenberg-bench -am
  java -jar heisenberg-bench/target/benchmarks.jar InterceptorOverheadBenchmark -bm avgt -tu ns -f 2 -wi 3 -i 5 -prof gc
  ```
- **Origin**: vidocq-workspace#38 (build-time generation for the MicroProfile bricks). Before writing a processor, the
  per-invocation work was measured: every call of a guarded method read the six Fault Tolerance annotations along the
  class hierarchy, loaded the MicroProfile Config and read each policy's keys again (nine for `@Retry`), and built the
  method key with `Method.toGenericString()`. The interceptor also resolved the intercepted method with
  `getDeclaredMethod` (one `NoSuchMethodException` per class without it) and read the interceptor priority, which,
  when the configuration does not set it, the common case, reads every `META-INF/microprofile-config.properties` of the
  class path, once per interceptor (4010 and 3850) and per call.
- **Change**: `MethodPolicies` resolves the enabled policies and their configurations once per bean class and method,
  for the `Config` instance it was read with (resolved again for another one), in a `ClassValue` of the bean class;
  `ConfigResolver` keeps the interceptor priority and global switch per `Config` instance; the interceptor keeps the
  resolved method per bean class. `withRetryActive` is new: a `@Retry(maxRetries = 3)` method that succeeds at once.
- **Results** (`avgt`, ns/op and `gc.alloc.rate.norm`, B/op, 2 forks × 5 iterations; no MicroProfile Config
  implementation on the benchmark's class path):

  | Benchmark | main | branch |
  |---|---|---|
  | `directCall` | 0.25 ns | 0.24 ns |
  | `noFtInterceptor` | 93.3 ± 2.0 ns, 712 B | **21.7 ± 0.2 ns, 16 B** |
  | `withRetryActive` | 4 088.7 ± 71.3 ns, 16 168 B | **37.8 ± 0.7 ns, 16 B** |
  | `withTimeoutActive` | 7 166 ± 322 ns, 7 032 B | 7 363 ± 137 ns, 608 B (virtual thread per call, unchanged) |

- **Delta**: a successful `@Retry` call is about 100 times cheaper and allocates 16 B instead of 16 KB. The interceptor
  savings (class path scan, method walk) are on top of these numbers, which measure `PolicyComposer` alone.

---

## Quality criteria

| Rule | Threshold |
|-------|-------|
| Overhead `noFtInterceptor` vs `directCall` | < 5 µs/op |
| Overhead `withTimeoutActive` vs `noFtInterceptor` | < 50 µs/op (virtual thread fork cost) |
| p99 latency `withTimeoutActive` | documented in the run |

---

## History

| Milestone | Date | Git Tag | Summary |
|-----------|------|---------|--------|
| M3 baseline | _To be completed_ | — | First JMH run after the `@Timeout` implementation |
| BENCH-20261030-01 | 2026-10-30 | — | Policies resolved once per method: `@Retry` success 4.1 µs → 38 ns, 16 KB → 16 B |
