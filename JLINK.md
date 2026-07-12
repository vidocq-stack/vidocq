# Packaging Vidocq — jlink, jpackage, Docker

Complete workflow for packaging a Vidocq application into a self-contained
deployable artefact via the `vidocq-runtime-maven-plugin`. Three packaging
targets are available, plus an external configuration override mechanism.

| Target | Goal | Output | Size | Start-up |
|-------|------|--------|--------|-----------|
| jlink runtime image | `vidocq:jlink` | `target/dist/` (binary + embedded Java runtime) | ~40 MB | ~4 s |
| Native bundle | `vidocq:jpackage` | `target/installer/<name>.app` (macOS), `.exe`/`.msi`/`.deb`/`.rpm` depending on OS, or cross-platform app-image | ~40 MB | ~1 s (CDS) |
| Docker image | `vidocq:docker` | `target/Dockerfile` to build via `docker build` | ~50 MB total | n/a |

All three rely on the **same** jlink runtime (see §Layout). `jpackage`
reuses the image produced by `jlink`; `docker` copies it into a minimal
distroless container.

---

## 1. Quick start on `vidocq-runtime-cassini-rest-example`

The `vidocq-runtime-cassini-rest-example` example is already wired to generate all three
artefacts in a single command.

```sh
cd vidocq-runtime-examples/vidocq-runtime-cassini-rest-example
mvn package -DskipTests
```

Produces:

```
target/
├── dist/                               # standalone jlink image
│   ├── bin/todo-app                    # binary launcher (no java required)
│   ├── conf/                           # overrideable config (see §5)
│   │   ├── vidocq.properties
│   │   └── logging.properties
│   ├── lib/                            # app JPMS modules + JDK
│   ├── legal/                          # licences (jlink)
│   └── release                         # build info
├── installer/
│   └── todo-app.app/                   # jpackage .app bundle
└── Dockerfile                          # pass to docker build
```

Immediate launch:

```sh
./target/dist/bin/todo-app
# → http://127.0.0.1:8080/        todo-list UI
# → http://127.0.0.1:8080/api/todos
```

---

## 2. Goal `vidocq:jlink`

### Role

Packages the application + all its modular dependencies + a minimal Java
runtime **into a self-contained directory** (`target/dist/`). The binary
`bin/<launcher>` requires **no pre-installed JVM** on the target machine.

### Internal mechanics

1. Stages all `compile`+`runtime` jars into `target/jlink-mods/`.
2. Verifies that none is an automatic module (otherwise a clear error is raised).
3. `jdeps --print-module-deps` (ToolProvider) resolves transitive JDK modules.
4. `jlink --module-path stage:$JAVA_HOME/jmods --add-modules <set>
   --launcher <name>=<module>/<mainClass> --strip-debug --compress=zip-6
   --no-header-files --no-man-pages --output target/dist`.
5. Copies `vidocq.properties` + `logging.properties` (and any configured
   resource) into `dist/conf/`.

### Configuration

```xml
<plugin>
    <groupId>io.vidocq.runtime</groupId>
    <artifactId>vidocq-runtime-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>jlink</id>
            <goals><goal>jlink</goal></goals>
            <configuration>
                <mainModule>com.example.myapp</mainModule>          <!-- required -->
                <mainClass>com.example.myapp.MainApp</mainClass>     <!-- required -->
                <launcher>my-app</launcher>                          <!-- default: artifactId -->
                <distDir>${project.build.directory}/dist</distDir>   <!-- default -->
                <stripDebug>true</stripDebug>                        <!-- default: true -->
                <compress>zip-6</compress>                           <!-- zip-0..zip-9, default: zip-6 -->
                <includeResources>                                   <!-- default: vidocq.properties + logging.properties -->
                    <param>vidocq.properties</param>
                    <param>logging.properties</param>
                    <param>my-custom-config.yaml</param>
                </includeResources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Launch

```sh
./target/dist/bin/my-app                                  # default
./target/dist/bin/my-app -Dfoo=bar                        # system properties
./target/dist/bin/my-app --module-path …                  # additional args (rare)
```

---

## 3. Goal `vidocq:jpackage`

### Role

Packages the jlink image into a **native bundle** for the host OS:
`.app`/`.dmg`/`.pkg` (macOS), `.deb`/`.rpm` (Linux), `.exe`/`.msi`
(Windows), or an app-image (cross-platform directory without installer).

### Internal mechanics

Reuses `target/dist/` (output of `vidocq:jlink`) as `--runtime-image`,
without re-resolving modules. Delegates to `java.util.spi.ToolProvider("jpackage")`.

### Configuration

```xml
<execution>
    <id>jpackage</id>
    <goals><goal>jpackage</goal></goals>
    <configuration>
        <mainModule>com.example.myapp</mainModule>          <!-- required -->
        <mainClass>com.example.myapp.MainApp</mainClass>     <!-- required -->
        <appName>my-app</appName>                            <!-- default: artifactId -->
        <appVersion>1.0.0</appVersion>                       <!-- macOS requires 1.x.y, not 0.x.y -->
        <type>app-image</type>                               <!-- app-image | dmg | pkg | deb | rpm | msi | exe -->
        <runtimeImage>${project.build.directory}/dist</runtimeImage>  <!-- default: jlink output -->
        <installerDir>${project.build.directory}/installer</installerDir>
        <icon>src/main/resources/app.icns</icon>             <!-- optional; .icns/.ico/.png -->
        <vendor>${project.groupId}</vendor>                  <!-- default: groupId -->
        <description>${project.description}</description>    <!-- default: POM description -->
    </configuration>
</execution>
```

### Default type: `app-image`

- **macOS**: produces a runnable `<name>.app` directory (Mach-O) — no signed
  installer. Ideal for distributing a portable binary.
- **Linux**: directory with a shell launcher.
- **Windows**: directory with a `<name>.exe` Win32 executable.

For a **distributable signed installer**, set `<type>` to the appropriate value
(`dmg`, `deb`, etc.) and provide the required native tooling
(`pkgbuild`/`dpkg`/Wix). Check prerequisites in the Oracle/OpenJDK `jpackage`
documentation.

### Versions

`jpackage` requires a **strictly numeric** version (`1.0.0`, `2.3.4`).
The plugin automatically strips `-SNAPSHOT` and any alphanumeric suffix from
`${project.version}`. **macOS app-image rejects versions starting with `0`** —
set an explicit `<appVersion>1.x.y</appVersion>` if the project is on `0.x.x`.

---

## 4. Goal `vidocq:docker`

### Role

Generates a `Dockerfile` that packages the jlink runtime image into a minimal
container. No additional JRE required — jlink includes its own runtime.

### Configuration

```xml
<execution>
    <id>docker</id>
    <goals><goal>docker</goal></goals>
    <configuration>
        <launcher>my-app</launcher>                          <!-- default: artifactId -->
        <imageTag>example/my-app:1.0.0</imageTag>            <!-- default: artifactId:version -->
        <baseImage>gcr.io/distroless/base-debian12:nonroot</baseImage>  <!-- default -->
        <exposedPort>8080</exposedPort>                      <!-- default: 8080 -->
        <runtimeImage>${project.build.directory}/dist</runtimeImage>
        <build>false</build>                                 <!-- default: false (generate only) -->
    </configuration>
</execution>
```

### Why `distroless/base-debian12:nonroot` by default?

- ~20 MB, no shell, no package manager: minimal attack surface.
- No JRE — jlink provides its own runtime, including one would be redundant.
- Non-root user by default — security-by-default aligned with k8s best practices.

For interactive debugging (shell, busybox), temporarily use
`gcr.io/distroless/base-debian12:debug`.

### Build and run

```sh
# Build (the plugin does not do it automatically by default)
docker build -t example/my-app:1.0.0 -f target/Dockerfile target/

# Run
docker run --rm -p 8080:8080 example/my-app:1.0.0

# With overridden config (see §5)
docker run --rm -p 8080:8080 \
    -e VIDOCQ_CONFIG_DIR=/etc/myapp \
    -v $(pwd)/conf:/etc/myapp:ro \
    example/my-app:1.0.0
```

To invoke `docker build` directly from Maven:
`<configuration><build>true</build></configuration>` or
`-Dvidocq.docker.build=true`. The plugin fails cleanly if the `docker`
binary is not available.

---

## 5. External configuration override (`ExternalFileConfigSource`)

Three mechanisms in decreasing priority order:

```sh
# A. System property (ordinal 400)
./bin/my-app -Dvidocq.chappe.listener.default.port=9090

# B. Environment variable (ordinal 300)
VIDOCQ_CONFIG_DIR=/etc/my-app ./bin/my-app
# → reads /etc/my-app/vidocq.properties

# C. External file (ordinal 250 — new)
# Looks in this order:
#   1. ${vidocq.config.dir}/vidocq.properties        (system prop)
#   2. ${VIDOCQ_CONFIG_DIR}/vidocq.properties        (env var)
#   3. ${java.home}/conf/vidocq.properties           (jlink convention)
#   4. ./conf/vidocq.properties                      (working dir)
```

### `${java.home}/conf/vidocq.properties` convention

In a jlink image, `java.home` points to the runtime image (= `dist/`). The
`vidocq:jlink` goal copies the `vidocq.properties` from the classpath into
`dist/conf/`. Therefore:

```sh
# On-the-fly edit without recompilation:
echo "vidocq.chappe.listener.default.port=9090" >> target/dist/conf/vidocq.properties
./target/dist/bin/my-app   # now runs on 9090
```

This is exactly the "ops modify the config of a release without rebuilding" use
case.

### Diagnostics

At boot, the log includes a line:

```
INFO ExternalFileConfigSource: Loaded external config from /path/to/vidocq.properties (3 entries)
```

If no file is found, the source is silently absent and only the classpath config
(`PropertiesFileConfigSource`, ordinal 100) applies.

### Full `ConfigSource` hierarchy

| Ordinal | Source | Description |
|---------|--------|-------------|
| 400 | `SystemPropertiesConfigSource` | `-Dkey=value` |
| 300 | `EnvConfigSource` | Environment variables |
| **250** | **`ExternalFileConfigSource`** | **Overrideable external file** |
| 100 | `PropertiesFileConfigSource` | `vidocq.properties` from the classpath |

---

## 6. Prerequisites and best practices

### 6.1 All jars must be named JPMS modules

`jlink` rejects automatic modules. The goal detects this and fails cleanly
with:

```
jlink does not support automatic modules: my.legacy.lib (file:///…/legacy.jar).
Convert these jars to proper JPMS modules (add a module-info.java).
```

For your own project:

- Add a `module-info.java` to the application module.
- For non-modular third-party libs: use `jdeps --generate-module-info`,
  request a modular upstream release, or re-package.

### 6.2 Records serialised via REST

Yasson 3.0.4 + records + strict module-path does not deserialise the canonical
constructor: `String` fields come back `null` after a POST → GET round-trip.
Workaround: factory annotated with `@JsonbCreator`:

```java
public record Todo(long id, String title, boolean done) {
    @JsonbCreator
    public static Todo create(@JsonbProperty("id") long id,
                              @JsonbProperty("title") String title,
                              @JsonbProperty("done") boolean done) {
        return new Todo(id, title, done);
    }
}
```

To be removed once the in-house `cassini-jsonb` is delivered
(see [cassini/JSON-ROADMAP.md](../cassini/JSON-ROADMAP.md)).

### 6.3 JAX-RS / JSON-B reflection

Resources, providers, and data models must be in an **open** package:

```java
module com.example.myapp {
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;

    opens com.example.myapp;       // JAX-RS + JSON-B reflection
}
```

### 6.3.1 Custom `java.util.logging` Handler

If your app declares a custom logging handler (`StdoutHandler`,
`CompactFormatter`, etc.) referenced in `logging.properties` via
`handlers = com.example.myapp.logging.StdoutHandler`, you must **export**
the package to `java.logging` — otherwise `LogManager.createLoggerHandlers`
fails with `IllegalAccessException` at boot:

```java
module com.example.myapp {
    requires java.logging;
    // …
    exports com.example.myapp.logging to java.logging;
}
```

`exports … to java.logging` (qualified) is sufficient — no need for `opens`,
since `Class.newInstance()` only uses the public no-arg constructor.

### 6.4 macOS app-image and version

`jpackage --type app-image` on macOS requires `appVersion` starting with `1`
or higher. A `-SNAPSHOT` project must set an explicit
`<appVersion>1.0.0</appVersion>`.

### 6.5 Ports in Docker

The `EXPOSE` in the generated Dockerfile points to the configured port
(`<exposedPort>`, default 8080). Make sure `vidocq.chappe.listener.default.port`
matches, otherwise the `-p` mapping is ineffective.

---

## 7. Layout of produced artefacts

### jlink image (`target/dist/`)

```
dist/
├── bin/
│   ├── java                        # reduced JVM binary
│   ├── keytool                     # JDK utility
│   └── <launcher>                  # ← app launcher (entrypoint)
├── conf/
│   ├── jaxp.properties             # JDK config
│   ├── logging.properties          # ← copied by vidocq:jlink
│   ├── net.properties
│   ├── security/                   # truststore, policies
│   └── vidocq.properties           # ← copied by vidocq:jlink, overrideable
├── lib/                            # JPMS modules (app + JDK + libs)
│   ├── modules                     # jimage archive of modules
│   ├── jrt-fs.jar
│   └── …
├── legal/                          # JDK module licences
└── release                         # JDK version + embedded modules
```

### macOS bundle (`target/installer/<name>.app/`)

```
<name>.app/
└── Contents/
    ├── Info.plist                  # Bundle metadata
    ├── PkgInfo
    ├── MacOS/
    │   └── <name>                  # Mach-O ARM64/x86_64 launcher
    ├── app/                        # additional config (empty here)
    ├── Resources/                  # icons, assets
    ├── runtime/                    # ← embedded jlink image
    │   └── Contents/Home/          # = target/dist/ structure
    │       ├── bin/, conf/, lib/, legal/, release
    └── _CodeSignature/             # ad-hoc signature (re-sign for distribution)
```

### Docker image

```
gcr.io/distroless/base-debian12:nonroot
└── /opt/app/                       # = COPY target/dist/
    ├── bin/<launcher>              # entrypoint
    ├── conf/, lib/, legal/, release
WORKDIR /opt/app
EXPOSE 8080
ENTRYPOINT ["/opt/app/bin/<launcher>"]
```

---

## 8. Known limitations

| # | Limitation | Workaround |
|---|------------|------------|
| 1 | Any non-modular dependency blocks `jlink` | Add `module-info.java` or use `jdeps --generate-module-info`. **Note**: the MicroProfile Config API is delivered as a named module `org.eclipse.microprofile.config` via `io.vidocq.ravel:ravel-mp-config-api` (JPMS substitute for `org.eclipse.microprofile.config:microprofile-config-api`, which remains an automatic module upstream). |
| 2 | Yasson + records → `title=null` on POST/GET round-trip in module-path | `@JsonbCreator` factory (see §6.2). Will be fixed by in-house `cassini-jsonb`. |
| 3 | macOS `app-image` rejects `appVersion=0.x.y` | Set `<appVersion>1.0.0</appVersion>` |
| 4 | `--strip-debug` removes `LineNumberTable` (less readable stack-traces) | `<stripDebug>false</stripDebug>` in dev, `true` in prod |
| 5 | No fat-jar fallback (uber-jar via shade-plugin) | Backlog `package-fatjar`; jlink + docker cover ~95 % of cases |
| 6 | `jpackage` `--type=dmg`/`deb`/`msi` requires native tooling (`pkgbuild`, `dpkg`, Wix) | Use `app-image` which has no native prerequisites, or install the tooling in CI |

---

## 9. Roadmap

- **`package-fatjar`** (backlog) — shade-plugin fallback for legacy deployments
  that do not support module-path.
- **In-house `cassini-jsonb` / `cassini-jsonp`**
  ([roadmap](../cassini/JSON-ROADMAP.md)) — will remove the records + Yasson
  friction.
- **Pre-generated CDS** in `vidocq:jlink` (`--generate-cds-archive`) — additional
  100–300 ms gain at start-up.
- **Multi-arch Docker image** (buildx, `linux/amd64`+`linux/arm64`) — useful for
  multi-architecture production deployments.
- **Sign macOS / Windows installers** — `codesign` + Apple notarization workflow,
  Win32 signtool; not included in the plugin for now.

---

## 10. Quick reference

```sh
# Full build (generates dist + .app + Dockerfile)
mvn package -DskipTests

# Run jlink image directly
./target/dist/bin/<launcher>

# Run macOS bundle
open target/installer/<name>.app
# or in CLI mode to see logs:
./target/installer/<name>.app/Contents/MacOS/<launcher>

# Run Docker
docker build -t <tag> -f target/Dockerfile target/
docker run --rm -p 8080:8080 <tag>

# Override config without recompiling
echo "vidocq.foo=bar" >> target/dist/conf/vidocq.properties
./target/dist/bin/<launcher>

# Override via env (Docker/k8s friendly)
VIDOCQ_CONFIG_DIR=/etc/myapp ./target/dist/bin/<launcher>

# Override via system property
./target/dist/bin/<launcher> -Dvidocq.foo=bar
```
