# mansart-data-maven-plugin

Build-time, ahead-of-time generation of repository implementations for `@Repository` interfaces that
live in **pre-compiled dependency jars** — i.e. libraries that did not run the `mansart-data-processor`
annotation processor and therefore ship no `*RepositoryImpl`.

## Why

`mansart-data-processor` (the APT) only sees the **sources** being compiled, so it materializes the
repositories declared in the application itself. A `@Repository` provided by a dependency jar has no
generated implementation; at runtime the `mansart-data-cdi` `BuildCompatibleExtension` falls back to a
**reflective** path (a proxy generated at startup via the Class-File API, requiring `opens … to
io.vidocq.vauban.core`).

This plugin closes that gap for application builds: it generates the missing implementations at
compile time, so library-provided repositories are wired as **static** CDI beans with no runtime
reflection — keeping the AOT (GraalVM native-image, Leyden CDS) story intact.

The reflective runtime path is **not** removed — it remains the safety net for archives you do not
build (TCK suites, opaque jars).

## How it works

`generate-external-repositories` (bound to `generate-sources`):

1. Scans the compile classpath with the JDK **Class-File API** (JEP 484) for `@Repository`
   interfaces, skipping any already mapped in a `META-INF/mansart-repositories*.list`.
2. Runs a **source-less `javac` task** over the compile classpath and drives
   `io.vidocq.mansart.data.processor.ExternalRepositoryCodegen`, which reuses the *exact same*
   emitters as the APT (no duplicated codegen).
3. Emits each `*RepositoryImpl` (and any missing `_Entity` metamodel) into an
   **application-owned package**, referencing the external types by fully-qualified name so the
   application module never shares a package with the dependency jar (**no JPMS split package**).
4. Writes `META-INF/mansart-repositories-external.list`, which `mansart-data-cdi` reads alongside the
   APT index.

## Usage

```xml
<plugin>
  <groupId>io.vidocq.mansart</groupId>
  <artifactId>mansart-data-maven-plugin</artifactId>
  <version>${mansart.version}</version>
  <executions>
    <execution>
      <goals><goal>generate-external-repositories</goal></goals>
    </execution>
  </executions>
  <configuration>
    <!-- Optional. Default: derived from the project's groupId/artifactId. -->
    <targetPackage>com.acme.app.mansart.generated</targetPackage>
  </configuration>
</plugin>
```

### Parameters

| Parameter | Property | Default | Description |
| --- | --- | --- | --- |
| `targetPackage` | `mansart.targetPackage` | derived from project coordinates | Application-owned package the generated classes are emitted into. |
| `generatedSourcesDirectory` | `mansart.generatedSourcesDirectory` | `${project.build.directory}/generated-sources/mansart` | Added as a compile source root. |
| `outputDirectory` | — | `${project.build.outputDirectory}` | Where `META-INF/mansart-repositories-external.list` is written. |
| `skip` | `mansart.skip` | `false` | Skip external repository generation. |

## Constraints

- The generated metamodel reads the entity's private fields via `MethodHandles.privateLookupIn`, so an
  entity from an external jar must stay **reflectively accessible** (classpath, or an open module). A
  sealed module-path entity needs an `opens`.
- If the dependency was itself compiled with the APT (it already ships its `_Entity` metamodel), the
  plugin reuses it instead of regenerating one.
- Repositories declared in the application's own sources are handled by the APT — this plugin only
  generates what is **missing**.

## Implementation notes

The plugin module is packaged with a **hand-written** `META-INF/maven/plugin.xml` (the
`maven-plugin-plugin` descriptor generator does not support JDK 25 class files) and has no
`module-info.java` — Maven plugins run on the Plexus classpath.

See the Antora docs: `mansart-jakarta-data` → "Repositories from external jars (AOT generation)".
