# M0: Scaffold JKP TCK Runner Module

## What was done
- Created `mansart-jakarta-persistence/pom.xml` — empty aggregator, no sub-modules.
- Created `mansart-jakarta-persistence/mansart-jakarta-persistence-tck/pom.xml` — standalone Model 4.0.0 POM, NOT in reactor.
  - Depends ONLY on `jakarta.persistence-api:3.2.0` + `jakarta.tck:persistence-tck-spec-tests:3.2.1`.
  - NO Mansart modules, NO backing DBs, NO CDI, NO Bean Validation (M1+).
  - Profiles: `tck-run` (full TCK), `tck-sig` (signature subset), `tck-pg` (PostgreSQL via testcontainers).
- Created `run-official-tck-jkp-3.2.sh` — execution script (placeholder, functional when M1 modules exist).
- Created `tck-suite.xml` — empty TestNG suite skeleton.
- Registered `mansart-jakarta-persistence` in root `pom.xml` `<modules>`.

## Why the first build failed
The initial TCK POM declared dependencies on non-existent Mansart modules (`-core`, `-cdi`, `-dialect-h2`, `h2`, `postgresql`, `testcontainers`, `vauban-indexer`, `mansart-jakarta-persistence-tests`). Per M0 rules: "DO NOT depend on implementation modules that do not exist yet."

## Fix
- Removed all Mansart dependencies from the TCK POM.
- Removed the TCK module from the parent reactor (it is standalone, Model 4.0.0, no `<parent>`).
- Parent module is now an empty aggregator.

## Build result
`./scripts/build.sh install` — OK (exit 0), 10 tests, 0 failures.

## Next (M1)
- Create `mansart-jakarta-persistence-api` (Jakarta Persistence API 3.2.0 — Jakarta EE 11 spec).
- Wire `mansart-jakarta-persistence-api` into the TCK POM.
- Add Arquillian extension for persistence (`mansart-arquillian-persistence`).
