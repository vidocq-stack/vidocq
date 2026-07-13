# mansart-data-processor

Mansart annotation processor (APT) — **`SourceVersion.RELEASE_25`**. Generates, at user module compilation:

1. The **rich Mansart metamodel** `_<Entity>` (typed attributes, static `EntityModel<T>`).
2. The **standard JPA metamodel** `<Entity>_` (only if `jakarta.persistence-api` is on the compile-classpath).
3. The **static implementation** `<Repo>Impl` for each detected `@Repository` interface.
4. The `META-INF/mansart-repositories.list` file (used by the CDI BCE).

## When to use it

This path is **optional** since M7: if the APT hasn't run on the module declaring the interface (typical case: `@Repository` interfaces compiled in a third-party jar, or official TCK), `mansart-data-core` generates the implementation **at runtime** via Class-File API.

The APT remains useful for:
- Compile-time validation (early error detection: invalid derived method name, syntactically wrong JDQL `@Query`, non-existent attribute…).
- Bootstrap performance (no bytecode generation at startup).
- AOT / native image (GraalVM, Leyden) — although runtime generation is also AOT-compatible.

## Maven configuration

```xml
<dependency>
    <groupId>io.vidocq.mansart</groupId>
    <artifactId>mansart-data-processor</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>4.0.0-beta-4</version>
            <configuration>
                <release>25</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.vidocq.mansart</groupId>
                        <artifactId>mansart-data-processor</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

> The `maven-compiler-plugin` 3.13.0/3.14.0 does not read Java 25 class files (major 69). **Use 4.0.0-beta-4**.

## Output

`target/generated-sources/annotations/`:

```
shop/_Author.java       ← rich Mansart metamodel
shop/Author_.java       ← standard JPA metamodel (if jakarta.persistence-api present)
shop/AuthorRepositoryImpl.java
META-INF/mansart-repositories.list
```

## Compile-time validation

Errors emitted by the processor:
- Repository references an entity without `@Entity`.
- Attribute without `@Id` / multiple non-composite `@Id`.
- `@Find List<X> findByXyz(...)` with `xyz` not an attribute of `X`.
- Syntactically invalid JDQL `@Query`.
- Mansart **and** JPA annotations mixed on the same entity.

## Java module

```java
module io.vidocq.mansart.data.processor {
    requires java.compiler;
    requires io.vidocq.mansart.data.api;
    requires io.vidocq.mansart.data.dialect.spi;

    provides javax.annotation.processing.Processor
        with io.vidocq.mansart.data.processor.MansartProcessor;
}
```
