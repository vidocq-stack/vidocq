# mansart-data-cdi

**CDI 4.1 Lite** bootstrap for Mansart Data. Standard `jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` — compatible with **Vauban** (Vidocq), **Weld embedded**, **OpenWebBeans**.

## Behavior

At CDI container startup:

1. `MansartDataExtension` (BCE) traverses scanned classes (`@Discovery` / `@Enhancement`) looking for interfaces annotated `@jakarta.data.repository.Repository`.
2. For each one, in `@Synthesis`, registers a **synthetic `@Singleton` bean** typed on the interface, whose `create` function delegates to `MansartRuntimeRepoCreator` (which goes through **runtime bytecode generation** of `mansart-data-core`).
3. The `RepositoryRuntime` required by each repository is produced by `MansartRuntimeProducer` from a `DataSource` injected by the application.

The application only needs to expose a `DataSource` (for example via a `@Produces`) and `@Inject` its repositories.

## Producer override

To take control of the `RepositoryRuntime` wiring (explicit dialect, multi-DataSource, custom pool), declare your own `@Produces RepositoryRuntime`:

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

The default producer (`MansartRuntimeProducer`) can be disabled by excluding it from scanned classes (depending on the container).

## Java module

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

The `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension` is also provided for containers without Java Modules support.

## Dependencies

- `mansart-data-core`
- `jakarta.cdi-api` 4.1
- `jakarta.inject-api`
