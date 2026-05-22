# Intégration Ravel dans Vauban (Conteneur CDI)

> Guide d'intégration pour enregistrer `Config` comme bean CDI dans le conteneur
> Vauban et permettre l'injection de propriétés de configuration.

## Présentation

Vauban est le conteneur CDI Vidocq. Avec `ravel-cdi-vauban`, Vauban obtient le support
complet de MicroProfile Config 3.1 : `@Inject Config`, `@ConfigProperty`, `@ConfigProperties`.

## Architecture de l'intégration

```
Application
    │
    ▼
Vauban (CDI container)
    │  ├── ravel-cdi-vauban (BCE + producers)
    │  │       └── ConfigCdiExtension (synthétise les beans @ConfigProperty)
    │  │       └── ConfigSyntheticCreator (injecte le Config complet)
    │  │       └── RavelConfigPropertyResolver (résout à runtime)
    │  └── ravel-core (implémentation Config)
    │           └── RavelConfigProviderResolver
    │           └── SystemPropertiesConfigSource (400)
    │           └── EnvironmentVariablesConfigSource (300)
    │           └── MicroprofilePropertiesConfigSource (100)
```

## Dépendances Maven

Dans le `pom.xml` parent de Vauban ou dans les modules applicatifs :

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

Dans les modules qui ont besoin de MP Config :

```xml
<dependencies>
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-cdi-vauban</artifactId>
    </dependency>
</dependencies>
```

## Configuration JPMS

```java
module io.vidocq.vauban.mymodule {
    requires io.vidocq.ravel.api;
    requires io.vidocq.ravel.cdi.vauban;
    requires jakarta.inject;
    // ...
}
```

## Utilisation dans des beans Vauban

### Injection du Config complet

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

### Injection de propriétés individuelles

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

### Groupes de propriétés avec `@ConfigProperties`

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

    public java.util.Optional<String> schema;  // db.schema (optionnel)
}
```

Injection :

```java
@Inject
@ConfigProperties
private DatabaseConfig dbConfig;

// Avec un préfixe différent (ex: base de données secondaire)
@Inject
@ConfigProperties(prefix = "db.secondary")
private DatabaseConfig secondaryDbConfig;
```

## Enregistrement de la BCE dans Vauban

La BCE `ConfigCdiExtension` est enregistrée automatiquement via ServiceLoader :

```
META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
    → io.vidocq.ravel.cdi.ConfigCdiExtension
```

Si vous utilisez Vauban avec scanning classpath, aucune configuration supplémentaire n'est requise.
Si vous utilisez `SeContainerInitializer.disableDiscovery()`, ajoutez explicitement la BCE :

```java
SeContainerInitializer initializer = SeContainerInitializer.newInstance()
        .disableDiscovery()
        .addBeanClasses(MyService.class, ServerConfig.class)
        // ravel-cdi-vauban ajoute automatiquement sa BCE si le JAR est sur le classpath
        ;
```

## Sources de configuration personnalisées pour Vauban

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
    public int getOrdinal() { return 250; } // Priorité entre env vars et properties file

    private Map<String, String> readVaubanConfiguration() {
        // Lire la configuration interne de Vauban
        return new HashMap<>();
    }
}
```

Enregistrement via ServiceLoader :

```
META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource
    → io.vidocq.vauban.VaubanInternalConfigSource
```

## Lookup programmatique

En dehors de l'injection CDI :

```java
import org.eclipse.microprofile.config.ConfigProvider;

// Lookup programmatique (sans CDI)
Config config = ConfigProvider.getConfig();
String host = config.getValue("server.host", String.class);

// Avec Optional
Optional<Integer> port = config.getOptionalValue("server.port", Integer.class);
```

## Validation au déploiement

`ravel-cdi-vauban` valide les propriétés **requises** (sans `defaultValue`) au démarrage.
Si une propriété requise est absente, Vauban refuse de démarrer avec un `DeploymentException` :

```
DeploymentException: Missing required config property 'db.host' for type String
  at injection point @Inject @ConfigProperty private ServerConfig.dbHost
```

Pour les propriétés optionnelles, utilisez `Optional<T>` ou `defaultValue` :

```java
@Inject
@ConfigProperty(name = "feature.x.enabled", defaultValue = "false")
private boolean featureXEnabled;

@Inject
@ConfigProperty(name = "optional.feature")
private Optional<String> optionalFeature;
```

## Intégration avec `vidocq`

Pour remplacer Smallrye Config dans `vidocq` :

1. Supprimer la dépendance `io.smallrye.config:smallrye-config`
2. Ajouter `io.vidocq.ravel:ravel-cdi-vauban`
3. Rebuilder — aucune modification du code applicatif n'est requise

Le `ServiceLoader` remplacera automatiquement l'implémentation `ConfigProviderResolver`.

