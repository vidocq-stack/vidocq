# Generated code for third-party CDI jars

- **Date:** 2026-10-01
- **Status:** direction approved in conversation, spec under review
- **Issue:** #186 (revisits the "proxies generated at run time" choice of #94)
- **Repositories:** `vauban` (generator, descriptor tools), then `vidocq` (plugin, extension, docs)
- **Builds on:** Vidocq/vauban#110 and #185 (codegen coverage), whose console column measures the result

## Context

On the langchain4j-cdi MCP tasks server, the 28 `dev.langchain4j.cdi.mcp` beans run entirely by reflection. Three
causes (#186):

1. `vidocq:generate` processes only the jars listed in `<scanDependencies>`, and applications list none.
2. Even for a scanned jar, `VaubanGenerator` writes no `_VaubanComponents`: it generates per-package providers for
   the project's own classes only, and client proxies and `$$Intercepted` subclasses for scanned jars.
3. The container finds providers through `ServiceLoader`, so a named module must declare
   `provides io.vidocq.vauban.api.VaubanComponentProvider`; `--patch-module`, which `vidocq:dev` and `vidocq:run`
   use today for the classes generated for a scanned jar, cannot change a module descriptor.

What already exists and is reused:

- `JpmsPatches` parks the classes generated for a scanned jar in `target/vidocq-patches/<artifactId>/`, wires them
  with `--patch-module` in `dev`/`run`, and writes an enriched copy of the jar in `package`/`jlink`.
- `vauban:modularize` (`Modularizer`) synthesizes a descriptor for an automatic jar — `requires` from jdeps, every
  package exported, `META-INF/services` promoted to `provides`, scanned `uses`, `open module` by default, a licence
  gate and a `uses` legality check.
- `ApplicationLaunch.modulePath` already substitutes, in every launch shape, the copy `ModularizedJars.resolve`
  returns for a dependency jar.
- `ModuleInfoRewriter.addComponentProvider` (vauban `enhance`) adds `provides … VaubanComponentProvider with …` and
  `requires io.vidocq.vauban.api` to a `module-info.class`.

## Goals

1. A scanned third-party jar gets the same generated code as a project: per-package `_VaubanComponents`
   (components, fields, methods, client proxies), client proxies and `$$Intercepted` subclasses, all Class-File.
2. One enriched copy per scanned jar carries those classes and a descriptor that declares them; every launch shape
   (`dev`, `run`, `package`, `jlink`) uses it in place of the original, through the existing substitution.
3. An automatic jar is first given a descriptor (`open module`) by the existing modularizer, then enriched.
4. Three sources select the jars to scan: the extension that ships them, `<scanDependencies>`, and an automatic
   detection of bean archives. `vidocq:analyze-deps` reports what each source selects and why.
5. The documentation says all of it.

## Non-goals

- No change to langchain4j-cdi (#94: it targets Java 17 and never depends on Vidocq).
- No change to runtime dispatch: the container already prefers a provider over reflection.
- `vidocq:analyze-deps` does not write the `pom.xml`; it prints the block to paste.
- An IDE launch with no Maven step keeps using the original jars, hence reflection, as today.

## Design

### 1. vauban — dependency providers and descriptor tools (`vauban-maven-plugin`)

- **`VaubanGenerator.Config`** gains `dependencyProviders` (default `false`, so `vauban:generate` is unchanged). When
  `true`, `generateComponentProvider` also collects the managed beans whose class files come from a scanned **jar**
  (the generator records, while scanning, which jar each class comes from), with the same eligibility rules as for
  the project (non-nested, `ComponentCollector`), and writes one `_VaubanComponents` per package into `outputDir`.
- Those providers are **not** listed in the project's `META-INF/services` file; `GenerationResult` reports them in a
  new `generatedProviders` list, so the caller relocates and registers them.
- **`ModuleInfoRewriter`** gains an overload taking extra `requires`: a generated `$$Intercepted` subclass calls into
  `io.vidocq.vauban.core`, so an enriched module that holds one must read it. A helper returns the Vauban modules a
  set of class files references (constant-pool scan with the Class-File API).
- **`Modularizer`** gains a single-jar synthesis that returns a `module-info.class` without writing or clearing
  `target/vauban-modularized/` (the `modularize` goal keeps owning that directory).

### 2. vidocq — one enriched copy per scanned jar (`vidocq-runtime-maven-plugin`)

- `VidocqGenerateMojo` sets `dependencyProviders = true` and relocates the providers with the proxies (they live in
  the jar's packages).
- After relocation, for each scanned jar with parked classes, a new `EnrichedJars` writes
  `target/vidocq-enriched/<original file name>`:
  1. start from `ModularizedJars.resolve(build, jar)`; if that jar still has no descriptor, synthesize one
     (`open module`) through the single-jar modularizer, or, if synthesis is refused (a `ServiceLoader` lookup no
     explicit module may declare), leave the jar to `--patch-module` with a warning naming the reason. The
     modularizer's optional licence gate is not applied: it is off by default and `modularize` keeps it;
  2. add the parked classes (as `JpmsPatches.enrich` does: shipped entries win);
  3. rewrite the descriptor: `provides io.vidocq.vauban.api.VaubanComponentProvider with` the jar's providers,
     `requires io.vidocq.vauban.api`, and `requires io.vidocq.vauban.core` when a generated class references it;
  4. add `META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider` for class-path launches;
  5. drop the signature files and stamp the manifest with `Vidocq-Enriched-From` (coordinates) and
     `Vidocq-Enriched-Digest` (`sha256:` of the source jar), as `DependencyEnhancer` does.
- The directory is emptied at the start of each `generate`, so a stale copy is never substituted.
- **Substitution:** `EnrichedJars.resolve(build, jar)` returns the enriched copy, else
  `ModularizedJars.resolve(build, jar)`. `ApplicationLaunch.modulePath` and the `package`/`jlink` staging use it.
  `--patch-module` and `JpmsPatches.enrich` remain only for a jar that has parked classes but no enriched copy.
- The build log lists every enriched jar with its source, and the generated providers per jar.

### 3. vidocq — which jars are scanned

A dependency jar is scanned when one of these selects it, unless it is excluded:

| Source | How |
|---|---|
| Extension | A dependency jar's manifest declares `Vidocq-Scan-Dependencies: <groupId:artifactId patterns>`; the patterns apply to the project's dependencies. The langchain4j-cdi MCP extension declares `dev.langchain4j.cdi.mcp:*`. |
| Application | `<scanDependencies>`, as today. |
| Automatic | The jar holds `META-INF/beans.xml` whose `bean-discovery-mode` is not `none`. On by default; `-Dvidocq.generate.autoScan=false` or `<autoScan>false</autoScan>` turns it off. |

Excluded, with a warning naming the reason (except the first, which is silent as today):

- already processed: `META-INF/vauban-bce-processed`, or a `META-INF/vauban-beans.list`, or a
  `VaubanComponentProvider` service declared by the jar — every Vidocq brick compiled with the Vauban APT;
- signed (`META-INF/*.SF`), unless `<scanDependencies>` names it;
- listed in a new `<scanExcludes>` (same pattern syntax).

### 4. vidocq — `vidocq:analyze-deps`

A goal (no phase binding) that prints, per dependency jar: bean archive and discovery mode; module kind (explicit,
open, automatic); signed; already processed; selected by which source, or excluded and why; and what
`vidocq:generate` would do (enrich, modularize then enrich, patch only, nothing). It ends with the
`<scanDependencies>` block that makes the automatic selection explicit.

### 5. Documentation

- vidocq: the Maven plugin page (`generate`: the three sources, exclusions, enriched copies; the new
  `analyze-deps` goal), the langchain4j-cdi extension section (its jars are generated, not reflective), the
  dev-console `cdi-panel` section (a third-party bean archive now reads `Class-File`).
- vauban: `internals.adoc` (`_VaubanComponents` for scanned jars), `reference.adoc` (the `dependencyProviders`
  option and the single-jar modularizer, where the plugin goals are described).

## Testing

- **vauban:** generator unit tests over a fixture jar — providers per package with `dependencyProviders = true`,
  none with `false`, none listed in the project's service file; `ModuleInfoRewriter` with extra `requires`; the
  constant-pool helper; single-jar synthesis returns an `open module` without touching `target/vauban-modularized/`.
- **vidocq:** `EnrichedJars` — descriptor rewritten (`provides`, `requires`), classes added, services file, manifest
  stamp, signature dropped, automatic jar modularized first, refusal falls back to patching; resolution order;
  source selection (manifest, `scanDependencies`, `beans.xml`, each exclusion); `analyze-deps` report.
- **Integration:** the existing `vidocq-runtime-it-langchain4j-cdi-mcp` boots on the enriched jars; the dev console
  of the MCP tasks server shows the `dev.langchain4j.cdi.mcp` beans as `Class-File` or `partial`, no longer
  `reflection`.
- vauban `clean install` + both TCKs; vidocq affected modules + plugin tests.

## Risks

- **jdeps blind spots:** a dependency reached only by reflection is missing from a synthesized `requires`; the
  existing modularizer limits (vauban#108 for `uses`) now apply to more jars. `analyze-deps` makes the selection
  visible, and `<scanExcludes>` or `autoScan=false` opts out.
- **Modified redistribution:** a packaged application ships enriched third-party jars. The manifest stamp and the
  build log record it, signed jars are left alone unless named, and `autoScan=false` keeps the old behaviour.
- **Layering (#94):** an enriched copy keeps its module name, so the boot-layer placement is unchanged.
- **Stacked delivery:** these branches build on vauban#110 and #185; their pull requests merge after those.
