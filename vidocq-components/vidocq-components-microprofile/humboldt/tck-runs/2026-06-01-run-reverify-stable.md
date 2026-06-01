# MicroProfile Telemetry 2.1 TCK — re-verification run (2026-06-01)

## Goal

Re-run the official tracing suite to confirm the 85/85 PASS result from
2026-05-24 is still stable on `main` (no regression introduced by the
post-M7c commits — `c2c9380` ship `META-INF/vauban-beans.list` (Weld-safe),
`324e73a` bump chappe to 0.2.0-SNAPSHOT).

## Environment

- **Hardware**: local workstation (macOS 25.5.0)
- **JVM**: Temurin 25+36 LTS
- **Maven**: 3.9.16 (via `.sdkmanrc`)
- **Branch**: `main` (2 commits ahead of `origin/main`, tip `c2c9380`)
- **Command**: `./run-official-tck-telemetry-2.1.sh all`

## Result

```
[INFO] Running TestSuite
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 237.2 s -- in TestSuite
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:58 min
```

**85/85 PASS — 100% applicable.** No regression vs 2026-05-24.

## Reactor sanity check (preceding step)

`./mvnw -ntp clean install -DskipTests` — 15 modules SUCCESS in 2.5s.
`./mvnw -ntp verify` — **105/105 unit tests PASS** across 14 test-bearing
modules, 0 failure, 0 error, 0 skipped.

## Conclusion

M7 (official MicroProfile Telemetry 2.1 TCK) remains green. The next open
milestones are **M8 (perf benchmarks vs SmallRye Telemetry / OTel SDK)** and
**M9 (migration guide + 1.0.0 release)** — see `ROADMAP.md`.
