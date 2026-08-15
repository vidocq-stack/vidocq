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

The descriptor also carries `uses` directives, scanned off the bytecode of the whole dependency
closure with the JDK Class-File API. They are not optional: an automatic module may consume any
service, an explicit one may only consume what it declares, so a jar promoted without them fails
its own `ServiceLoader.load` with *"module … does not declare `uses`"*. Lookups written through a
helper that takes the service type as a `Class` parameter are followed across jars, since it is the
module reaching `ServiceLoader` — not the one naming the service — that must declare the directive.
A service type passed as a variable rather than a class literal cannot be seen by any bytecode
scan; declare that one by hand.

Only the directives the module system will accept are emitted. A `uses` is not a hint: the JVM
rejects the whole graph at resolution time with *"Module M uses S but does not read a module that
exports P to M"*, so a directive that cannot be satisfied trades one broken lookup for a runtime
that does not start at all. A scanned service is kept only when its type resolves to a package of
the closure or of the JDK, **and** the declaring module can read the module exporting it (following
`requires` and the transitive closure of `requires transitive`). Everything else is dropped, listed
in `report.txt` and logged as a `WARN` naming the jar, the service and the reason — `not in
closure`, `caller <m> cannot read <exporter>`, or `not a class literal`.

### Why some jars stay automatic

A jar whose own code reaches `ServiceLoader` for a type defined in a jar that depends on it has
**no legal explicit form at all**. LangChain4j is the textbook case: `langchain4j-core` ships a
generic `ServiceHelper.loadFactories(Class)`, and `langchain4j` calls it with types from its own
packages. `ServiceLoader` checks the *calling* class's module, so the JVM demands
`uses dev.langchain4j.spi.services.AiServiceContextFactory` on `langchain4j.core` — but that package
belongs to `langchain4j`, which already `requires langchain4j.core`. Declaring it would need a
`requires` back, and JPMS has no cycles. No descriptor satisfies both constraints.

Such a jar is therefore **left automatic** rather than patched into a graph that cannot resolve, and
the build warns:

```
kept automatic: langchain4j-core-1.17.1.jar — ServiceLoader of dev.langchain4j.http.client.HttpClientBuilderFactory
  from langchain4j.core cannot be declared (module cycle); jlink will reject it, dev mode works
```

Automatic modules work on the module path, so `vidocq:dev` and a plain `--module-path` run are
unaffected; only `jlink` refuses them, and an application depending on such a library cannot be
linked into an image until the situation changes. The ways out are upstream (each caller doing its
own `ServiceLoader.load`, or the library shipping a hand-written `module-info`) — or, in a future
version of this goal, merging the mutually-dependent jars into a single module, which is the only
shape the cycle admits. Set `vidocq.modularize.forceExplicit` to patch the jar anyway (the illegal
directives stay dropped) when you know the failing lookup is one your application never reaches.

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
| `vidocq.modularize.forceExplicit` (`<forceExplicit>`) | `false` | Patch a jar even when one of its own `ServiceLoader` lookups cannot be declared legally — see *Why some jars stay automatic*. The illegal directives are dropped either way. |

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
