# Ravel Integration in Vauban (CDI Container)

> Integration guide for registering `Config` as a CDI bean in the Vauban container
> and enabling configuration property injection.

## Overview

Vauban is the Vidocq CDI container. With `ravel-cdi-vauban`, Vauban gains full
MicroProfile Config 3.1 support: `@Inject Config`, `@ConfigProperty`, `@ConfigProperties`.

## Integration Architecture

```
Application
    │
    ▼
Vauban (CDI container)
    │  ├── ravel-cdi-vauban (BCE + producers)
    │  │       └── ConfigCdiExtension (synthesises @ConfigProperty beans)
    │  │       └── ConfigSyntheticCreator (injects the full Config)
    │  │       └── RavelConfigPropertyResolver (resolves at runtime)
    │  └── ravel-core (Config implementation)
    │           └── RavelConfigProviderResolver
    │           └── SystemPropertiesConfigSource (400)
    │           └── EnvironmentVariablesConfigSource (300)
    │           └── MicroprofilePropertiesConfigSource (100)
```

## Maven Dependencies

In the Vauban parent `pom.xml` or in application modules:

```xml
<dependencyManagement>
    <dependencies>
        <!-- Ravel BOM -->
        <dependency>
            <groupId>io.vidocq.ravel</groupId>
            <artifactId>ravel-cdi-vauban</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

In modules that need MP Config:

```xml
<dependencies>
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-cdi-vauban</artifactId>
    </dependency>
</dependencies>
```

## JPMS Configuration

```java
module io.vidocq.vauban.mymodule {
    requires io.vidocq.ravel.api;
    requires io.vidocq.ravel.cdi.vauban;
    requires jakarta.inject;
    // ...
}
```

## Usage in Vauban Beans

### Injecting the full Config

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

@ApplicationScoped
public class MyService {

    @Inject
    private Config config;

    public String getDatabaseUrl() {
        return config.getValue("db.url", String.class);
    }
}
```

### Injecting individual properties

```java
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Dependent
public class ServerConfig {

    @Inject
    @ConfigProperty(name = "server.port", defaultValue = "8080")
    private int port;

    @Inject
    @ConfigProperty(name = "server.host", defaultValue = "localhost")
    private String host;

    @Inject
    @ConfigProperty(name = "server.debug")
    private java.util.Optional<Boolean> debug;

    // getters...
}
```

### Property groups with `@ConfigProperties`

```java
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ConfigProperties(prefix = "db")
@ApplicationScoped
public class DatabaseConfig {

    public String host;          // db.host
    public int port;             // db.port
    public String name;          // db.name

    @ConfigProperty(defaultValue = "5432")
    public int poolSize;         // db.poolSize

    public java.util.Optional<String> schema;  // db.schema (optional)
}
```

Injection:

```java
@Inject
@ConfigProperties
private DatabaseConfig dbConfig;

// With a different prefix (e.g., secondary database)
@Inject
@ConfigProperties(prefix = "db.secondary")
private DatabaseConfig secondaryDbConfig;
```

## Registering the BCE in Vauban

The `ConfigCdiExtension` BCE is automatically registered via ServiceLoader:

```
META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
    → io.vidocq.ravel.cdi.ConfigCdiExtension
```

If you use Vauban with classpath scanning, no additional configuration is required.
If you use `SeContainerInitializer.disableDiscovery()`, add the BCE explicitly:

```java
SeContainerInitializer initializer = SeContainerInitializer.newInstance()
        .disableDiscovery()
        .addBeanClasses(MyService.class, ServerConfig.class)
        // ravel-cdi-vauban automatically adds its BCE if the JAR is on the classpath
        ;
```

## Custom Configuration Sources for Vauban

```java
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

public class VaubanInternalConfigSource implements ConfigSource {

    private final Map<String, String> properties;

    public VaubanInternalConfigSource() {
        this.properties = readVaubanConfiguration();
    }

    @Override
    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() { return "vauban-internal"; }

    @Override
    public int getOrdinal() { return 250; } // Priority between env vars and properties file

    private Map<String, String> readVaubanConfiguration() {
        // Read Vauban's internal configuration
        return new HashMap<>();
    }
}
```

Registration via ServiceLoader:

```
META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource
    → io.vidocq.vauban.VaubanInternalConfigSource
```

## Programmatic Lookup

Outside CDI injection:

```java
import org.eclipse.microprofile.config.ConfigProvider;

// Programmatic lookup (without CDI)
Config config = ConfigProvider.getConfig();
String host = config.getValue("server.host", String.class);

// With Optional
Optional<Integer> port = config.getOptionalValue("server.port", Integer.class);
```

## Deployment Validation

`ravel-cdi-vauban` validates **required** properties (without `defaultValue`) at startup.
If a required property is absent, Vauban refuses to start with a `DeploymentException`:

```
DeploymentException: Missing required config property 'db.host' for type String
  at injection point @Inject @ConfigProperty private ServerConfig.dbHost
```

For optional properties, use `Optional<T>` or `defaultValue`:

```java
@Inject
@ConfigProperty(name = "feature.x.enabled", defaultValue = "false")
private boolean featureXEnabled;

@Inject
@ConfigProperty(name = "optional.feature")
private Optional<String> optionalFeature;
```

## Integration with `vidocq`

To replace Smallrye Config in `vidocq`:

1. Remove the `io.smallrye.config:smallrye-config` dependency
2. Add `io.vidocq.ravel:ravel-cdi-vauban`
3. Rebuild — no application code changes required

The `ServiceLoader` will automatically replace the `ConfigProviderResolver` implementation.
