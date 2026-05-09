# Intégration Ravel dans Cassini (REST Jakarta EE)

> Guide d'intégration pour injecter des propriétés de configuration MicroProfile Config 3.1
> via Ravel dans les ressources REST Cassini.

## Présentation

Cassini est le serveur REST Vidocq (Jakarta RESTful Web Services). Avec `ravel-cdi-vauban`,
les ressources JAX-RS peuvent injecter des propriétés de configuration via `@ConfigProperty`
sans dépendance sur Smallrye Config.

## Dépendances Maven

Dans votre module `cassini-*` ou application utilisant Cassini :

```xml
<dependencies>
    <!-- Ravel CDI (inclut ravel-core transitively) -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-cdi-vauban</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <!-- API MicroProfile Config (fournie par ravel-api) -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

## Configuration JPMS

Dans votre `module-info.java` :

```java
module com.example.myapp {
    requires io.vidocq.ravel.api;         // API MicroProfile Config
    requires io.vidocq.ravel.cdi.vauban;  // Intégration CDI
    requires jakarta.inject;
    // ...
}
```

## Utilisation dans une ressource REST

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

## Fichier de configuration

Créez `META-INF/microprofile-config.properties` dans vos ressources :

```properties
# application.properties (ordinal 100)
app.greeting=Bonjour
app.port=8080
# app.debug est optionnel — sera Optional.empty() si absent
```

Sources de configuration (par ordre de priorité décroissante) :

| Source | Ordinal | Exemple |
|---|---|---|
| Propriétés système | 400 | `-Dapp.port=9090` |
| Variables d'env | 300 | `APP_PORT=9090` |
| `microprofile-config.properties` | 100 | voir ci-dessus |

## Support des profils

Activez un profil en ajoutant `-Dmp.config.profile=prod` au démarrage :

```properties
# microprofile-config.properties
app.port=8080
%prod.app.port=443
%dev.app.port=8081
```

## Support de `@ConfigProperties` (groupes de propriétés)

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

Injection :

```java
@Inject
@ConfigProperties
private DatabaseConfig db;
```

Configuration :

```properties
db.host=localhost
db.port=5432
db.name=myapp
```

## Intégration avec `cassini-cdi-vauban`

Aucune configuration supplémentaire n'est requise. `ravel-cdi-vauban` enregistre
automatiquement sa Build Compatible Extension via `META-INF/services`.

Le conteneur Vauban découvrira la BCE et synthétisera les beans nécessaires pour
satisfaire les points d'injection `@ConfigProperty`.

## Sources de configuration personnalisées

Implémentez `org.eclipse.microprofile.config.spi.ConfigSource` :

```java
public class DatabaseConfigSource implements ConfigSource {
    @Override
    public Map<String, String> getProperties() {
        // Lire depuis la base de données, un service, etc.
        return Map.of("app.feature.x", "true");
    }

    @Override
    public String getValue(String propertyName) {
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() { return "database-config"; }

    @Override
    public int getOrdinal() { return 200; } // Entre env vars et properties file
}
```

Enregistrez via `META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource` :

```
com.example.DatabaseConfigSource
```

## Convergence Ravel vs Smallrye Config

Ravel est un remplacement drop-in de Smallrye Config :

| Feature | Ravel | Smallrye |
|---|---|---|
| `@ConfigProperty` CDI | ✅ | ✅ |
| `@ConfigProperties` | ✅ | ✅ |
| Property expressions `${key}` | ✅ | ✅ |
| Profils `%dev.` | ✅ | ✅ |
| Arrays / Collections | ✅ | ✅ |
| Implicit converters | ✅ | ✅ |
| **Zero dépendances** | ✅ | ❌ (Smallrye Common, etc.) |
| **TCK 100 % PASS** | ✅ | ✅ |

Pour migrer depuis Smallrye, supprimez `io.smallrye.config:smallrye-config` et ajoutez
`io.vidocq.ravel:ravel-cdi-vauban`. Aucune modification du code applicatif n'est requise.

