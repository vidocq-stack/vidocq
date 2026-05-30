# Run TCK MP Telemetry 2.1 — post M5b (JUL bridge + LoggingLogRecordExporter)

**Date** : 2026-05-23 20:53
**Command** : `./run-official-tck-telemetry-2.1.sh all`
**Duration** : ~3 min

## Results

```
<testng-results ignored="0" total="85" passed="9" failed="48" skipped="28">
```

## Progress

| Run | Date | total | PASS | FAIL | SKIP |
|---|---|---|---|---|---|
| post M7c.1+2+4 | 2026-05-23 00:36 | 93 | 5 | 60 | 28 |
| post M7c.8 | 2026-05-23 20:32 | 85 | 7 | 50 | 28 |
| **post M5b** | 2026-05-23 20:53 | **85** | **9** | **48** | **28** |

Net M5b delta: **+2 PASS, -2 FAIL**.

## Items delivered in M5b

- **`HumboldtJulHandler`** (humboldt-sdk-log/bridge): JUL → OTel Logger bridge.
  Severity mapping (SEVERE→ERROR, WARNING→WARN, INFO→INFO, CONFIG/FINE→DEBUG,
  FINER→DEBUG2, FINEST→DEBUG3). Logger cached by scope name. Idempotent.
- **`LoggingLogRecordExporter`** (humboldt-sdk-log/export): file-based exporter
  compliant with the format expected by the MP Telemetry Logs TCK:
  ```
  YYYY-MM-DD HH:MM:SS.SSS LEVEL <body> scopeInfo:<scope>:<version>
  ```
  Path resolved via system property `mptelemetry.tck.log.file.path`, fallback stdout.
- **`HumboldtAutoConfigure` wiring**:
  - case `OTEL_LOGS_EXPORTER=logging` → `LoggingLogRecordExporter.create()`
  - `SimpleLogRecordProcessor` (synchronous) for `in-memory` AND `logging` (the TCK reads
    the file immediately, no deferred batch flush)
  - Auto-install of `HumboldtJulHandler` on the root JUL logger when the log pipeline
    is in production mode (otlp or logging) — **not in in-memory** to avoid internal
    runtime logs polluting the tests' InMemoryLogRecordExporter
  - Idempotent: does not reinstall if a `HumboldtJulHandler` is already present
- **`humboldt-tck` POM**: `systemPropertyVariables` added to the `tck-official` profile
  to pass `mptelemetry.tck.log.file.path=${project.build.directory}/humboldt-tck-logs.txt`

## Tests confirmed PASS

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 (JulTest)
```

- ✅ `JulTest.julInfoTest` — JUL INFO log appears in file with the right format
- ✅ `JulTest.julWarnTest` — JUL WARNING log (mapped WARN) appears

Example generated output (`target/humboldt-tck-logs.txt`):
```
2026-05-23 18:52:59.084 INFO a very distinguishable info message scopeInfo:jul-logger:
2026-05-23 18:53:04.102 WARN a very distinguishable warning message scopeInfo:jul-logger:
```

## Regression avoided

- Unit test `HumboldtAutoConfigureTest.in_memory_pipeline_exports_traces_metrics_logs_end_to_end`
  initially failed because JulHandler intercepted the final init `LOG.log(...)`
  and emitted 2 records instead of 1. Fix: conditional install
  (`logging` or `otlp` only, not `in-memory` or `none`).

## Next steps (ordered by remaining impact)

| Step | Item | Estimated gain | Effort |
|---|---|---|---|
| 1 | **M7c.9** JAX-RS `@Context` injection in CassiniHarness | +5-8 | Medium |
| 2 | **M7c.7** JAX-RS ClientFilter (CLIENT spans) | +6 | Medium |
| 3 | **M7c.5** Cyrano MP Rest Client integration | +12-18 | Medium-large |
| 4 | **M4b** full SDK metric | +24 | Large |
| 5 | Cluster D autoconfigure SPI (deferred?) | +3 | To arbitrate |
