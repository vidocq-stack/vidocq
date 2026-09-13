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

## `modularize` moved to the Vauban plugin

**The `vidocq:modularize` goal no longer exists.** It is now `vauban:modularize`, in
`io.vidocq.vauban:vauban-maven-plugin`. Vauban owns the class loader and the module machinery,
so the goal that writes module descriptors belongs there.

Nothing about what it does has changed — same derivation, same `uses` scanning, same split-package
guard, same report. Only the coordinates, the goal prefix, the property names and the output
directory move.

### Migrating a build

```diff
-<plugin>
-    <groupId>io.vidocq</groupId>
-    <artifactId>vidocq-runtime-maven-plugin</artifactId>
-    <executions>
-        <execution>
-            <id>modularize</id>
-            <goals><goal>modularize</goal></goals>
-        </execution>
-    </executions>
-</plugin>
+<plugin>
+    <groupId>io.vidocq.vauban</groupId>
+    <artifactId>vauban-maven-plugin</artifactId>
+    <executions>
+        <execution>
+            <id>modularize</id>
+            <goals><goal>modularize</goal></goals>
+        </execution>
+    </executions>
+</plugin>
```

| Before | After |
|---|---|
| `vidocq:modularize` | `vauban:modularize` |
| `vidocq.modularize.skip` | `vauban.modularize.skip` |
| `vidocq.modularize.mode` | `vauban.modularize.mode` |
| `vidocq.modularize.open` | `vauban.modularize.open` |
| `vidocq.modularize.forceExplicit` | `vauban.modularize.forceExplicit` |
| `target/vidocq-modularized/` | `target/vauban-modularized/` |
| `<release>` | **gone** — every multi-release variant of a jar is analysed, whatever its release, so there was nothing to select |

`vidocq:dev`, `vidocq:jlink` and `vidocq:package` still pick the patched copies up automatically;
they read the new directory. No other change is needed.

The goal's own reference lives with it now, in the Vauban documentation — [`vauban:modularize` in detail](https://codefloe.com/Vidocq/vauban/src/branch/main/docs/en/modules/ROOT/pages/reference.adoc).

## Roadmap

- A boot-time diagnostic that turns a runtime `InaccessibleObjectException` into a friendly
  "add `opens X`" message (a targeted net for the JSON-B reflection case above).
- `complete-module-info` scaffolding of a minimal `module-info.java` when none exists.
