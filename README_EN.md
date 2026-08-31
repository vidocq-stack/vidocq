# Vidocq

Modular Java SE application runtime built on [Vauban](https://github.com/VidocqMP/vauban) (CDI 4.1).

## Architecture

Vidocq is a lightweight runtime with a Quarkus-inspired extension mechanism, but simpler:

```
vidocq-runtime-spi          Extension interfaces (VidocqExtension, VidocqConfiguration)
vidocq-runtime-core         Engine: bootstrap, extension discovery, lifecycle
vidocq-runtime-maven-plugin Maven plugin: bean indexing + distribution ZIP packaging
```

### Extension mechanism

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
        // CDI container is ready
        var beanManager = context.beanManager();
    }
}
```

Registration via `META-INF/services/io.vidocq.runtime.spi.VidocqExtension` or `module-info.java`:

```java
provides VidocqExtension with MyExtension;
```

### Configuration

Properties are resolved in order:
1. System properties (`-Dkey=value`)
2. Environment variables (`KEY_NAME`)
3. `vidocq.properties` classpath resource

## Prerequisites

- Java 25 (`sdk use java 25-tem`)
- Maven 3.9.16 (`sdk use maven 3.9.16`)

## Build

```bash
mvn clean install
```

## Packaging

With the Maven plugin:

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

Produces a distribution ZIP:

```
myapp-1.0/
  bin/myapp.sh    Unix launcher (module-path)
  bin/myapp.cmd   Windows launcher
  lib/*.jar       Application + dependencies
```

## Available extensions

| Extension | Description |
|-----------|-------------|
| [vidocq-rest-extension](https://github.com/VidocqMP/vidocq-rest-extension) | JAX-RS via Jersey + Grizzly |

## License

EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
