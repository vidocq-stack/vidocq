<p align="center">
  <img src="vidocq-runtime-logo.png" alt="Vidocq" width="300">
</p>

<h1 align="center">Vidocq</h1>

<p align="center">
  <strong>Modular Java SE MicroProfile Runtime</strong><br>
  <a href="https://microprofile.io/">MicroProfile 7.1</a> | <a href="https://codefloe.com/Vidocq/vauban">Vauban CDI Lite</a> | JDK 25 | Java Modules
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-25-orange" alt="JDK">
  <img src="https://img.shields.io/badge/Maven-3.9.16-purple" alt="Maven">
  <img src="https://img.shields.io/badge/Jakarta_EE-Core_Profile_11_certified-brightgreen" alt="Jakarta EE Core Profile 11 certified">
  <img src="https://img.shields.io/badge/CDI-4.1_Lite-blue" alt="CDI">
  <img src="https://img.shields.io/badge/JAX--RS-4.0-green" alt="JAX-RS">
  <img src="https://img.shields.io/badge/license-EPL--2.0%20OR%20EUPL--1.2%20OR%20GPL--2.0--or--later-blue.svg" alt="License">
</p>

---

## What is Vidocq?

Vidocq is a modular Java SE application runtime built on [Vauban](https://codefloe.com/Vidocq/vauban) (CDI 4.1 Lite). It assembles the Vidocq bricks — Chappe (HTTP), Cassini (Jakarta REST), Champollion (JSON), Mansart (Data, Transactions) and the MicroProfile implementations — through a lightweight extension system inspired by Quarkus.

- **Jakarta EE Core Profile 11 certified** (Vidocq 0.3.0, approved 2026-09-09 — [`jakartaee/platform#1351`](https://github.com/jakartaee/platform/issues/1351), results at [vidocq.dev/certification](https://vidocq.dev/certification/)). Details: [`CERTIFICATION.md`](CERTIFICATION.md).
- **MicroProfile 7.1**: every MicroProfile spec of the platform (plus the standalone Metrics 5.1) is delivered as an extension, and the 8 official TCK runners pass on the assembled runtime (1868 tests, 2026-08-27 — see [`TCK.md`](vidocq-runtime-integration-tests/TCK.md)).

### Why Vidocq?

| | Quarkus | Helidon | **Vidocq** |
|---|---|---|---|
| CDI | ArC (partial) | Weld | **Vauban (native Java Modules CDI Lite)** |
| Java Modules | No | Partial | **Native (module-info.java)** |
| Approach | Build-time + extensions | Microframework | **MicroProfile extensions on CDI Lite** |
| Minimum JDK | 17 | 21 | **25** |

### Philosophy

- **CDI Lite first**: Vauban generates proxies and interceptors at compile time via the JDK 25 Class-File API
- **MicroProfile extensions**: each spec (REST, Config, Health, ...) is an independent extension
- **Native Java Modules**: each module declares a `module-info.java`
- **Virtual threads ready**: `ScopedValue` (JEP 487) for `@RequestScoped` context

## Quick Start

### Prerequisites

- JDK 25 (Temurin)
- Maven 3.9.16

```bash
# With SDKMAN!
sdk env install
```

### Create an application with the CLI

```bash
curl -fsSL https://vidocq.dev/install.sh | sh    # PowerShell: irm https://vidocq.dev/install.ps1 | iex
vidocq create --name todo
```

`vidocq create` scaffolds the `pom.xml` (parent `vidocq-runtime-parent` + `vidocq-runtime-maven-plugin`), the `module-info.java`, the entry point and a `vidocq.properties`. Full walkthrough: [getting started](https://doc.vidocq.dev) (`docs/en/modules/ROOT/pages/getting-started.adoc`).

### Hello World REST

```java
@Path("/hello")
@ApplicationScoped
public class HelloResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from Vidocq!";
    }
}
```

No further wiring: the Cassini annotation processor generates the dispatch adapter at compile time — no runtime reflection.

Entry point:

```java
public class App {
    public static void main(String[] args) {
        Vidocq.main(args);
    }
}
```

```bash
$ mvn package
$ sh target/todo-<version>/bin/todo.sh
$ curl http://localhost:8080/hello
Hello from Vidocq!
```

### Build Vidocq itself

```bash
./mvnw -ntp install -DskipTests
```

## Architecture

```mermaid
graph TB
    subgraph "vidocq-runtime-spi"
        SPI[VidocqExtension<br/>VidocqConfiguration<br/>ExtensionContext]
    end
    subgraph "vidocq-runtime-core"
        BOOT[VidocqBootstrap<br/><i>Lifecycle orchestrator</i>]
        LOADER[ExtensionLoader<br/><i>ServiceLoader</i>]
    end
    subgraph "vidocq-runtime-extensions"
        REST[vidocq-runtime-cassini-rest-extension<br/><i>Jakarta REST 4.0 (Cassini)</i>]
        HTTP[vidocq-runtime-chappe-webserver-extension<br/><i>Chappe HTTP/1.1 + HTTP/2</i>]
    end
    subgraph "Build tools"
        PLUGIN[vidocq-runtime-maven-plugin<br/><i>generate + package</i>]
    end

    SPI --> BOOT
    LOADER --> BOOT
    REST --> SPI
    REST --> HTTP
    HTTP --> SPI
    BOOT --> VAUBAN[Vauban CDI Lite<br/><i>4.1</i>]
    PLUGIN --> VAUBAN
    style BOOT fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    style REST fill:#fff3e0,stroke:#f57c00,stroke-width:2px
```

```
vidocq/
├── vidocq-runtime-spi                   Extension interfaces (VidocqExtension, VidocqConfiguration)
├── vidocq-runtime-core                  Bootstrap, extension discovery, lifecycle
├── vidocq-runtime-maven-plugin          Codegen, packaging (ZIP, jlink, jpackage, Docker), dev mode, continuous testing
├── vidocq-runtime-cli                   `vidocq` command line (create, dev, start, extension, build, doctor…)
├── vidocq-runtime-devservices           Dev Services (containers started for dev mode and tests)
├── vidocq-runtime-extensions/           Runtime extensions, grouped by domain
│   ├── vidocq-runtime-extensions-essentials/       Chappe HTTP transport, dev console, schema migration, langchain4j-cdi MCP
│   ├── vidocq-runtime-extensions-jakartaee-core/   Cassini JAX-RS 4.0
│   ├── vidocq-runtime-extensions-jakartaee-web/    Mansart Data / Persistence / Transactions / JDBC pool
│   ├── vidocq-runtime-extensions-microprofile/     Config, Fault Tolerance, Health, JWT, Metrics, OpenAPI (+ Swagger UI), Rest Client, Telemetry
│   └── vidocq-runtime-extensions-module-repackaged/  Third-party Java Modules repackages (H2)
├── vidocq-runtime-examples/             Examples (cassini-rest, cervantes-jwt, knock-health, mansart-h2, petstore, external-rest-lib)
└── vidocq-runtime-integration-tests/    Arquillian ITs + MicroProfile TCK runners on the assembled runtime
```

## Extension Mechanism

Extensions implement `VidocqExtension` and are discovered via `ServiceLoader`.

Lifecycle:

1. **configure** — configuration before CDI boot
2. **beforeStart** — enrich the `VaubanContainerBuilder`
3. **onStart** — CDI container is ready, start services
4. **onStop** — shutdown (reverse priority order)

```java
public class MyExtension implements VidocqExtension {

    @Override
    public String name() { return "my-extension"; }

    @Override
    public int priority() { return 1000; }

    @Override
    public void onStart(ExtensionContext context) {
        var beanManager = context.beanManager();
    }
}
```

Registration via `META-INF/services/io.vidocq.runtime.spi.VidocqExtension` or `module-info.java`:

```java
provides VidocqExtension with MyExtension;
```

## REST Extension (Jakarta REST 4.0)

`vidocq-runtime-cassini-rest-extension` plugs [Cassini](https://codefloe.com/Vidocq/cassini) (standalone Jakarta REST 4.0) into the [Chappe](https://codefloe.com/Vidocq/chappe) HTTP engine provided by `vidocq-runtime-chappe-webserver-extension`:

- `@Path` / `@Provider` classes are discovered as CDI beans in the Vauban container and exposed to Cassini through a `VaubanBeanProvider`.
- The dispatch adapters are generated at compile time by the Cassini annotation processor (companion `vidocq-runtime-cassini-rest-extension-codegen`, checked by `vidocq:checkpom`).
- The extension mounts a Cassini stack on a Chappe listener; declarative mounts (`vidocq.http.mount.<n>.type=restful`) take over when present.
- The startup report lists the mounted routes, and the dev console shows live request figures per mount.

### Configuration

| Property | Default | Description |
|-----------|--------|-------------|
| `vidocq.http.host` | `0.0.0.0` | Host of the `default` Chappe listener (alias of `vidocq.chappe.listener.default.host`) |
| `vidocq.http.port` | `8080` | Port of the `default` Chappe listener (alias of `vidocq.chappe.listener.default.port`) |
| `vidocq.rest.context-path` | `/` | Mount prefix of the REST application |
| `vidocq.rest.listener` | `default` | Chappe listener the REST application is mounted on |

## Configuration

Without a MicroProfile Config provider, the native sources are used, highest ordinal first:

1. System properties (`-Dkey=value`) — ordinal 400
2. Environment variables (`KEY_NAME`) — ordinal 300
3. External `vidocq.properties` (`-Dvidocq.config.dir`, `VIDOCQ_CONFIG_DIR`, `${java.home}/conf` for a jlink image, or `./conf`) — ordinal 250
4. `vidocq.properties` / `application.properties` on the classpath — ordinal 100

With `vidocq-runtime-ravel-config-extension` (MicroProfile Config 3.1), Ravel's sources replace the native ones.


## Packaging and tooling

```xml
<plugin>
    <groupId>io.vidocq.runtime</groupId>
    <artifactId>vidocq-runtime-maven-plugin</artifactId>
    <version>0.3.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
                <goal>package</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

`package` produces a ZIP distribution:

```
myapp-1.0/
  bin/myapp.sh    Unix launcher (module-path)
  bin/myapp.cmd   Windows launcher
  lib/*.jar       Application + dependencies
```

Other goals of `vidocq-runtime-maven-plugin`:

| Goal | Purpose |
|------|---------|
| `generate` | CDI bean index + proxies/interceptors for dependencies not processed by the Vauban APT |
| `jlink` / `jpackage` / `docker` | Standalone runtime image (see [`JLINK.md`](JLINK.md)), native installers, minimal Docker image |
| `run` | Runs the application once in a forked JVM, like the production launcher |
| `dev` | Dev mode: reload on change, dev console, Dev Services (see [`DEBUGMODE.md`](DEBUGMODE.md), [`DEV_SERVICES.md`](DEV_SERVICES.md)) |
| `test` | Continuous testing: re-runs the tests on every change |
| `checkpom` / `check-module-info` / `complete-module-info` | Check the codegen companions and the `module-info.java` directives the extensions need |
| `idea` | *(experimental)* IntelliJ IDEA run configurations |

## Extensions

| Spec / feature | Brick | Vidocq extension | Status |
|------|-------|-----------|--------|
| Jakarta REST 4.0 | [cassini](https://codefloe.com/Vidocq/cassini) | `vidocq-runtime-cassini-rest-extension` | ✅ Core Profile 11 certified |
| HTTP/1.1 + HTTP/2 server | [chappe](https://codefloe.com/Vidocq/chappe) | `vidocq-runtime-chappe-webserver-extension` | ✅ |
| Jakarta Data 1.0 | [mansart](https://codefloe.com/Vidocq/mansart) | `vidocq-runtime-mansart-data-extension` | ✅ |
| Jakarta Transactions 2.0 | mansart | `vidocq-runtime-mansart-transactions-extension` | ✅ |
| Virtual-thread-native JDBC pool | mansart | `vidocq-runtime-mansart-pool-extension` | ✅ |
| Jakarta Persistence 3.2 | [mansart](https://codefloe.com/Vidocq/mansart) | `vidocq-runtime-mansart-persistence-extension` | 🧪 P9 integration tests green; not TCK-certified |
| MicroProfile Config 3.1 | [ravel](https://codefloe.com/Vidocq/ravel) | `vidocq-runtime-ravel-config-extension` | ✅ TCK green on the runtime |
| MicroProfile Fault Tolerance 4.1 | [heisenberg](https://codefloe.com/Vidocq/heisenberg) | `vidocq-runtime-heisenberg-fault-tolerance-extension` | ✅ TCK green on the runtime |
| MicroProfile Health 4.0 | [knock](https://codefloe.com/Vidocq/knock) | `vidocq-runtime-knock-health-extension` | ✅ TCK green on the runtime |
| MicroProfile JWT Auth 2.1 | [cervantes](https://codefloe.com/Vidocq/cervantes) | `vidocq-runtime-cervantes-jwt-extension` | ✅ TCK green on the runtime |
| MicroProfile OpenAPI 4.1 | [grimm](https://codefloe.com/Vidocq/grimm) | `vidocq-runtime-grimm-openapi-extension` (+ `-openapi-ui-extension`, Swagger UI) | ✅ TCK green on the runtime |
| MicroProfile Rest Client 4.0 | [cyrano](https://codefloe.com/Vidocq/cyrano) | `vidocq-runtime-cyrano-rest-client-extension` | ✅ TCK green on the runtime |
| MicroProfile Telemetry 2.1 | [humboldt](https://codefloe.com/Vidocq/humboldt) | `vidocq-runtime-humboldt-telemetry-extension` | ✅ TCK green on the runtime |
| MicroProfile Metrics 5.1 (standalone spec) | [dirac](https://codefloe.com/Vidocq/dirac) | `vidocq-runtime-dirac-metrics-extension` | ✅ TCK green on the runtime |
| Schema migration | — | `vidocq-runtime-migration-extension` (+ Flyway / Liquibase) | ✅ |
| Dev console | — | `vidocq-runtime-devconsole-extension` | ✅ (includes a read-only Dev MCP for coding agents) |
| MCP server | langchain4j-cdi | `vidocq-runtime-langchain4j-cdi-mcp-extension` | ✅ |

## Jakarta Servlet 6.1

Servlet 6.1 is implemented by the standalone [Foy](https://codefloe.com/Vidocq/foy) project (transport via Chappe, CDI via Vauban), which carries its own official TCK runner (`run-official-tck-servlet6.1.sh`). It is not packaged as a Vidocq extension yet; it is part of the Web Profile 11 planning ([`WEB-PROFILE.md`](WEB-PROFILE.md)).


## License

[EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later](LICENSE)
