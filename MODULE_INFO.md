# Vidocq Module-Info Check

Vidocq is JPMS-strict: every application is a named module with a hand-curated `module-info.java`.
Some extensions need the **consumer** module to declare a JPMS directive that nothing forces at
compile time — it compiles fine on the classpath and in the TCK, and only breaks on the module path
(a real `docker compose up` / `jlink` boot). The canonical example is schema migration: Flyway and
Liquibase scan the `db.migration` package for SQL/XML scripts, so a named module must
`opens db.migration;` or the migration silently finds nothing at runtime.

The `vidocq-runtime-maven-plugin` catches these at build time.

## The two module-info goals

### `vidocq:check-module-info` — verify (default, fail-fast)

Bound at `process-classes` for every module that uses the Vidocq plugin (inherited from
`vidocq-runtime-parent`'s `pluginManagement`, alongside `checkpom`). It unions the
*module-info requirements* declared by every resolved dependency (see the descriptor format below)
and checks them against the compiled `module-info.class`. A missing directive fails the build with
the exact, copy-pasteable fix:

```
[ERROR] module-info of 'com.acme.app' is missing 1 directive(s) required by its Vidocq extensions
        — add to src/main/java/module-info.java:
[ERROR]     opens db.migration;   (the migration backend (Flyway/Liquibase) scans the db.migration package for classpath migrations)
```

Configuration:

| Property | Default | Effect |
|---|---|---|
| `vidocq.moduleinfo.skip` | `false` | Skip the check entirely. |
| `vidocq.moduleinfo.failOnMissing` | `true` | `false` downgrades failures to warnings. |

### `vidocq:complete-module-info` — fix (opt-in)

Has **no default phase** — it never runs during a normal build, so it never silently edits
hand-curated source. Invoke it explicitly to add the missing directives for you:

```
mvn vidocq:complete-module-info
```

It rewrites `src/main/java/module-info.java`, inserting the missing `opens` directives before the
module's closing brace (a marked, zero-dependency text insertion). It is **idempotent**: a directive
already present in source is never duplicated.

## Descriptor format (for extension authors)

An extension declares what its consumers must add by shipping
`src/main/resources/META-INF/vidocq/module-requirements.properties`:

```properties
# requires: comma-separated module names the consumer must read
requires        = some.module, another.module
# opens: comma-separated packages the consumer must open (unqualified)
opens           = db.migration
# opens.to.<module>: a qualified `opens <pkg> to <module>` (rare)
opens.to.io.vidocq.vauban.core = com.acme.app.beans
# reason.<name>: a short why, surfaced in the diagnostic (name = a module or a package)
reason.db.migration = the migration backend (Flyway/Liquibase) scans the db.migration package
```

The check unions the descriptors of all dependencies, so the requirement lives with the extension
that needs it. Today the schema-migration extension
(`io.vidocq.runtime.extensions.essentials.migration`) ships `opens db.migration`; it is the module a
migrating app always depends on, regardless of the Flyway/Liquibase backend.

## Design notes

**Only `opens` is enforced.** A `requires` is frequently satisfied *transitively* (e.g. the H2
jpms-repackaged module declares `requires transitive java.sql`), but `ModuleElement.getDirectives()`
and `ModuleDescriptor.requires()` expose only the *direct* requires — so checking a `requires` would
be a false positive that breaks correct apps. `opens` is never inherited, so it is the
false-positive-free thing to enforce. (The requirement model still carries `requires` for a future
readability-aware check.)

**An `opens <pkg>` applies only to a module that contains `<pkg>`.** You neither need, nor can
legally, open a package you do not have. The plugin checks the module's output directory for the
package before requiring it, so an extension that merely *depends on* another extension (e.g. the
Flyway backend depends on the migration core but ships no `db.migration` package) is a no-op rather
than wrongly told to open a package it lacks. This is also what lets the check run reactor-wide.

## What is and is not checked

**Checked:** dependency-driven `opens` — directives an app needs purely because it depends on an
extension, with no application-specific knowledge required. `opens db.migration` is the marquee case.

**Not checked — reflection on your own classes.** When the app's DTOs are serialized by JSON-B
(Champollion) *without* static binding, Champollion reflects on them via
`MethodHandles.privateLookupIn`, which requires the app to open the DTO package. This is **not**
caught here, because it depends on the app's own packages and on whether each DTO uses static
binding — knowledge the build-time check cannot derive without false positives. Two ways to avoid the
footgun:

- **Preferred:** annotate the DTO records with `@JsonbStatic` so Champollion generates static
  serializers (zero reflection, no `opens` needed); or
- `opens com.acme.app.dto;` the package(s) containing your JSON-B DTOs.

JAX-RS resource dispatch (Cassini) uses APT-generated adapters with direct typed dispatch, so it does
**not** reflect into your resource packages on the nominal codegen path — no `opens` is needed for it.

## `vidocq:modularize` — give a non-modular dependency a module descriptor

The goals above are about the directives **your** `module-info.java` is missing. `vidocq:modularize`
answers the opposite problem: a third-party jar that has **no** `module-info.class` at all. On the
module path such a jar is an *automatic module* — it works, but it reads every module, exports every
package, and `jlink` refuses to link it. This goal patches a copy of it with a generated descriptor
(ModiTect: `jdeps`-derived `requires`, every package exported, `META-INF/services` promoted to
`provides`, `open module` by default) into `target/vidocq-modularized/`, under the **original file
name**.

Nothing is installed, deployed or redistributed: the copies live in `target/` and only this build
sees them. `vidocq:dev`, `vidocq:jlink` and `vidocq:package` resolve every dependency through that
directory first, so a patched copy transparently replaces the original jar on the module path, in
the staged jlink image and in the distribution's `lib/`.

Minimal wiring (the goal has no default binding of its own — declare an execution; its default phase
is `prepare-package`):

```xml
<plugin>
    <groupId>io.vidocq.runtime</groupId>
    <artifactId>vidocq-runtime-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>modularize</id>
            <goals><goal>modularize</goal></goals>
            <configuration>
                <mode>all-automatic</mode>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### `derived` or `all-automatic`

| `<mode>` | Patches | Use it when |
|---|---|---|
| `derived` (default) | only jars whose automatic name is *derived from the file name* (no `Automatic-Module-Name` manifest entry) | you want to stabilise the modules nobody has named yet, and to leave every jar whose author already committed to a module name untouched |
| `all-automatic` | every automatic jar, including those declaring `Automatic-Module-Name` | you run `vidocq:jlink` or `vidocq:jpackage` — `jlink` rejects **any** automatic module, so all of them must become named modules |

### Module naming

By default a patched jar keeps **the very name it already had as an automatic module** (the
`Automatic-Module-Name` entry, or the JDK-derived name from the file name). That is deliberate: it is
the name `javac` saw when it compiled your `module-info.java` against the *original* jar, so the
`requires` you wrote keeps resolving after patching.

`<moduleNames>` overrides that name per `artifactId`:

```xml
<moduleNames>
    <langchain4j-open-ai>dev.langchain4j.openai</langchain4j-open-ai>
</moduleNames>
```

**Caveat — an override is a runtime-only rename.** Compilation still resolves against the original
jar, which announces its automatic name; an overridden module renamed out from under a
`requires <old.name>;` will compile and then fail module resolution at run time (or vice versa). Only
override the name of a jar your code does not `requires` by name — one reached transitively or purely
through services — or the derived name is not a legal Java module name at all (in which case javac
could not resolve it either, and the dependency must be reached through services).

### Split packages fail the build

Two automatic jars sharing a package cannot both become named modules — and the module system would
reject them side by side anyway. The goal checks the whole automatic closure up front and fails with
the offending package and the jars holding it:

```
vidocq:modularize — split package(s) between dependency jars, the module system cannot host them together:
  com.acme.util in acme-core-1.2.jar, acme-legacy-1.2.jar
Remove one side from the dependency graph (Maven <exclusions>) or wait for an upstream fix.
```

`<excludes>` does **not** silence this: leaving one side unpatched does not make the packages any
less split.

### `report.txt`

Every run writes `target/vidocq-modularized/report.txt` with, per patched jar, the module name, why
it was selected, and the **full generated `module-info` source** — plus one line per jar left as is.
It is the thing to read when a `requires` looks wrong or a service is not picked up; the output
directory is emptied at the start of each run, so the report always describes the jars sitting next
to it.

### Licence gate (opt-in)

Patching rewrites someone else's jar. When `<allowedLicenses>` is set, every artifact that ends up
patched must declare one of the listed licences, otherwise the build fails — an explicit,
licence-aware decision for teams that ship the patched copies inside an image:

```xml
<allowedLicenses>
    <license>Apache-2.0</license>
    <license>EPL-2.0</license>
    <license>MIT</license>
</allowedLicenses>
```

Names are normalised before comparison (`The Apache Software License, Version 2.0` matches
`Apache-2.0`). An artifact declaring no licence at all is a violation. Left empty (the default) the
gate is off — nothing is redistributed by the goal itself.

### Other parameters

| Parameter / property | Default | Effect |
|---|---|---|
| `vidocq.modularize.skip` | `false` | Skip the goal entirely. |
| `<includes>` / `<excludes>` | empty | Restrict patching to / away from these `artifactId`s. Both only ever *restrict*: an explicit module named in `<includes>` still stays untouched. |
| `vidocq.modularize.open` (`<openModules>`) | `true` | Generate `open module` descriptors (a reflective library keeps working). `false` generates a closed module that `exports` every package. |
| `<release>` | `${maven.compiler.release}` (else `25`) | JDK release `jdeps` analyses multi-release jars against. |

### Dev mode needs an earlier binding

`vidocq:dev` is a direct-invocation goal: it does not fork a lifecycle, and its rebuild loop runs
`mvn process-classes`. Bound at its default `prepare-package`, `modularize` therefore never runs in a
`mvn vidocq:dev` session and dev mode would see the original jars. Bind it to `process-classes` when
you want dev mode to run against the patched copies:

```xml
<execution>
    <id>modularize</id>
    <phase>process-classes</phase>
    <goals><goal>modularize</goal></goals>
</execution>
```

(The alternative is to run `mvn prepare-package` once before starting `vidocq:dev` — the copies
survive in `target/` until the next `clean`.)

## Roadmap

- A boot-time diagnostic that turns a runtime `InaccessibleObjectException` into a friendly
  "add `opens X`" message (a targeted net for the JSON-B reflection case above).
- `complete-module-info` scaffolding of a minimal `module-info.java` when none exists.
