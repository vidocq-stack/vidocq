# Ravel Integration in Cassini (Jakarta REST)

> Integration guide for injecting MicroProfile Config 3.1 configuration properties
> via Ravel into Cassini REST resources.

## Overview

Cassini is the Vidocq REST server (Jakarta RESTful Web Services). With `ravel-cdi-vauban`,
JAX-RS resources can inject configuration properties via `@ConfigProperty`
without depending on Smallrye Config.

## Maven Dependencies

In your `cassini-*` module or application using Cassini:

```xml
<dependencies>
    <!-- Ravel CDI (transitively includes ravel-core) -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-cdi-vauban</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <!-- MicroProfile Config API (provided by ravel-api) -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

## JPMS Configuration

In your `module-info.java`:

```java
module com.example.myapp {
    requires io.vidocq.ravel.api;         // MicroProfile Config API
    requires io.vidocq.ravel.cdi.vauban;  // CDI integration
    requires jakarta.inject;
    // ...
}
```

## Usage in a REST Resource

```java
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api")
public class MyResource {

    @Inject
    @ConfigProperty(name = "app.greeting", defaultValue = "Hello")
    private String greeting;

    @Inject
    @ConfigProperty(name = "app.port")
    private int port;

    @Inject
    @ConfigProperty(name = "app.debug")
    private java.util.Optional<Boolean> debug;

    @GET
    @Path("/info")
    public String getInfo() {
        return greeting + " on port " + port
               + (debug.orElse(false) ? " [DEBUG]" : "");
    }
}
```

## Configuration File

Create `META-INF/microprofile-config.properties` in your resources:

```properties
# application.properties (ordinal 100)
app.greeting=Hello
app.port=8080
# app.debug is optional — will be Optional.empty() if absent
```

Configuration sources (in decreasing priority order):

| Source | Ordinal | Example |
|---|---|---|
| System properties | 400 | `-Dapp.port=9090` |
| Environment variables | 300 | `APP_PORT=9090` |
| `microprofile-config.properties` | 100 | see above |

## Profile Support

Activate a profile by adding `-Dmp.config.profile=prod` at startup:

```properties
# microprofile-config.properties
app.port=8080
%prod.app.port=443
%dev.app.port=8081
```

## `@ConfigProperties` Support (property groups)

```java
import org.eclipse.microprofile.config.inject.ConfigProperties;

@ConfigProperties(prefix = "db")
@jakarta.enterprise.context.ApplicationScoped
public class DatabaseConfig {
    public String host;        // db.host
    public int port;           // db.port
    public String name;        // db.name
    @ConfigProperty(name = "db.password", defaultValue = "")
    private String password;
    
    public String getPassword() { return password; }
}
```

Injection:

```java
@Inject
@ConfigProperties
private DatabaseConfig db;
```

Configuration:

```properties
db.host=localhost
db.port=5432
db.name=myapp
```

## Integration with `cassini-cdi-vauban`

No additional configuration is required. `ravel-cdi-vauban` automatically registers
its Build Compatible Extension via `META-INF/services`.

The Vauban container will discover the BCE and synthesise the necessary beans to
satisfy `@ConfigProperty` injection points.

## Custom Configuration Sources

Implement `org.eclipse.microprofile.config.spi.ConfigSource`:

```java
public class DatabaseConfigSource implements ConfigSource {
    @Override
    public Map<String, String> getProperties() {
        // Read from a database, a service, etc.
        return Map.of("app.feature.x", "true");
    }

    @Override
    public String getValue(String propertyName) {
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() { return "database-config"; }

    @Override
    public int getOrdinal() { return 200; } // Between env vars and properties file
}
```

Register via `META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource`:

```
com.example.DatabaseConfigSource
```

## Ravel vs Smallrye Config Comparison

Ravel is a drop-in replacement for Smallrye Config:

| Feature | Ravel | Smallrye |
|---|---|---|
| `@ConfigProperty` CDI | ✅ | ✅ |
| `@ConfigProperties` | ✅ | ✅ |
| Property expressions `${key}` | ✅ | ✅ |
| Profiles `%dev.` | ✅ | ✅ |
| Arrays / Collections | ✅ | ✅ |
| Implicit converters | ✅ | ✅ |
| **Zero dependencies** | ✅ | ❌ (Smallrye Common, etc.) |
| **TCK 100% PASS** | ✅ | ✅ |

To migrate from Smallrye, remove `io.smallrye.config:smallrye-config` and add
`io.vidocq.ravel:ravel-cdi-vauban`. No application code changes are required.
