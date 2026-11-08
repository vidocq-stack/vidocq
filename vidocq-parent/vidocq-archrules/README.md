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
| `noReflection(fallbacks, source)` | no `java.lang.reflect`, no `Class.forName`, outside the documented fallbacks (each with a reason) | workspace `AGENTS.md`, "Maximum static code generation" |
| `noPlatformThreads(policy, source)` | no platform-thread pool or platform thread in I/O code | workspace `AGENTS.md`, "Virtual Threads everywhere for I/O" |
| `noThreadLocal(why, source)` | no `ThreadLocal` | same, plus the brick's reference page |
| `neverDependOn(from, forbidden, source)` | foundational bricks never depend on each other, `*-api` never on `*-core` | workspace `AGENTS.md`, dependency graph |
| `layers(source, Layer...)` | the layers of one brick and who may reach them | the brick's reference page |

`Jdk` lists the JDK packages a module may use, `Packages.of` builds a package list, and `RuleAssertions` lets a brick
test each rule on fixture classes that break it and that keep it.

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
