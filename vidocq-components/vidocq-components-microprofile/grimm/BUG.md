# Bug Tracker

Track reproducible bugs with:
- id
- date
- symptom
- minimal repro
- hypothesis
- status

## Entries

### BUG-20260715-01 — grimm-tck references stale vidocq-runtime groupIds

- **id**: BUG-20260715-01
- **date**: 2026-07-15
- **symptom**: `./run-official-tck-mp-openapi-4.1.sh all` fails before running any test:
  `Could not resolve dependencies for io.vidocq.grimm:grimm-tck` —
  `Could not find artifact io.vidocq.runtime:vidocq-runtime-cassini-rest-extension:jar:0.3.0-SNAPSHOT`.
- **minimal repro**: build vidocq runtime `main` (`mvn install`), then run the grimm TCK script.
- **hypothesis (confirmed)**: the vidocq runtime module rework of 2026-06-08
  ("first rework of modules") moved `vidocq-runtime-cassini-rest-extension` to groupId
  `io.vidocq.runtime.extensions.jakartaee.core` and `vidocq-runtime-chappe-webserver-extension`
  to `io.vidocq.runtime.extensions.essentials`. `grimm-tck` (out-of-reactor) still declared the
  old `io.vidocq.runtime` groupId, which is no longer installed locally; resolution then falls
  back to the invalid snapshot on central-snapshots (see vidocq/BUG.md) and fails.
  `vidocq-runtime-core` did not move.
- **status**: FIXED — grimm-tck/pom.xml updated to the new groupIds;
  full MP OpenAPI 4.1 TCK green again (349 tests, 0 failures, 0 errors, 0 skipped).

