# Humboldt — Benchmarks

> Every performance number (JMH, wrk, comparison vs SmallRye Telemetry / OTel SDK Java, etc.) must be recorded here. No number in a README or commit message without a matching entry. See the workspace root `CLAUDE.md` for the convention.

## Entry format

```
### [HBT-BNCH-N] Titre court
- **Date** : YYYY-MM-DD
- **Hardware**: CPU model, RAM, OS
- **JVM**: Java 25 Temurin / GraalVM CE 24 / …
- **Commit**: Humboldt SHA + compared dependency SHAs
- **Tool**: JMH / wrk / custom
- **Exact command**: reproducible copy/paste
- **Raw results**: table ops/s, p50/p99 latency, allocations, etc.
- **Delta vs previous run**: %
- **Analysis**: interpretation, hotspots, ADR to create
```

---

_No benchmark has been run yet (M0)._
