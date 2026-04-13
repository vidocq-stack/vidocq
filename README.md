# Vidocq

Serveur d'applications Java SE modulaire construit sur [Vauban](https://github.com/VidocqMP/vauban) (CDI 4.1).

## Architecture

Vidocq est un serveur léger avec un mécanisme d'extensions inspiré de Quarkus, mais plus simple :

```
vidocq-spi          Interfaces d'extension (VidocqExtension, VidocqConfiguration)
vidocq-core         Moteur : bootstrap, découverte d'extensions, cycle de vie
vidocq-maven-plugin Plugin Maven : indexation des beans + packaging en distribution ZIP
```

### Mécanisme d'extensions

Les extensions implémentent `VidocqExtension` et sont découvertes via `ServiceLoader`.

Cycle de vie :

1. **configure** — configuration avant le boot CDI
2. **beforeStart** — enrichissement du `VaubanContainerBuilder`
3. **onStart** — le container CDI est prêt, démarrage des services
4. **onStop** — arrêt (ordre inverse des priorités)

```java
public class MonExtension implements VidocqExtension {

    @Override
    public String name() { return "mon-extension"; }

    @Override
    public int priority() { return 1000; }

    @Override
    public void onStart(ExtensionContext context) {
        // Le container CDI est prêt
        var beanManager = context.beanManager();
    }
}
```

Enregistrement via `META-INF/services/fr.vidocq.vidocq.spi.VidocqExtension` ou `module-info.java` :

```java
provides VidocqExtension with MonExtension;
```

### Configuration

Les propriétés sont résolues dans l'ordre :
1. Propriétés système (`-Dkey=value`)
2. Variables d'environnement (`KEY_NAME`)
3. Fichier `vidocq.properties` du classpath

## Prérequis

- Java 25 (`sdk use java 25-tem`)
- Maven 4.0.0-rc-5 (`sdk use maven 4.0.0-rc-5`)

## Build

```bash
mvn clean install
```

## Packaging

Avec le plugin Maven :

```xml
<plugin>
    <groupId>fr.vidocq.vidocq</groupId>
    <artifactId>vidocq-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
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

Produit une distribution ZIP :

```
myapp-1.0/
  bin/myapp.sh    Lanceur Unix (module-path)
  bin/myapp.cmd   Lanceur Windows
  lib/*.jar       Application + dépendances
```

## Extensions disponibles

| Extension | Description |
|-----------|-------------|
| [vidocq-rest-extension](https://github.com/VidocqMP/vidocq-rest-extension) | JAX-RS via Jersey + Grizzly |

## Licence

Apache License 2.0
