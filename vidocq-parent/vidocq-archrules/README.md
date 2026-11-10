# vidocq-archrules

The architecture rules of the Vidocq ecosystem as [ArchUnit](https://www.archunit.org/) rules, shared by every brick
(Vidocq/vidocq#289). Build and test tooling: a brick takes it in **test scope**, it is never shipped and never part of
a brick's runtime. It brings ArchUnit's core (Apache-2.0, `slf4j-api`). It has no `module-info.java` (it sits on the
class path of a brick's tests) and carries the stable name `io.vidocq.archrules` as `Automatic-Module-Name`.

## Rules

Each factory of `VidocqArchRules` takes the `source`, the document the rule comes from, and puts it in the failure
message.

| Factory | Rule | Origin |
|---|---|---|
| `dependOnlyOn(subject, allowed, source)` | zero external dependency, checked on the compiled classes | workspace `AGENTS.md`, "Zero external dependency for what ships" |
| `noReflection(exceptions, source)` | no reflection that reads or invokes user code, outside the documented adapters (each with a reason) | workspace `AGENTS.md`, "Maximum static code generation" |
| `noReflection(fallbacks, source)` | deprecated: any `java.lang.reflect`, outside a list of class names | same |
| `noPlatformThreads(policy, source)` | no platform-thread pool or platform thread in I/O code | workspace `AGENTS.md`, "Virtual Threads everywhere for I/O" |
| `noThreadLocal(why, source)` | no `ThreadLocal` | same, plus the brick's reference page |
| `neverDependOn(from, forbidden, source)` | foundational bricks never depend on each other, `*-api` never on `*-core` | workspace `AGENTS.md`, dependency graph |
| `layers(source, Layer...)` | the layers of one brick and who may reach them | the brick's reference page |

`Jdk` lists the JDK packages a module may use, `Packages.of` builds a package list, and `RuleAssertions` lets a brick
test each rule on fixture classes that break it and that keep it.

## The reflection rule

`noReflection(ReflectionExceptions, source)` is split by intent. It forbids the reflection that **reads or invokes
user code**: `Class.forName`, `ClassLoader.loadClass`, `newInstance`, `getDeclared*` / `getMethods` / `getFields` /
`getConstructors` / `getRecordComponents`, annotation reads, and any use of `Method`, `Field`, `Constructor`,
`Parameter`, `Proxy`, `Array` and the other member-level classes of `java.lang.reflect`. It lets through what only
carries a type or a flag: `Type`, `ParameterizedType`, `WildcardType`, `TypeVariable`, `GenericArrayType`,
`GenericDeclaration` and `Modifier` (a JAX-RS `MessageBodyReader` signature, a JSON-B type token, a generic
`Converter<T>`), so a brick no longer lists the classes that merely hold a type.

A brick declares an adapter, a place where reflection is wanted, with a mandatory reason, by package, by annotation
or by class (a class entry also covers its nested classes, an annotation on an outer class covers what it encloses):

```java
static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
        ReflectionExceptions.builder()
                .inPackage("io.vidocq.foy.internal.gen..", "the registry tiers load generated companions by name")
                .annotatedWith(ReflectiveAdapter.class, "reads the annotations of application classes at run time")
                .inClass("io.vidocq.foy.boot.ComponentFactory", "its reflective factory, on explicit opt-out only")
                .build(),
        "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");
```

A blank reason is refused. The description of the rule prints the source document, then every adapter with its
reason, and says to declare a new one in `ReflectionExceptions`; a violation names the class and the call. Prefer a
package or an annotation to a class name: a new class in the package, or carrying the marker, is documented by where
it sits. `ReflectionExceptions.none()` is a brick with no adapter.

Reflection on the JDK or on the brick's own types cannot be told apart from reflection on user code by reading the
bytecode (`Class` is the receiver in both cases), so such a call is declared as an adapter like any other.

## Use

```xml
<dependency>
    <groupId>io.vidocq</groupId>
    <artifactId>vidocq-archrules</artifactId>
    <version>0.4.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Then write an `ArchitectureRules` class of constants built with the factories for the brick's packages, and a JUnit
test that imports the shipped classes with `ClassFileImporter` and calls `rule.check(classes)`. See `chappe-tests`
(package `architecture`) and `foy-architecture-tests` for the two reference uses.

The brick's reference page names this library in its test-tooling section, as the zero-dependency rule asks.
