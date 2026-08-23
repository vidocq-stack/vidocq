# BUG — Knock

Tracking of reproducible bugs in `knock` (internal issues, regressions, incorrect behaviours
not yet fixed). Vidocq workspace convention: short id, date, symptom,
minimal repro, root cause hypothesis, status.

---

## BUG-001 — `tck-mp-health` crashes: `cassini-tck:0.1.0-SNAPSHOT` not found

- **Opened**: 2026-05-10
- **Resolved**: 2026-05-11
- **Status**: ✅ RESOLVED

### Symptom

The `CI / tck-mp-health` job in knock's `ci.yml` consistently fails during the Maven
resolution phase:

```
[ERROR] Failed to execute goal on project knock-tck:
  Could not resolve dependencies for project io.vidocq.knock:knock-tck:jar:0.1.0-SNAPSHOT:
  The following artifacts could not be resolved:
    io.vidocq.cassini:cassini-tck:jar:0.1.0-SNAPSHOT (absent)
  → vidocq-snapshots: HTTP 404
```

On the Forgejo run side: status `CI / tck-mp-health (push) | failure | Failing after 37s` on
all `main` pushes prior to 2026-05-11.

### Minimal Repro

```bash
cd ~/projects/perso/vidocq/knock
./mvnw -ntp -pl knock-api,knock-core,knock-cdi-vauban,knock-cassini -am install -DskipTests
./run-official-tck-mp-health-4.0.sh all
# → fails at the "mvn test -f knock-tck/pom.xml" phase
```

### Root Cause

`knock-tck/pom.xml` (line 263) declares in `<scope>test</scope>`:

```xml
<dependency>
  <groupId>io.vidocq.cassini</groupId>
  <artifactId>cassini-tck</artifactId>
  <version>${cassini.version}</version>
  <scope>test</scope>
</dependency>
```

However `cassini-tck` is itself an **out-of-reactor** module in cassini (standalone POM Model 4.0.0,
ShrinkWrap Maven Resolver vs Model 4.1 constraint). The `mvn deploy` of the cassini
reactor **did not include it** → never published to Reposilite → resolution fails
on the knock side.

### Temporary Workaround (before fix)

`deploy needs: build` (and **not** `needs: tck-mp-health`) in knock's `ci.yml` to
allow publishing knock snapshots despite the failing TCK — knock had to remain
consumable by `vidocq` even while the TCK was red.

### Applied Fix

**Commit**: `cassini@1f9e6000` — added a dedicated step to cassini's `ci.yml`, after
the `Deploy to Forgejo Maven registry` step:

```yaml
- name: Build and deploy cassini-tck (out-of-reactor)
  run: |
    mvn -B -ntp -f cassini-tck/pom.xml \
        deploy -Dmaven.test.skip=true \
        -DaltDeploymentRepository=vidocq-snapshots::https://repo.vidocq.dev/snapshots
```

Key points:

- `-f cassini-tck/pom.xml` points to the standalone POM (Model 4.0.0 remains unchanged)
- `-Dmaven.test.skip=true` (not `-DskipTests`) avoids resolution of test deps, including
  `jakarta-restful-ws-tck` (non-public Jakarta artifact)
- `-DaltDeploymentRepository=id::url` redirects the deploy to Reposilite without modifying
  the standalone pom (which has no `<distributionManagement>`)

**Second commit**: `knock@6086572a` — restores `deploy needs: [build, tck-mp-health]`
in knock's `ci.yml` (release gate on TCK 100% PASS).

### Validation

Full pipeline `knock@6086572a` (2026-05-11 17:16-17:17):

| Job | Status | Duration |
| --- | --- | --- |
| `build` | ✅ | 16s |
| `tck-mp-health` | ✅ TCK PASS 100% | 33s |
| `deploy` | ✅ (runs after tck-mp-health) | 17s |

Total: 1m06s. The `deploy needs: [build, tck-mp-health]` gate is enforced.

### External References

- `Forge-New/99-Annexes/Gotchas-Java-Maven.md` §2 (generic recipe for out-of-reactor TCKs)
- `Forge-New/60-CICD-Cross-Repo/Workflow-ci.yml.md` (section "Publishing out-of-reactor TCKs")
- `cassini/.forgejo/workflows/ci.yml` (step added at line ~71)

---

## BUG-002 — Java Modules workaround via manual copy of compile-scope JARs

- **Opened**: 2026-05-25
- **Status**: ⚠️ OPEN — active workaround

### Symptom

The root `pom.xml` of knock uses `maven-dependency-plugin` (phase `initialize`) to
clean and repopulate `target/javamodules/` with all compile-scope JARs, then passes
`--module-path ${project.build.directory}/javamodules` manually to the compiler.

This workaround indicates that Maven's native Java Modules resolution does not work for
certain compile-scope dependencies of knock, notably `champollion-jsonp`, `cassini-core`
and `vauban-core`/`vauban-classloader-spi`.

### Minimal Repro

```bash
grep -n "javamodules\|module-path" knock/pom.xml
# reveals maven-clean-plugin + maven-dependency-plugin + compilerArgs
```

Without the workaround, `javac` fails to resolve the `io.vidocq.champollion`
or `io.vidocq.cassini` modules from the `module-info.java` of the knock sub-modules.

### Root Cause Hypothesis

The affected JARs do not have a `module-info.class` in a version recognised by
`maven-compiler-plugin` 4.x for automatic placement on `--module-path`. Copying
to `target/javamodules/` forces javac to treat them as automatic modules. The
`maven-clean-plugin` in phase `initialize` prevents stale JAR conflicts during
incremental builds.

### Resolution Path

Check module by module which ones have an explicit Java Modules descriptor and which ones
only have an `Automatic-Module-Name` — then remove the corresponding entries from the
workaround as upstream modules are fixed.

## BUG-20260712-01 — hardcoded implementation version constant in the published api artifact

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : Knock.IMPLEMENTATION_VERSION (knock-api/Knock.java)
- **Symptôme** : the artifact published on Maven Central as 0.2.0 reports a hardcoded
  "0.1.0-SNAPSHOT" implementation version — the constant was maintained by hand and never
  updated by the release train. Same class as vidocq BUG-20260704-01 (CLI banner).
- **Reproduction minimale** : read the constant from the published 0.2.0 jar.
- **Hypothèse de cause** : compile-time constant, no build filtering.
- **Investigations** :
  - 2026-07-12 : found by grepping for stale version strings after the issue #3 follow-up.
    Fixed: version.properties filtered by Maven next to the class, constant loaded at class
    init (same-module Java Modules resource, no opens). No longer compile-time-inlineable, which
    also protects future consumers from the javac inlining trap.

## BUG-20260823-01 — main build-and-deploy fails: javadoc jar on knock-cdi-vauban has nothing to document

- **Date** : 2026-08-23
- **Statut** : FIXED (58e1f31)
- **Module touché** : knock-cdi-vauban (maven-javadoc-plugin attach-javadocs)
- **Symptôme** : `build-and-deploy` on main is red since 2026-07-26 (first failure right
  after PR #17 merged, well before the Codefloe migration — reproduced identically on the
  Codefloe runners). `maven-javadoc-plugin:3.11.2:jar` fails with
  "error: No public or protected classes found to document."
- **Reproduction minimale** :
  ```
  ./mvnw -pl knock-cdi-vauban javadoc:jar
  ```
- **Hypothèse de cause** : the BCE static-metadata wave made every class in
  knock-cdi-vauban package-private (the module only ships a ServiceLoader provider and
  generated wiring); javadoc then has zero public API to document and fails the build.
- **Investigations** :
  - 2026-08-23 : surfaced by the post-migration CI sweep on codefloe.com; Codeberg run
    history shows the same failure on 2026-07-26. Candidate fix: set
    `<maven.javadoc.failOnError>false</maven.javadoc.failOnError>` in the module POM so an
    (empty) javadoc jar is still produced for the release train, rather than skipping the
    javadoc jar entirely (Central requires the artifact's presence).
