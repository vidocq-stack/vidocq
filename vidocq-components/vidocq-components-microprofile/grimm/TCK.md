# TCK Scoreboard

Track MicroProfile OpenAPI 4.1 TCK progress here.

## Current score

- Status: M10 bootstrap in progress
- Baseline known from `ROADMAP.md`: 353 tests, 317 failures, 29 skipped
- Current blocking families:
  - container deployment/wiring edge cases (archive descriptors)
  - endpoint reachability intermittence (`ConnectException`)
  - functional OpenAPI assertions once deployment is up (e.g. missing expected paths/responses)

Latest targeted run (`PetStoreAppTest`):

- deployment now succeeds (no `Invalid class descriptor`, no `Unsatisfied dependency`)
- HTTP endpoint is reachable (`/openapi` responds)
- remaining failures are model-content mismatches (assertions on `paths`, `responses`, security blocks)

## Runtime matrix (Cassini/Chappe)

The TCK harness now supports a small execution matrix to isolate infra regressions:

| Variant | Goal | Key properties |
|---|---|---|
| `default-readiness` | baseline stable startup | `grimm.tck.port=0`, `grimm.tck.waitForReadiness=true`, `grimm.tck.readinessTimeoutMillis=10000` |
| `extended-readiness` | detect slow bootstrap vs hard failures | same as above with `readinessTimeoutMillis=30000` |
| `no-readiness-probe` | compare behavior without HTTP readiness gate | `grimm.tck.waitForReadiness=false` |

Entrypoints:

- `./run-official-tck-mp-openapi-4.1.sh smoke`
- `./run-official-tck-mp-openapi-4.1.sh matrix PetStoreAppTest`
- `./run-official-tck-mp-openapi-4.1.sh all`

## Harness knobs

Arquillian container `grimm` accepts:

- `host` (default `127.0.0.1`)
- `port` (default `0` for ephemeral)
- `waitForReadiness` (default `true`)
- `readinessTimeoutMillis` (default `10000`)
- `readinessPath` (default `/openapi`)
- `systemProperties` (semicolon-separated `key=value` list injected during deployment)

These can be overridden per run with JVM properties `-Dgrimm.tck.*`.

## Exclusions

Document any excluded tests with spec-based justification.

