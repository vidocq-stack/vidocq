# Knock MP Health API

Re-package of `org.eclipse.microprofile.health:microprofile-health-api` with an explicit
`module-info.class` (module name: `microprofile.health.api`), so that the MicroProfile Health spec
can be used with strict Java Modules and included in a `jlink` runtime image. The official artifact
only ships an `Automatic-Module-Name`, which `jlink` rejects.

This artifact is the sole source of the MP Health spec consumed by the Knock modules. The official
TCK keeps resolving the upstream API.

## How it is built

Same pattern as `ravel-mp-config-api`, `heisenberg-mp-ft-api` and `cyrano-mp-rest-client-api`:

1. `maven-dependency-plugin:unpack` extracts the **upstream binary jar** into `target/classes` at
   `generate-resources` time (excluding only `META-INF/MANIFEST.MF` and `META-INF/maven/**`, which
   `maven-jar-plugin` rewrites for our artefact);
2. `maven-compiler-plugin` compiles the **only source file we own**,
   `src/main/java/module-info.java`, patched into the `microprofile.health.api` module via
   `--patch-module`.

**No upstream source file is vendored in this repository.** The upstream version is pinned by the
`microprofile.health.upstream.version` property in the parent POM, so bumping the spec is a
one-line change with no source to re-copy.

## Licensing

The unpacked classes remain the work of the Eclipse Foundation and the MicroProfile contributors,
under the **Apache License 2.0**. The upstream `META-INF/LICENSE` and `META-INF/NOTICE` are
propagated verbatim into the produced jar, as required by Apache-2.0 section 4(a) and 4(d).

Only `module-info.java` is Vidocq-authored and carries the project's
`EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later` header.

> **History.** Until 2026-07, this module vendored the nine upstream `.java` files, copied from the
> official *sources* jar, in order to strip the OSGi `@Version` annotations from the
> `package-info.java` files. Two problems followed: a workspace-wide licence-header normalisation
> pass overwrote the upstream Apache-2.0 headers with the Vidocq header (a copyright
> misattribution, since the files were verbatim copies), and the module had to be re-synchronised
> by hand on every spec bump. Unpacking the **binary** jar removes both issues at once: the OSGi
> annotations are `RuntimeInvisibleAnnotations` (`CLASS` retention), so they are never loaded at
> runtime and never reach `jlink` — which is why the sibling modules listed above never needed to
> strip them in the first place.
