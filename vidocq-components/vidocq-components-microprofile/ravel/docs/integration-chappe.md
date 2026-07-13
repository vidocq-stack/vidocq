# Ravel Integration in Chappe (HTTP Server)

> Integration guide for configuring the Chappe HTTP server via MicroProfile Config 3.1
> with Ravel as the implementation.

## Overview

Chappe is the Vidocq HTTP server. Ravel reads server configuration
(port, TLS, timeouts, etc.) from standard MicroProfile configuration sources.

## Maven Dependencies

```xml
<dependencies>
    <!-- Ravel core (no CDI, for standalone context) -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-core</artifactId>
        <version>0.2.0</version>
    </dependency>
    <!-- MicroProfile Config API -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-api</artifactId>
        <version>0.2.0</version>
    </dependency>
</dependencies>
```

With CDI (Vauban):

```xml
<dependencies>
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-cdi-vauban</artifactId>
        <version>0.2.0</version>
    </dependency>
</dependencies>
```

## Java Modules Configuration

```java
module io.vidocq.chappe.server {
    requires io.vidocq.ravel.api;   // or ravel.core if without CDI
    // ...
}
```

## Programmatic Usage (without CDI)

```java
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

public class ChappeServerConfig {

    private final Config config;

    public ChappeServerConfig() {
        this.config = ConfigProvider.getConfig();
    }

    public int getPort() {
        return config.getValue("chappe.http.port", int.class);
    }

    public String getHost() {
        return config.getOptionalValue("chappe.http.host", String.class)
                     .orElse("0.0.0.0");
    }

    public boolean isTlsEnabled() {
        return config.getOptionalValue("chappe.tls.enabled", boolean.class)
                     .orElse(false);
    }

    public String getTlsKeystore() {
        return config.getOptionalValue("chappe.tls.keystore", String.class)
                     .orElse(null);
    }
}
```

## Recommended Configuration File

```properties
# META-INF/microprofile-config.properties
chappe.http.port=8080
chappe.http.host=0.0.0.0
chappe.http.max-connections=1000
chappe.http.idle-timeout=PT30S

# TLS (disabled by default)
chappe.tls.enabled=false
chappe.tls.keystore=/etc/chappe/keystore.jks
chappe.tls.keystore-password=changeit

# Profiles
%prod.chappe.http.port=443
%prod.chappe.tls.enabled=true
%dev.chappe.http.port=8081
```

## Configuration via Environment Variables

MicroProfile Config (and Ravel) automatically maps environment variables:

| Property | Environment variable |
|---|---|
| `chappe.http.port` | `CHAPPE_HTTP_PORT` |
| `chappe.tls.enabled` | `CHAPPE_TLS_ENABLED` |
| `chappe.tls.keystore-password` | `CHAPPE_TLS_KEYSTORE_PASSWORD` |

## Activating a Deployment Profile

```bash
# Start in production mode
java -Dmp.config.profile=prod -jar chappe.jar

# Via environment variable
MP_CONFIG_PROFILE=prod java -jar chappe.jar
```

## Configuration Expressions

Use expressions to avoid duplication:

```properties
chappe.base-url=http://localhost:${chappe.http.port}
chappe.health-endpoint=${chappe.base-url}/health
```

## External Configuration Sources

For dynamic configurations (e.g., from a centralised configuration service):

```java
public class RemoteConfigSource implements ConfigSource {

    @Override
    public Map<String, String> getProperties() {
        // Read from an HTTP endpoint, Redis, etc.
        return fetchFromRemote();
    }

    @Override
    public int getOrdinal() { return 350; } // Between system props (400) and env vars (300)

    @Override
    public String getName() { return "remote-config"; }
}
```
