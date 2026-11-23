# Bugs — Noether Validation

Reproducible implementation defects in the provisional local provider.
Convention: workspace `AGENTS.md`. Status: OPEN → INVESTIGATING → FIXED → CLOSED.

## BUG-20261123-57 — The Validation TCK script installs the branch into the shared `~/.m2`

- **Date**: 2026-11-23 (same bug in the Persistence runner, see `noether-persistence/BUG.md`, and in Foy, Peano,
  Ibarra, Bodoni, Cassini, Champollion, Mansart and Kerckhoffs)
- **Status**: FIXED (pending commit)
- **Symptom**: `noether-validation-tck/run-official-tck-validation-3.1.sh` ran
  `(cd ../.. && ./mvnw -q -B -ntp -pl noether-validation/noether-validation-core -am install -DskipTests)`, then the
  TCK, on the default local repository, `~/.m2/repository`, shared by every build and session of the machine: a run
  from a branch replaced the published `noether-validation-core` snapshot there for every other build.
- **Repro**: reading the script (the runner is a standalone POM, outside the reactor: no test runs it).
- **Cause**: the script predates the workspace rule "never install into `~/.m2` from a branch build".
- **Fix**: both Maven calls take `-Dmaven.repo.local=<Noether folder>/.tck-repository
  -Dmaven.repo.local.tail=$HOME/.m2/repository` (`TCK_LOCAL_REPOSITORY` moves the first), the same private
  repository as the Persistence runner. `.tck-repository/` is git-ignored.

## BUG-20261018-01 — TraversableResolver reachability cache ignores the bean path

- **Date**: 2026-10-18
- **Status**: FIXED on fix/traversable-path-cache
- **Affected module**: `noether-validation-core`, `engine.ValidationRun.reachable`
- **Symptom**: a path-dependent resolver answer for a shared cascaded instance
  is reused at another path, producing violations for a path the resolver forbids.
- **Minimal reproduction**: root fields `a` and `b` both have `@Valid` and point
  to the same child with a null `@NotNull name`. A resolver forbids traversal
  under path `b`. Validation produces `a.name` and `b.name`, instead of only `a.name`.
- **Confirmed cause**: the per-bean reachability cache key includes the operation
  and member name but omits the resolver's path input.
- **Investigations**:
  - 2026-10-18: a scratch regression using the real validator reproduces both
    violations for the shared instance; distinct child instances correctly
    produce only `a.name`. Resolver tracing confirms no request for the shared
    child's second path. Evidence: session artifact
    `java25-review/b/src/repro/PathReachabilityRepro.java` and `b/output.txt`.
    No production fix or tracked regression has been applied.
  - 2026-11-15: reproduced by `ValidatorEngineTest.aSharedCascadedInstanceIsReachableOrNotAtEachPathOnItsOwn`
    (red: `a.name` and `b.name`). Fixed: the per-bean cache is now keyed by the property kind, its member
    name and the path it is reached at, so the resolver is asked once per property and path.
