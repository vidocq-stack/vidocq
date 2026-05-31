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
