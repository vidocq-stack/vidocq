# mansart-data-processor

Annotation processor (APT) Mansart — **`SourceVersion.RELEASE_25`**. Génère, à la compilation des modules utilisateur :

1. Le **métamodèle Mansart riche** `_<Entity>` (attributs typés, `EntityModel<T>` statique).
2. Le **métamodèle JPA standard** `<Entity>_` (uniquement si `jakarta.persistence-api` est sur le compile-classpath).
3. L'**implémentation statique** `<Repo>Impl` pour chaque interface `@Repository` détectée.
4. Le fichier `META-INF/mansart-repositories.list` (utilisé par le BCE CDI).

## Quand l'utiliser

Cette voie est **optionnelle** depuis M7 : si l'APT n'a pas tourné sur le module qui déclare l'interface (cas typique : interfaces `@Repository` compilées dans un jar tiers, ou TCK officiel), `mansart-data-core` génère l'implémentation **à runtime** via Class-File API.

L'APT reste utile pour :
- Validation à compile-time (détection précoce des erreurs : nom de méthode dérivable invalide, `@Query` JDQL syntaxiquement faux, attribut inexistant…).
- Performance de bootstrap (pas de génération de bytecode au démarrage).
- AOT / native image (GraalVM, Leyden) — bien que la génération runtime soit aussi AOT-compatible.

## Configuration Maven

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

> Le plugin `maven-compiler-plugin` 3.13.0/3.14.0 ne lit pas les class files Java 25 (major 69). **Utiliser 4.0.0-beta-4**.

## Sortie

`target/generated-sources/annotations/` :

```
shop/_Author.java       ← métamodèle Mansart riche
shop/Author_.java       ← métamodèle JPA standard (si jakarta.persistence-api présent)
shop/AuthorRepositoryImpl.java
META-INF/mansart-repositories.list
```

## Validation à compile-time

Erreurs émises par le processor :
- Repository référence une entité sans `@Entity`.
- Attribut sans `@Id` / plusieurs `@Id` non composite.
- `@Find List<X> findByXyz(...)` avec `xyz` non attribut de `X`.
- `@Query` JDQL syntaxiquement invalide.
- Annotations Mansart **et** JPA mélangées sur la même entité.

## Module JPMS

```java
module io.vidocq.mansart.data.processor {
    requires java.compiler;
    requires io.vidocq.mansart.data.api;
    requires io.vidocq.mansart.data.dialect.spi;

    provides javax.annotation.processing.Processor
        with io.vidocq.mansart.data.processor.MansartProcessor;
}
```
