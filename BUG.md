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

### Related — latent, NOT yet fixed

`vidocq-runtime-cyrano-extension` has the **same** shape (own BCE
`io.vidocq.runtime.ext.cyrano.CyranoBuildCompatibleExtension` in a non-opened package) and will fail
the same way once a Cyrano-enabled app boots on the module path. The one-line `opens ... to
io.vidocq.vauban.core` fix additionally needs `io.vidocq.vauban.core` reachable in the wrapper's
compile module graph (it is not today — compiling the open emits "module not found: io.vidocq.vauban.core"),
so the wrapper likely needs `requires io.vidocq.runtime.spi`/the vauban module on its path first. Left
unfixed here because no current app exercises Cyrano on the module path and it could not be verified
end-to-end; track and fix when Cyrano is first deployed under strict JPMS.
