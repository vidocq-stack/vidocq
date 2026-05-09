# Intégration Ravel dans Chappe (Serveur HTTP)

> Guide d'intégration pour configurer le serveur HTTP Chappe via MicroProfile Config 3.1
> avec Ravel comme implémentation.

## Présentation

Chappe est le serveur HTTP Vidocq. Ravel permet de lire la configuration du serveur
(port, TLS, timeouts, etc.) depuis des sources de configuration standard MicroProfile.

## Dépendances Maven

```xml
<dependencies>
    <!-- Ravel core (sans CDI, pour contexte standalone) -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-core</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <!-- API MicroProfile Config -->
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

Avec CDI (Vauban) :

```xml
<dependencies>
    <dependency>
        <groupId>io.vidocq.ravel</groupId>
        <artifactId>ravel-cdi-vauban</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

## Configuration JPMS

```java
module io.vidocq.chappe.server {
    requires io.vidocq.ravel.api;   // ou ravel.core si sans CDI
    // ...
}
```

## Utilisation programmatique (sans CDI)

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

## Fichier de configuration recommandé

```properties
# META-INF/microprofile-config.properties
chappe.http.port=8080
chappe.http.host=0.0.0.0
chappe.http.max-connections=1000
chappe.http.idle-timeout=PT30S

# TLS (désactivé par défaut)
chappe.tls.enabled=false
chappe.tls.keystore=/etc/chappe/keystore.jks
chappe.tls.keystore-password=changeit

# Profils
%prod.chappe.http.port=443
%prod.chappe.tls.enabled=true
%dev.chappe.http.port=8081
```

## Configuration via variables d'environnement

MicroProfile Config (et Ravel) mappe automatiquement les variables d'environnement :

| Propriété | Variable d'environnement |
|---|---|
| `chappe.http.port` | `CHAPPE_HTTP_PORT` |
| `chappe.tls.enabled` | `CHAPPE_TLS_ENABLED` |
| `chappe.tls.keystore-password` | `CHAPPE_TLS_KEYSTORE_PASSWORD` |

## Activation d'un profil de déploiement

```bash
# Démarrage en mode production
java -Dmp.config.profile=prod -jar chappe.jar

# Via variable d'environnement
MP_CONFIG_PROFILE=prod java -jar chappe.jar
```

## Expressions de configuration

Utilisez des expressions pour éviter la duplication :

```properties
chappe.base-url=http://localhost:${chappe.http.port}
chappe.health-endpoint=${chappe.base-url}/health
```

## Sources de configuration externes

Pour des configurations dynamiques (ex: via un service de configuration centralisé) :

```java
public class RemoteConfigSource implements ConfigSource {

    @Override
    public Map<String, String> getProperties() {
        // Lecture depuis un endpoint HTTP, Redis, etc.
        return fetchFromRemote();
    }

    @Override
    public int getOrdinal() { return 350; } // Entre system props (400) et env vars (300)

    @Override
    public String getName() { return "remote-config"; }
}
```

