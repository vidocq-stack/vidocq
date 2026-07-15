# BUG.md

Tracking of reproducible bugs. Compliant with Vidocq conventions (AGENTS.md § "Reproducible bugs tracked in BUG.md").

| id | date | symptom | repro | hypothesis | status |
|---|---|---|---|---|---|
| BUG-001 | 2026-05-15 | **Potential virtual thread leak** under `@Asynchronous + @Timeout` with a long-running `CompletionStage` | Method `@Asynchronous @Timeout(100ms)` returning `CompletableFuture.supplyAsync(() -> Thread.sleep(5000))`: `TimeoutEngine` stops after 100ms and throws `TimeoutException`, but the unwrap thread (`stage.toCompletableFuture().get()`) keeps blocking for up to 5s before being collected | The `get()` call is not interruptible via `Thread.interrupt()` on all `CompletableFuture` implementations (depends on the underlying chain). With virtual threads, the leak is minor (a few KB of memory) and resolves on its own when the task expires. Acceptable until a future optimization with `StructuredTaskScope` (finalized in Java 25) if benchmarks justify it. | ⚠️ accepted — optional M10+ optimization |
| BUG-002 | 2026-05-25 | **Java Modules bypassed**: `maven-dependency-plugin` copies compile-scope JARs into `target/javamodules/` and the compiler receives a manual `--module-path` instead of relying on Maven native Java Modules resolution | `grep "javamodules" pom.xml` reveals the config ; remove the `maven-dependency-plugin` section and rebuild to observe the `module not found` errors | `microprofile-fault-tolerance-api`, `vauban-core`/`vauban-classloader-spi`, and `jmh-core` do not have a `module-info.class` recognized by `maven-compiler-plugin` 4.x for automatic placement on the module path | ⚠️ OPEN — workaround active ; to be removed module by module as upstream dependencies become modularized or wrapped (see the `heisenberg-mp-ft-api` pattern) |

## Notes

- Bugs marked ⚠️ are known and tolerated behaviors (low impact) until a planned architectural migration.
- Critical bugs (🔴) must be fixed before merging the corresponding milestone.
