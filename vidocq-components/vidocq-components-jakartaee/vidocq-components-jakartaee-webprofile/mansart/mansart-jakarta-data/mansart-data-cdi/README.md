# mansart-data-cdi

Bootstrap **CDI 4.1 Lite** pour Mansart Data. Standard `jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` — compatible avec **Vauban** (Vidocq), **Weld embedded**, **OpenWebBeans**.

## Comportement

Au démarrage du conteneur CDI :

1. `MansartDataExtension` (BCE) parcourt les classes scannées (`@Discovery` / `@Enhancement`) à la recherche d'interfaces annotées `@jakarta.data.repository.Repository`.
2. Pour chacune, en `@Synthesis`, enregistre un **bean synthétique `@Singleton`** typé sur l'interface, dont la fonction `create` délègue à `MansartRuntimeRepoCreator` (qui passe par la **génération bytecode runtime** de `mansart-data-core`).
3. Le `RepositoryRuntime` requis par chaque repository est produit par `MansartRuntimeProducer` à partir d'un `DataSource` injecté par l'application.

L'application n'a qu'à exposer un `DataSource` (par exemple via un `@Produces`) et `@Inject` ses repositories.

## Producer override

Pour prendre la main sur le wiring du `RepositoryRuntime` (dialect explicite, multi-DataSource, pool custom), déclarer son propre `@Produces RepositoryRuntime` :

```java
@ApplicationScoped
public class MyRuntime {
    @Produces @Singleton
    public RepositoryRuntime runtime(@MyApp DataSource ds) {
        return MansartData.builder()
                .dataSource(ds)
                .dialect(new PostgresqlDialect())
                .build()
                .runtime();
    }
}
```

Le producer par défaut (`MansartRuntimeProducer`) peut être désactivé en l'excluant des classes scannées (selon le conteneur).

## Module JPMS

```java
module io.vidocq.mansart.data.cdi {
    requires io.vidocq.mansart.data.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires java.sql;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
        with io.vidocq.mansart.data.cdi.MansartDataExtension;
}
```

Le `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` est aussi fourni pour les conteneurs sans support JPMS.

## Dépendances

- `mansart-data-core`
- `jakarta.cdi-api` 4.1
- `jakarta.inject-api`
