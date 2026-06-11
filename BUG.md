# BUG — Vidocq Runtime

Tracking reproducible bugs in the Vidocq runtime (orchestrator + extension wrappers).
Vidocq workspace convention: short id, date, symptom, minimal repro, cause hypothesis, status.

---

## VID-001 — Cervantes JWT extension wrapper BCE not openable on the module path

- **Opening date**: 2026-06-02
- **Status**: ✅ FIXED 2026-06-02

### Symptom

Strict module-path boot (Arago Docker) fails:

```
BCE processing failed for io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension:
class io.vidocq.vauban.core.extensions.BceProcessor (in module io.vidocq.vauban.core) cannot access
... because module io.vidocq.runtime.ext.cervantes.jwt does not export io.vidocq.runtime.ext.cervantes
to module io.vidocq.vauban.core
```

Does not reproduce on the class-path (the cervantes acceptance/TCK run there).

### Cause

The wrapper module `io.vidocq.runtime.ext.cervantes.jwt` declares its own
`BuildCompatibleExtension` in the internal package `io.vidocq.runtime.ext.cervantes` and registers it
via `provides ... with`. vauban-core's `BceProcessor` instantiates BCEs by direct reflection
(`getDeclaredConstructor().newInstance()`), which needs the hosting package **opened** to vauban-core;
`provides ... with` alone only grants `ServiceLoader` access. The package was neither exported nor opened.

### Fix

`vidocq-runtime-cervantes-jwt-extension/module-info`:
`opens io.vidocq.runtime.ext.cervantes to io.vidocq.vauban.core;` (qualified — mirrors knock/dirac).

Verified: Arago Docker boots; cervantes MP-JWT 2.1 TCK 206/206 PASS.

### Related — cyrano wrapper: ✅ FIXED 2026-06-11

`vidocq-runtime-cyrano-rest-client-extension` had the **same** shape (own BCE in a non-opened
package). Fixed with the same one-line `opens io.vidocq.runtime.extensions.microprofile.cyrano to
io.vidocq.vauban.core` — the compile-graph concern recorded earlier did not materialize:
`io.vidocq.vauban.core` is reachable transitively through `io.vidocq.cyrano.cdi.vauban`, exactly as
in the cervantes wrapper, and the opens compiles as-is.

**Verified end-to-end this time**: new reactor module `vidocq-runtime-it-cyrano-jpms` boots the
Vidocq runtime with the wrapper ON THE MODULE PATH (main module-info → surefire module path, plus a
named-module sentinel against silent class-path degradation). The vehicle was proven by mutation:
without the opens the boot test fails with the exact VID-001 symptom; with it, green. This also
gives the runtime its first module-path IT — every other integration test runs on the class path,
where this whole bug class is invisible.
