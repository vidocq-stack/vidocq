# BUG — Dirac

Tracking reproducible bugs in `dirac` (internal issues, regressions, incorrect behaviors
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, cause hypothesis, status.

---

## DRC-001 — JPMS bypassed via manual copy of compile-scope JARs

- **Opening date**: 2026-05-25
- **Status**: ⚠️ OPEN — active workaround

### Symptom

The dirac root `pom.xml` uses `maven-dependency-plugin` (`initialize` phase) to
copy all compile-scope JARs to `target/javamodules/`, then passes
`--module-path ${project.build.directory}/javamodules` manually to the compiler.

This workaround indicates that Maven's native JPMS resolution doesn't work for
some of dirac's compile-scope dependencies, notably `microprofile-metrics-api` and
`vauban-core`/`vauban-classloader-spi`.

### Minimal repro

```bash
grep -n "javamodules\|module-path" dirac/pom.xml
# reveals the two manually configured plugins
```

Without the workaround (removing the `maven-dependency-plugin` config), `javac` fails with:

```
error: module not found: org.eclipse.microprofile.metrics
```

### Cause hypothesis

The concerned JARs don't have their own `module-info.class` — they only expose an
`Automatic-Module-Name` in their `MANIFEST.MF`. Version 4.x of `maven-compiler-plugin`
doesn't automatically place them on the `--module-path` for projects having an
explicit `module-info.java`. The copy to `target/javamodules/` allows javac to
resolve them as automatic modules by deriving their name from the JAR file name.

### Resolution path

1. Check if upstream versions of `microprofile-metrics-api` (3.x → 4.x?) publish
   a `module-info.class`. If yes, bump the version and remove the workaround.
2. Contact / PR upstream Eclipse MicroProfile to add a modular descriptor.
3. Failing that, wrap via an internal Dirac module (`dirac-mp-metrics-api`) that provides
   the missing `module-info.class` — pattern already used for `ravel-mp-config-api`.
