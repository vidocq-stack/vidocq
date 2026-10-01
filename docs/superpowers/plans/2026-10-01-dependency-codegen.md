# Generated Code for Third-Party CDI Jars — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scanned third-party CDI jar gets per-package `_VaubanComponents`, client proxies and intercepted
subclasses (Class-File), carried by one enriched copy whose descriptor declares them, substituted for the original
in every launch shape; jars are selected by the extension that ships them, `scanDependencies`, or a `beans.xml`
detection; `vidocq:analyze-deps` explains the selection; the docs say all of it.

**Architecture:** vauban's `VaubanGenerator` gains an opt-in `dependencyProviders` mode, `ModuleInfoRewriter` gains
extra `requires`, `Modularizer` gains a single-jar synthesis. vidocq's plugin gains `EnrichedJars` (write, resolve,
enrich all), `ScanSelection` (sources and exclusions), `AnalyzeReport` and the `analyze-deps` goal; `generate` wires
them, and `ApplicationLaunch`, `package` and `jlink` resolve through `EnrichedJars`.

**Tech Stack:** Java 25 (Class-File API), Maven 3.9.16 plugins, JUnit 5, Antora docs.

**Spec:** `vidocq/docs/superpowers/specs/2026-10-01-dependency-codegen-design.md`

## Global Constraints

- Java 25 + Maven 3.9.16: `sdk env` in `vauban/` and `vidocq/` before any command.
- Branches: vauban `feat/dependency-providers` (on `feat/codegen-coverage`), vidocq `feat/dependency-codegen` (on
  `feat/devconsole-codegen-coverage`). Never push or open a PR without the maintainer's go.
- English for code, Javadoc, comments, commits, docs. In vauban, write "Java Modules", never the abbreviation.
- No new `<dependency>` in any `pom.xml`.
- TDD: test first, watch it fail for the stated reason, then code.
- Always `clean` when building `vauban-core`, `vauban-maven-plugin` or `vidocq-runtime-maven-plugin`: an IDE
  compiler writes into `target/`. Reactor dependencies come from the local repository: run
  `./mvnw -ntp clean install -DskipTests` in `vauban/` before the first vidocq task, and after each vauban task.
- Every new Java file starts with the 19-line license header of `vauban-api/.../ProxyLink.java` (lines 1–19).
- Commits: Conventional Commits, `git commit -s`, GPG on, trailer `Co-Authored-By: Claude Opus 5.5
  <noreply@anthropic.com>`; vidocq commits reference `Refs: #186`, vauban commits `Refs: Vidocq/vidocq#186`.
- Generated-code selection must stay silent for already-processed jars, as today.

## Review Focus

1. **A multi-release jar whose only descriptor is versioned** (`META-INF/versions/N/module-info.class`): it must
   not be enriched with a second, conflicting root descriptor; expected: left to `--patch-module` with a warning.
   Pinned in Task 4 (`aJarWithOnlyAVersionedDescriptorIsLeftToPatching`).
2. **A stale enriched copy from an earlier run** when the jar is no longer scanned: it must not be substituted.
   Pinned in Task 4 (`clearRemovesEveryEnrichedCopy`) and wired in Task 5 (cleared before any early return).
3. **A scanned jar whose generator produced classes but no provider** (only proxies): the copy must not get an empty
   `provides`. Pinned in Task 2 (`addsExtraRequiresAndKeepsProvidesUntouchedWhenNoProvider`).
4. **A dependency that is a reactor directory**, not a jar: selection must read it without failing. Pinned in
   Task 6 (`aReactorDirectoryIsInspectedLikeAJar`).
5. **A signed jar named explicitly in `scanDependencies`**: it is enriched (signature dropped) rather than skipped.
   Pinned in Task 6 (`aSignedJarIsSkippedUnlessTheApplicationNamesIt`).

---

## Part A — vauban (`/Users/yblazart/projects/perso/vidocq/vauban`)

### Task 1: `VaubanGenerator` writes providers for scanned jars

**Files:**
- Modify: `vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/VaubanGenerator.java`
- Modify: `vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/GenerationResult.java`
- Test: `vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/generate/VaubanGeneratorTest.java`

**Interfaces:**
- Produces: `VaubanGenerator.Config(List<Path> dependencyJars, Path projectClassesDir, Path outputDir,
  ClassLoader classLoader, boolean dependencyProviders)` (+ the existing 3- and 4-arg constructors, `false`);
  `GenerationResult.generatedProviders()` (`List<String>`, FQNs of the dependency providers written).

- [ ] **Step 1: Write the failing tests** — add to `VaubanGeneratorTest`:

```java
    @Test
    @DisplayName("writes a _VaubanComponents per package of a scanned jar, listed in no service file")
    void shouldGenerateProvidersForScannedJarBeans() throws IOException {
        var jarPath = createTestJar("dep-lib.jar",
                new TestClass("org.dep.lib.Service", CD_DEPENDENT),
                new TestClass("org.dep.lib.Helper", null));
        var outputDir = tempDir.resolve("dep-output");
        Files.createDirectories(outputDir);

        var result = VaubanGenerator.generate(
                new VaubanGenerator.Config(List.of(jarPath), null, outputDir, null, true));

        assertEquals(List.of("org.dep.lib._VaubanComponents"), result.generatedProviders());
        assertTrue(Files.isRegularFile(outputDir.resolve("org/dep/lib/_VaubanComponents.class")));
        assertFalse(Files.exists(outputDir.resolve(
                        "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider")),
                "a dependency's providers are declared by its own module, not by the project");
    }

    @Test
    @DisplayName("writes no provider for a scanned jar by default: vauban:generate is unchanged")
    void shouldNotGenerateProvidersForScannedJarByDefault() throws IOException {
        var jarPath = createTestJar("dep-lib2.jar", new TestClass("org.dep.lib2.Service", CD_DEPENDENT));
        var outputDir = tempDir.resolve("dep-output2");
        Files.createDirectories(outputDir);

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(jarPath), null, outputDir));

        assertEquals(List.of(), result.generatedProviders());
        assertFalse(Files.exists(outputDir.resolve("org/dep/lib2/_VaubanComponents.class")));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test -Dtest=VaubanGeneratorTest`
Expected: COMPILATION ERROR — no 5-arg `Config` constructor, no `generatedProviders()`.

- [ ] **Step 3: `GenerationResult`** — add a sixth component and keep the five-argument constructor:

```java
public record GenerationResult(
        List<String> discoveredBeanClasses,
        List<String> generatedProxies,
        List<String> generatedInterceptors,
        List<String> wovenBeanClasses,
        List<String> warnings,
        List<String> generatedProviders
) {
    public GenerationResult {
        discoveredBeanClasses = List.copyOf(discoveredBeanClasses);
        generatedProxies = List.copyOf(generatedProxies);
        generatedInterceptors = List.copyOf(generatedInterceptors);
        wovenBeanClasses = List.copyOf(wovenBeanClasses);
        warnings = List.copyOf(warnings);
        generatedProviders = List.copyOf(generatedProviders);
    }

    /** A result without dependency providers. */
    public GenerationResult(List<String> discoveredBeanClasses, List<String> generatedProxies,
                            List<String> generatedInterceptors, List<String> wovenBeanClasses, List<String> warnings) {
        this(discoveredBeanClasses, generatedProxies, generatedInterceptors, wovenBeanClasses, warnings, List.of());
    }
}
```

Add to the record's Javadoc: `@param generatedProviders the {@code _VaubanComponents} written for the packages of
scanned dependency jars (only with {@code Config#dependencyProviders})`.

- [ ] **Step 4: `Config`** — replace the record with:

```java
    /**
     * Configuration for the generator.
     *
     * @param dependencyJars      JAR files to scan for CDI beans
     * @param projectClassesDir   project's compiled classes directory (may be null)
     * @param outputDir           where to write generated files
     * @param classLoader         ClassLoader with all deps + project classes for proxy generation (may be null to
     *                            skip generation)
     * @param dependencyProviders also write a {@code _VaubanComponents} per package of the scanned jars' managed
     *                            beans, for a caller that moves them into those jars' modules ({@code vidocq:generate})
     */
    public record Config(
            List<Path> dependencyJars,
            Path projectClassesDir,
            Path outputDir,
            ClassLoader classLoader,
            boolean dependencyProviders
    ) {
        public Config {
            dependencyJars = List.copyOf(dependencyJars);
        }

        /** Config for the project's own providers only, as {@code vauban:generate} uses it. */
        public Config(List<Path> dependencyJars, Path projectClassesDir, Path outputDir, ClassLoader classLoader) {
            this(dependencyJars, projectClassesDir, outputDir, classLoader, false);
        }

        /** Config without ClassLoader — discovery only, no proxy generation. */
        public Config(List<Path> dependencyJars, Path projectClassesDir, Path outputDir) {
            this(dependencyJars, projectClassesDir, outputDir, null, false);
        }
    }
```

- [ ] **Step 5: Record which classes come from scanned jars** — in `generate`, declare before the
`// 1. Scan dependency JARs` loop:

```java
        var dependencyClasses = new LinkedHashSet<String>();
```

and in the jar branch, right after `indexBuilder.addAll(classInfos);`:

```java
                        classInfos.forEach(ci -> dependencyClasses.add(ci.name().value()));
```

- [ ] **Step 6: Write the dependency providers** — replace the end of `generate`:

```java
        generateComponentProvider(config, index, beans, warnings);

        return new GenerationResult(sortedBeanClassNames, generatedProxies, generatedInterceptors,
                wovenBeans, warnings);
```

with:

```java
        generateComponentProvider(config, index, beans, warnings);

        // 10. The same providers for the scanned jars' packages, when the caller moves them into those modules.
        var generatedProviders = config.dependencyProviders()
                ? generateDependencyProviders(config, index, beans, dependencyClasses, warnings)
                : List.<String>of();

        return new GenerationResult(sortedBeanClassNames, generatedProxies, generatedInterceptors,
                wovenBeans, warnings, generatedProviders);
```

and add the method after `generateComponentProvider`:

```java
    /**
     * One {@code _VaubanComponents} per package of the scanned jars' managed beans, written to {@code outputDir} next
     * to their proxies, with the eligibility rules of the project's providers. Unlike those, they are listed in no
     * service file: they live in another module's packages, so the caller moves them into that module and declares
     * them there.
     *
     * @return the providers written, by fully-qualified name
     */
    private static List<String> generateDependencyProviders(Config config,
            io.vidocq.vauban.indexer.VaubanIndex index, List<BeanDescriptor> beans,
            java.util.Set<String> dependencyClasses, List<String> warnings) {
        var provided = new ArrayList<ProvidedClass>();
        var clientProxyFqns = new LinkedHashSet<String>();
        for (var bean : beans) {
            if (bean.kind() != BeanKind.MANAGED) continue;
            var fqn = bean.beanClass().value();
            if (fqn.contains("$") || !dependencyClasses.contains(fqn)) continue;
            var ci = index.getClassByName(io.vidocq.vauban.indexer.model.DotName.of(fqn)).orElse(null);
            if (ci == null) continue;
            provided.add(new ProvidedClass(fqn, ci, true));
            // Only a proxy that exists: a provider must never name a class it cannot load.
            if (bean.scope().isNormal() && classFileExists(config.outputDir(), fqn + "_ClientProxy")) {
                clientProxyFqns.add(fqn + "_ClientProxy");
            }
        }
        var providerFqns = new ArrayList<String>();
        for (var pkg : ComponentCollector.collect(provided, warnings)) {
            var pkgProxies = clientProxyFqns.stream()
                    .filter(p -> packageOf(p).equals(pkg.packageName()))
                    .toList();
            try {
                var gen = io.vidocq.vauban.core.provider.ComponentProviderClassGenerator.generate(
                        pkg.providerFqn(), pkg.components(), pkg.fields(), pkg.methods(), pkgProxies, List.of());
                writeClassFile(config.outputDir(), gen.className(), gen.bytecode());
                providerFqns.add(gen.className());
            } catch (Exception e) {
                warnings.add("Failed to generate component provider for dependency package "
                        + pkg.packageName() + ": " + e.getMessage());
            }
        }
        return providerFqns;
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test -Dtest=VaubanGeneratorTest`
Expected: all `VaubanGeneratorTest` tests pass.

- [ ] **Step 8: Whole plugin suite, then install**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test` → BUILD SUCCESS; then `./mvnw -ntp clean install -DskipTests`.

- [ ] **Step 9: Commit**

```bash
git add vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/VaubanGenerator.java \
        vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/GenerationResult.java \
        vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/generate/VaubanGeneratorTest.java
git commit -s -F - <<'EOF'
feat(maven-plugin): generate providers for the scanned dependency jars

With Config#dependencyProviders, VaubanGenerator writes one
_VaubanComponents per package of the scanned jars' managed beans, next
to their proxies, and reports them in GenerationResult#generatedProviders
instead of the project's service file: the caller moves them into the
jar's module and declares them there. Off by default, so
vauban:generate is unchanged.

Refs: Vidocq/vidocq#186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 2: Extra `requires` in `ModuleInfoRewriter`, and the Vauban modules generated classes call into

**Files:**
- Modify: `vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/enhance/ModuleInfoRewriter.java`
- Create: `vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/enhance/VaubanModuleReferences.java`
- Test: `vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/enhance/ModuleInfoRewriterTest.java`
- Test: `vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/enhance/VaubanModuleReferencesTest.java`

**Interfaces:**
- Produces: `ModuleInfoRewriter.addComponentProvider(byte[] moduleInfo, List<String> providerFqns,
  Set<String> extraRequires)` (empty providers ⇒ `provides` untouched); `VaubanModuleReferences.of(
  Collection<byte[]> classFiles)` → `Set<String>` (`io.vidocq.vauban.core` when referenced, else empty).

- [ ] **Step 1: Write the failing tests** — add to `ModuleInfoRewriterTest` (imports `java.lang.classfile.*`,
`java.lang.classfile.attribute.*`, `java.lang.constant.ModuleDesc`, `java.util.Set`,
`java.util.stream.Collectors`):

```java
    @Test
    void addsExtraRequiresAndKeepsProvidesUntouchedWhenNoProvider() {
        byte[] original = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("lib.mod"), mb -> { }));

        byte[] rewritten = ModuleInfoRewriter.addComponentProvider(original, List.of(),
                Set.of("io.vidocq.vauban.core"));

        var attr = ClassFile.of().parse(rewritten).findAttribute(Attributes.module()).orElseThrow();
        assertEquals(Set.of("io.vidocq.vauban.api", "io.vidocq.vauban.core"), attr.requires().stream()
                .map(r -> r.requires().name().stringValue()).collect(Collectors.toSet()));
        assertEquals(List.of(), attr.provides());
    }
```

Create `VaubanModuleReferencesTest` (package `io.vidocq.vauban.maven.enhance`):

```java
package io.vidocq.vauban.maven.enhance;

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaubanModuleReferencesTest {

    @Test
    void findsTheCoreModuleInAClassThatCallsIntoIt() {
        byte[] calling = ClassFile.of().build(ClassDesc.of("org.dep.Calling"), clb -> clb.withMethodBody("call",
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        ClassDesc.of("io.vidocq.vauban.core.interceptor.InterceptorManager")),
                ClassFile.ACC_PUBLIC, cob -> cob.return_()));
        byte[] plain = ClassFile.of().build(ClassDesc.of("org.dep.Plain"), clb -> { });

        assertEquals(Set.of("io.vidocq.vauban.core"), VaubanModuleReferences.of(List.of(plain, calling)));
        assertEquals(Set.of(), VaubanModuleReferences.of(List.of(plain)));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test -Dtest='ModuleInfoRewriterTest,VaubanModuleReferencesTest'`
Expected: COMPILATION ERROR — no 3-arg `addComponentProvider`, no `VaubanModuleReferences`.

- [ ] **Step 3: The overload** — in `ModuleInfoRewriter`, make the existing method delegate and add the overload
(import `java.util.Set`):

```java
    /** Return new {@code module-info.class} bytes with the provider service and API requirement. */
    public static byte[] addComponentProvider(byte[] moduleInfo, List<String> providerFqns) {
        return addComponentProvider(moduleInfo, providerFqns, Set.of());
    }

    /**
     * Same as {@link #addComponentProvider(byte[], List)}, also requiring {@code extraRequires}: the Vauban modules
     * the classes added to the module call into, such as {@code io.vidocq.vauban.core} for an intercepted subclass.
     * With no provider, {@code provides} is left as it is and only the requirements are added.
     */
    public static byte[] addComponentProvider(byte[] moduleInfo, List<String> providerFqns,
                                              Set<String> extraRequires) {
        var cf = ClassFile.of();
        var model = cf.parse(moduleInfo);
        var old = model.findAttribute(Attributes.module()).orElseThrow(
                () -> new IllegalArgumentException("not a module-info.class (no ModuleAttribute)"));

        var spiCD = ClassDesc.of(SPI);
        var newProviderCDs = providerFqns.stream().map(ClassDesc::of).toList();
        var wanted = new LinkedHashSet<String>();
        wanted.add(API_MODULE);
        wanted.addAll(extraRequires);

        var newAttr = ModuleAttribute.of(old.moduleName(), mb -> {
            mb.moduleFlags(old.moduleFlagsMask());
            old.moduleVersion().ifPresent(v -> mb.moduleVersion(v.stringValue()));
            for (var e : old.exports()) mb.exports(e);
            for (var o : old.opens()) mb.opens(o);
            for (var u : old.uses()) mb.uses(u);

            // requires: copy all, add every wanted module that is absent.
            for (var r : old.requires()) {
                mb.requires(r);
                wanted.remove(r.requires().name().stringValue());
            }
            for (var name : wanted) {
                mb.requires(ModuleRequireInfo.of(ModuleDesc.of(name), 0, null));
            }

            // provides: copy all, merging our providers into the existing SPI directive (if any).
            boolean spiFound = false;
            for (var p : old.provides()) {
                if (!newProviderCDs.isEmpty() && p.provides().asSymbol().equals(spiCD)) {
                    var impls = new LinkedHashSet<ClassDesc>();
                    for (var w : p.providesWith()) impls.add(w.asSymbol());
                    impls.addAll(newProviderCDs);
                    mb.provides(ModuleProvideInfo.of(spiCD, new ArrayList<>(impls)));
                    spiFound = true;
                } else {
                    mb.provides(p);
                }
            }
            if (!spiFound && !newProviderCDs.isEmpty()) {
                mb.provides(ModuleProvideInfo.of(spiCD, newProviderCDs));
            }
        });

        return cf.transformClass(model,
                ClassTransform.dropping(el -> el instanceof ModuleAttribute)
                        .andThen(ClassTransform.endHandler(clb -> clb.with(newAttr))));
    }
```

- [ ] **Step 4: Create `VaubanModuleReferences`**:

```java
package io.vidocq.vauban.maven.enhance;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.Collection;
import java.util.Set;

/**
 * The Vauban modules a set of generated class files call into, read from their constant pools: what a module that
 * receives those classes must {@code require}. {@code io.vidocq.vauban.api} is left out, since
 * {@link ModuleInfoRewriter} always requires it.
 */
public final class VaubanModuleReferences {

    private static final String CORE_PACKAGE = "io/vidocq/vauban/core/";
    private static final String CORE_MODULE = "io.vidocq.vauban.core";

    private VaubanModuleReferences() {}

    /** {@code io.vidocq.vauban.core} when one of {@code classFiles} names a type of it, else nothing. */
    public static Set<String> of(Collection<byte[]> classFiles) {
        for (byte[] bytes : classFiles) {
            for (PoolEntry entry : ClassFile.of().parse(bytes).constantPool()) {
                boolean names = switch (entry) {
                    case ClassEntry type -> type.asInternalName().startsWith(CORE_PACKAGE);
                    // A descriptor names a type without a class entry: (Lio/vidocq/vauban/core/…;)V
                    case Utf8Entry utf -> utf.stringValue().contains("L" + CORE_PACKAGE);
                    default -> false;
                };
                if (names) {
                    return Set.of(CORE_MODULE);
                }
            }
        }
        return Set.of();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test -Dtest='ModuleInfoRewriterTest,VaubanModuleReferencesTest,DependencyEnhancerTest'`
Expected: all pass (`DependencyEnhancerTest` proves the 2-arg path is unchanged).

- [ ] **Step 6: Commit**

```bash
git add vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/enhance \
        vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/enhance
git commit -s -F - <<'EOF'
feat(maven-plugin): require what generated classes call into

ModuleInfoRewriter#addComponentProvider takes extra requires, and with
no provider leaves provides alone. VaubanModuleReferences reads, from
the constant pools of generated classes, whether a module receiving
them must read io.vidocq.vauban.core (an intercepted subclass does).

Refs: Vidocq/vidocq#186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 3: `Modularizer.synthesizeOne`

**Files:**
- Modify: `vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/modularize/Modularizer.java`
- Test: `vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/modularize/ModularizerTest.java`

**Interfaces:**
- Produces: `public static Optional<byte[]> Modularizer.synthesizeOne(Path jar, List<Path> closure, boolean open,
  Consumer<String> log) throws IOException`.

- [ ] **Step 1: Write the failing tests** — add to `ModularizerTest` (imports `java.util.Optional`,
`java.nio.ByteBuffer`):

```java
    @Test
    void synthesizeOneReturnsAnOpenDescriptorAndWritesNothing() throws IOException {
        Path jar = plainJar("acme-one-1.0.0.jar", "com.acme.one", "One", Map.of());
        Path buildDir = tmp.resolve("target");

        Optional<byte[]> moduleInfo = Modularizer.synthesizeOne(jar, List.of(jar), true, line -> { });

        assertTrue(moduleInfo.isPresent());
        ModuleDescriptor md = ModuleDescriptor.read(ByteBuffer.wrap(moduleInfo.get()));
        assertTrue(md.isOpen());
        assertEquals("acme.one", md.name());
        assertFalse(Files.exists(ModularizedJars.root(buildDir)), "the goal's own directory is not touched");
    }

    @Test
    void synthesizeOneLeavesAnExplicitModuleAlone() throws IOException {
        Path jar = tmp.resolve("m2/acme-explicit-1.0.jar");
        Files.createDirectories(jar.getParent());
        byte[] descriptor = java.lang.classfile.ClassFile.of().buildModule(
                java.lang.classfile.attribute.ModuleAttribute.of(java.lang.constant.ModuleDesc.of("acme.explicit"),
                        mb -> mb.requires(java.lang.classfile.attribute.ModuleRequireInfo.of(
                                java.lang.constant.ModuleDesc.of("java.base"),
                                java.lang.classfile.ClassFile.ACC_MANDATED, null))));
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("module-info.class"));
            out.write(descriptor);
            out.closeEntry();
        }

        assertTrue(Modularizer.synthesizeOne(jar, List.of(jar), true, line -> { }).isEmpty());
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test -Dtest=ModularizerTest`
Expected: COMPILATION ERROR — `synthesizeOne` undefined.

- [ ] **Step 3: Implement** — in `Modularizer`, after `run`, add (import `java.util.Optional`):

```java
    /**
     * A descriptor for one automatic jar, synthesized as {@link #run} would but written nowhere: neither the jar nor
     * {@code target/vauban-modularized/} is touched, so a caller can build a copy of its own. Empty when the jar
     * already has a descriptor, or when a {@code ServiceLoader} lookup it makes cannot be declared by an explicit
     * module — the reason is logged with {@link #KEPT_AUTOMATIC_PREFIX}.
     *
     * @param jar     the jar
     * @param closure every jar of the dependency closure, {@code jar} included: jdeps needs it for {@code requires}
     * @param open    synthesize an {@code open module}
     * @param log     receives what the synthesis reports
     */
    public static Optional<byte[]> synthesizeOne(Path jar, List<Path> closure, boolean open, Consumer<String> log)
            throws IOException {
        JarModuleInfo info = JarModuleClassifier.classify(jar);
        if (!info.isAutomatic()) {
            return Optional.empty();
        }
        Options options = new Options(Mode.ALL_AUTOMATIC, Set.of(), Set.of(), Map.of(), open, false);
        ModuleDescriptor first = synthesize(jar, info.moduleName(), closure, options, Set.of(), log).descriptor();
        Set<String> scanned = ServiceUsesScanner.over(closure, log).scan(jar);
        UsesLegality.Verdict verdict = UsesLegality.of(closure, Set.of(jar), Map.of(jar, first))
                .check(Map.of(jar, scanned));
        if (verdict.hasUnreadableDrop(jar)) {
            log.accept(KEPT_AUTOMATIC_PREFIX + jar.getFileName()
                    + " (a ServiceLoader lookup it makes cannot be declared by an explicit module)");
            return Optional.empty();
        }
        Set<String> uses = verdict.legal().getOrDefault(jar, Set.of());
        return Optional.of(synthesize(jar, info.moduleName(), closure, options, uses, log).moduleInfo());
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test -Dtest=ModularizerTest`
Expected: all pass.

- [ ] **Step 5: Plugin suite, install, docs, commit**

Run: `./mvnw -ntp -pl vauban-maven-plugin clean test` → BUILD SUCCESS; `./mvnw -ntp clean install -DskipTests`.

Docs (vauban): in `docs/en/modules/ROOT/pages/internals.adoc`, in the `_VaubanComponents` row text (starts with
`| One per package.`), append: ` For a scanned dependency jar, `vidocq:generate` asks the generator for the same
providers (`VaubanGenerator.Config#dependencyProviders`) and moves them into an enriched copy of that jar, whose
descriptor declares them.` In `docs/en/modules/ROOT/pages/reference.adoc`, after the `vauban:modularize` row's text
(ends with `Full reference: <<modularize>>.`), append: ` A caller that builds its own copy, such as
`vidocq:generate`, uses `Modularizer.synthesizeOne` instead: the same descriptor for one jar, written nowhere.`

```bash
git add vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/modularize/Modularizer.java \
        vauban-maven-plugin/src/test/java/io/vidocq/vauban/maven/modularize/ModularizerTest.java \
        docs/en/modules/ROOT/pages/internals.adoc docs/en/modules/ROOT/pages/reference.adoc
git commit -s -F - <<'EOF'
feat(maven-plugin): synthesize one jar's descriptor without writing it

Modularizer#synthesizeOne returns the descriptor vauban:modularize
would write for one automatic jar, open if asked, and touches neither
the jar nor target/vauban-modularized/. A lookup no explicit module may
declare leaves it empty, with the reason logged. The docs say how
vidocq:generate uses it and the dependency providers.

Refs: Vidocq/vidocq#186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

## Part B — vidocq (`/Users/yblazart/projects/perso/vidocq/vidocq`)

Plugin module: `vidocq-runtime-maven-plugin` (below `PLUGIN`); main package
`PLUGIN/src/main/java/io/vidocq/runtime/maven`, tests `PLUGIN/src/test/java/io/vidocq/runtime/maven`.
Test command shape: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=<Class>`.

### Task 4: `EnrichedJars`

**Files:**
- Create: `PLUGIN/src/main/java/io/vidocq/runtime/maven/EnrichedJars.java`
- Test: `PLUGIN/src/test/java/io/vidocq/runtime/maven/EnrichedJarsTest.java`

**Interfaces:**
- Consumes: Task 2 `ModuleInfoRewriter.addComponentProvider(byte[], List, Set)`, `VaubanModuleReferences.of`;
  Task 3 `Modularizer.synthesizeOne`; existing `ModularizedJars.resolve`, `JpmsPatches.patchDirFor`,
  `JpmsPatches.packagesOf` (package-private, same package).
- Produces: `EnrichedJars.DIR_NAME`, `root(Path)`, `resolve(Path buildDir, Path jar)`, `isEnriched(Path, Path)`,
  `clear(Path buildDir)`, `write(Path source, byte[] moduleInfo, Path patchDir, List<String> providers,
  String coordinates, Path originalJar, Path out)`, `record Scanned(Path originalJar, String artifactId,
  String coordinates)`, `enrichAll(Path buildDir, List<Scanned>, List<String> providers, List<Path> closure,
  Consumer<String> info, Consumer<String> warn)` → `List<Path>` (originals enriched).

- [ ] **Step 1: Write the failing tests** — `EnrichedJarsTest`:

```java
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichedJarsTest {

    static final String SPI = "io.vidocq.vauban.api.VaubanComponentProvider";

    @TempDir
    Path tmp;

    static byte[] descriptor(String module) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(module), mb -> mb.requires(
                ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
    }

    static byte[] emptyClass(String fqn) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> { });
    }

    /** A class whose method names a type of io.vidocq.vauban.core, as an intercepted subclass does. */
    static byte[] callingCore(String fqn) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> clb.withMethodBody("call",
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        ClassDesc.of("io.vidocq.vauban.core.interceptor.InterceptorManager")),
                ClassFile.ACC_PUBLIC, cob -> cob.return_()));
    }

    static Path jar(Path file, Map<String, byte[]> entries, Map<String, String> sectionDigests) throws Exception {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        sectionDigests.forEach((entry, digest) -> {
            Attributes section = new Attributes();
            section.putValue("SHA-256-Digest", digest);
            manifest.getEntries().put(entry, section);
        });
        try (var out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    static Path patch(Path dir, Map<String, byte[]> classes) throws Exception {
        for (var e : classes.entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        return dir;
    }

    static ModuleDescriptor descriptorOf(Path jar) {
        return ModuleFinder.of(jar).findAll().iterator().next().descriptor();
    }

    @Test
    void writeDeclaresTheProvidersAndCarriesTheParkedClasses() throws Exception {
        Path source = jar(tmp.resolve("m2/lib-1.0.jar"), Map.of(
                "module-info.class", descriptor("org.dep"),
                "org/dep/Service.class", emptyClass("org.dep.Service"),
                "META-INF/LIB.SF", "Signature-Version: 1.0\n".getBytes(StandardCharsets.UTF_8)),
                Map.of("org/dep/Service.class", "abc="));
        Path patchDir = patch(tmp.resolve("patch"), Map.of(
                "org/dep/_VaubanComponents.class", emptyClass("org.dep._VaubanComponents"),
                "org/dep/Service$$Intercepted.class", callingCore("org.dep.Service$$Intercepted")));
        Path out = tmp.resolve("target/vidocq-enriched/lib-1.0.jar");

        EnrichedJars.write(source, null, patchDir, List.of("org.dep._VaubanComponents"), "org.dep:lib:1.0", source,
                out);

        ModuleDescriptor md = descriptorOf(out);
        assertEquals("org.dep", md.name());
        assertTrue(md.requires().stream().map(ModuleDescriptor.Requires::name).collect(Collectors.toSet())
                .containsAll(Set.of("io.vidocq.vauban.api", "io.vidocq.vauban.core")));
        assertEquals(List.of("org.dep._VaubanComponents"), md.provides().stream()
                .filter(p -> p.service().equals(SPI)).findFirst().orElseThrow().providers());
        try (JarFile copy = new JarFile(out.toFile())) {
            assertTrue(copy.getEntry("org/dep/_VaubanComponents.class") != null);
            assertTrue(copy.getEntry("org/dep/Service$$Intercepted.class") != null);
            assertNull(copy.getEntry("META-INF/LIB.SF"), "the signature no longer matches: dropped");
            assertEquals("org.dep._VaubanComponents\n", new String(copy.getInputStream(
                    copy.getEntry("META-INF/services/" + SPI)).readAllBytes(), StandardCharsets.UTF_8));
            Attributes main = copy.getManifest().getMainAttributes();
            assertEquals("org.dep:lib:1.0", main.getValue("Vidocq-Enriched-From"));
            assertTrue(main.getValue("Vidocq-Enriched-Digest").startsWith("sha256:"));
            assertTrue(copy.getManifest().getEntries().isEmpty(), "per-entry digests are dropped");
        }
    }

    @Test
    void resolvePrefersTheEnrichedCopyThenTheModularizedOne() throws Exception {
        Path build = tmp.resolve("target");
        Path original = Files.createDirectories(tmp.resolve("m2")).resolve("lib.jar");
        Files.writeString(original, "x");
        assertEquals(original, EnrichedJars.resolve(build, original));

        Path modularized = build.resolve("vauban-modularized/lib.jar");
        Files.createDirectories(modularized.getParent());
        Files.writeString(modularized, "x");
        assertEquals(modularized, EnrichedJars.resolve(build, original));

        Path enriched = EnrichedJars.root(build).resolve("lib.jar");
        Files.createDirectories(enriched.getParent());
        Files.writeString(enriched, "x");
        assertEquals(enriched, EnrichedJars.resolve(build, original));
        assertTrue(EnrichedJars.isEnriched(build, original));
    }

    @Test
    void clearRemovesEveryEnrichedCopy() throws Exception {
        Path build = tmp.resolve("target");
        Path stale = EnrichedJars.root(build).resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "x");

        EnrichedJars.clear(build);

        assertFalse(Files.exists(stale));
    }

    @Test
    void enrichAllSynthesizesAnOpenModuleForAnAutomaticJar() throws Exception {
        Path build = tmp.resolve("target");
        Path auto = jar(tmp.resolve("m2/auto-lib-1.0.jar"),
                Map.of("org/auto/A.class", emptyClass("org.auto.A")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "auto-lib"), Map.of(
                "org/auto/_VaubanComponents.class", emptyClass("org.auto._VaubanComponents")));
        List<String> warnings = new ArrayList<>();

        List<Path> enriched = EnrichedJars.enrichAll(build,
                List.of(new EnrichedJars.Scanned(auto, "auto-lib", "org.auto:auto-lib:1.0")),
                List.of("org.auto._VaubanComponents"), List.of(auto), line -> { }, warnings::add);

        assertEquals(List.of(auto), enriched);
        assertEquals(List.of(), warnings);
        ModuleDescriptor md = descriptorOf(EnrichedJars.resolve(build, auto));
        assertFalse(md.isAutomatic());
        assertTrue(md.isOpen());
        assertTrue(md.provides().stream().anyMatch(p -> p.service().equals(SPI)));
    }

    @Test
    void aJarWithOnlyAVersionedDescriptorIsLeftToPatching() throws Exception {
        Path build = tmp.resolve("target");
        Path mr = jar(tmp.resolve("m2/mr-lib-1.0.jar"), Map.of(
                "META-INF/versions/11/module-info.class", descriptor("org.mr"),
                "org/mr/A.class", emptyClass("org.mr.A")), Map.of());
        patch(JpmsPatches.patchDirFor(build, "mr-lib"), Map.of(
                "org/mr/_VaubanComponents.class", emptyClass("org.mr._VaubanComponents")));
        List<String> warnings = new ArrayList<>();

        List<Path> enriched = EnrichedJars.enrichAll(build,
                List.of(new EnrichedJars.Scanned(mr, "mr-lib", "org.mr:mr-lib:1.0")),
                List.of("org.mr._VaubanComponents"), List.of(mr), line -> { }, warnings::add);

        assertEquals(List.of(), enriched);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("mr-lib"), warnings.getFirst());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=EnrichedJarsTest`
Expected: COMPILATION ERROR — `EnrichedJars` does not exist.

- [ ] **Step 3: Create `EnrichedJars`**:

```java
package io.vidocq.runtime.maven;

import io.vidocq.vauban.maven.enhance.ModuleInfoRewriter;
import io.vidocq.vauban.maven.enhance.VaubanModuleReferences;
import io.vidocq.vauban.maven.modularize.ModularizedJars;
import io.vidocq.vauban.maven.modularize.Modularizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The enriched copy of a scanned dependency jar: the jar with the classes {@code vidocq:generate} generated for it —
 * its {@code _VaubanComponents}, client proxies and intercepted subclasses — and a descriptor that declares them, so
 * the container finds the providers through {@code ServiceLoader} like any other.
 *
 * <p>The copies live in {@code target/vidocq-enriched/} under the original file names, and every launch shape puts
 * {@link #resolve the copy} on its path in place of the original: {@code vidocq:dev}, {@code vidocq:run},
 * {@code vidocq:package} and {@code vidocq:jlink}. A jar without a descriptor is first given one, an
 * {@code open module}, by {@link Modularizer#synthesizeOne}. The copy is a modified jar: its signature files are
 * dropped and its manifest names where it comes from.
 */
public final class EnrichedJars {

    /** Directory under {@code target/} holding the enriched copies, under the original file names. */
    public static final String DIR_NAME = "vidocq-enriched";
    static final String SERVICE_FILE = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";
    static final String MODULE_INFO = "module-info.class";
    static final String ENRICHED_FROM = "Vidocq-Enriched-From";
    static final String ENRICHED_DIGEST = "Vidocq-Enriched-Digest";

    /** One scanned dependency: the original jar, its artifact id and {@code groupId:artifactId:version}. */
    public record Scanned(Path originalJar, String artifactId, String coordinates) {}

    private EnrichedJars() {}

    /** {@code target/vidocq-enriched}, existing or not. */
    public static Path root(Path buildDir) {
        return buildDir.resolve(DIR_NAME);
    }

    /** What a launch puts on its path for {@code originalJar}: the enriched copy, else the modularized, else it. */
    public static Path resolve(Path buildDir, Path originalJar) {
        Path candidate = root(buildDir).resolve(originalJar.getFileName().toString());
        return Files.isRegularFile(candidate) ? candidate : ModularizedJars.resolve(buildDir, originalJar);
    }

    /** Whether {@code originalJar} has an enriched copy, which then carries all its generated classes. */
    public static boolean isEnriched(Path buildDir, Path originalJar) {
        return Files.isRegularFile(root(buildDir).resolve(originalJar.getFileName().toString()));
    }

    /** Deletes every enriched copy, so a jar no longer scanned is never substituted by a stale one. */
    public static void clear(Path buildDir) throws IOException {
        Path root = root(buildDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * Writes the enriched copy of every scanned jar that has classes parked in {@code target/vidocq-patches/}. A jar
     * without a root descriptor gets a synthesized {@code open module}; one that cannot get it stays with
     * {@code --patch-module}, and {@code warn} says why.
     *
     * @param providers every dependency provider {@code vidocq:generate} wrote; each jar keeps those of its packages
     * @param closure   every dependency jar, for the descriptor synthesis
     * @return the original jars that now have an enriched copy
     */
    public static List<Path> enrichAll(Path buildDir, List<Scanned> scanned, List<String> providers,
                                       List<Path> closure, Consumer<String> info, Consumer<String> warn)
            throws IOException {
        List<Path> enriched = new ArrayList<>();
        for (Scanned dep : scanned) {
            Path patchDir = JpmsPatches.patchDirFor(buildDir, dep.artifactId());
            if (!Files.isDirectory(patchDir)) {
                continue;
            }
            Path source = ModularizedJars.resolve(buildDir, dep.originalJar());
            byte[] moduleInfo = null;
            if (!hasRootDescriptor(source)) {
                Optional<byte[]> synthesized = Modularizer.synthesizeOne(source, closure, true, info);
                if (synthesized.isEmpty()) {
                    warn.accept(dep.artifactId() + ": no module descriptor can be given to it here, so its"
                            + " generated classes stay attached with --patch-module and its providers are not used");
                    continue;
                }
                moduleInfo = synthesized.get();
            }
            Set<String> packages = JpmsPatches.packagesOf(source);
            List<String> own = providers.stream().filter(p -> packages.contains(packageOf(p))).toList();
            Path out = root(buildDir).resolve(dep.originalJar().getFileName().toString());
            write(source, moduleInfo, patchDir, own, dep.coordinates(), dep.originalJar(), out);
            enriched.add(dep.originalJar());
            info.accept("Enriched " + dep.originalJar().getFileName() + " with its generated code (" + own.size()
                    + " provider(s)) → target/" + DIR_NAME + "/" + out.getFileName());
        }
        return enriched;
    }

    /**
     * Writes {@code out}: {@code source} with the class files of {@code patchDir} added (an entry the jar already has
     * wins), its descriptor declaring {@code providers} and requiring what the added classes call into, a class-path
     * service file, and a manifest that names its origin. Signature files and per-entry digests are dropped.
     *
     * @param moduleInfo  the descriptor to start from when {@code source} has none at its root, else {@code null}
     * @param coordinates {@code groupId:artifactId:version} of the original, recorded in the manifest
     * @param originalJar the original jar, whose digest the manifest records
     */
    public static void write(Path source, byte[] moduleInfo, Path patchDir, List<String> providers,
                             String coordinates, Path originalJar, Path out) throws IOException {
        Map<String, byte[]> patch = readTree(patchDir);
        Set<String> requires = VaubanModuleReferences.of(patch.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".class")).map(Map.Entry::getValue).toList());

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Manifest manifest = new Manifest();
        try (JarFile in = new JarFile(source.toFile(), false)) {
            if (in.getManifest() != null) {
                manifest = new Manifest(in.getManifest());
            }
            for (JarEntry entry : Collections.list(in.entries())) {
                if (entry.isDirectory() || entry.getName().equals(JarFile.MANIFEST_NAME)
                        || isSignature(entry.getName())) {
                    continue;
                }
                try (InputStream is = in.getInputStream(entry)) {
                    entries.put(entry.getName(), is.readAllBytes());
                }
            }
        }
        byte[] base = entries.containsKey(MODULE_INFO) ? entries.get(MODULE_INFO) : moduleInfo;
        if (base == null) {
            throw new IllegalArgumentException(source.getFileName() + " has no module descriptor to enrich");
        }
        patch.forEach(entries::putIfAbsent);
        entries.put(MODULE_INFO, ModuleInfoRewriter.addComponentProvider(base, providers, requires));
        if (!providers.isEmpty()) {
            Set<String> lines = new LinkedHashSet<>();
            if (entries.containsKey(SERVICE_FILE)) {
                new String(entries.get(SERVICE_FILE), StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(lines::add);
            }
            lines.addAll(providers);
            entries.put(SERVICE_FILE, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        manifest.getEntries().clear();
        Attributes main = manifest.getMainAttributes();
        main.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue(ENRICHED_FROM, coordinates);
        main.putValue(ENRICHED_DIGEST, "sha256:" + sha256(originalJar));

        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), out.getFileName().toString(), ".tmp");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
            for (var e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@code META-INF/*.SF}, {@code *.RSA}, {@code *.DSA}, {@code *.EC}, {@code SIG-*}: a signature. */
    static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    private static boolean hasRootDescriptor(Path jar) throws IOException {
        try (JarFile in = new JarFile(jar.toFile(), false)) {
            return in.getEntry(MODULE_INFO) != null;
        }
    }

    private static Map<String, byte[]> readTree(Path dir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(dir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Note on the multi-release case: `hasRootDescriptor` is `false`, `Modularizer.synthesizeOne` sees an explicit module
(the versioned descriptor) and returns empty, so the jar goes to the warning branch — Review Focus 1.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=EnrichedJarsTest`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/EnrichedJars.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/EnrichedJarsTest.java
git commit -s -F - <<'EOF'
feat(maven-plugin): write an enriched copy of a scanned dependency jar

EnrichedJars copies a scanned jar with the classes generated for it,
rewrites its descriptor to provide the _VaubanComponents and require
what the classes call into, adds a class-path service file, drops the
signature and stamps the origin in the manifest. A jar without a
descriptor first gets an open module from the modularizer; resolve()
prefers the enriched copy, then the modularized one.

Refs: #186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 5: `vidocq:generate` enriches, and every launch shape uses the copy

**Files:**
- Modify: `PLUGIN/src/main/java/io/vidocq/runtime/maven/VidocqGenerateMojo.java`
- Modify: `PLUGIN/src/main/java/io/vidocq/runtime/maven/ApplicationLaunch.java`
- Modify: `PLUGIN/src/main/java/io/vidocq/runtime/maven/VidocqPackageMojo.java`
- Modify: `PLUGIN/src/main/java/io/vidocq/runtime/maven/VidocqJlinkMojo.java`
- Test: `PLUGIN/src/test/java/io/vidocq/runtime/maven/ApplicationLaunchTest.java`

**Interfaces:**
- Consumes: Task 1 `Config(…, true)`, `GenerationResult.generatedProviders()`; Task 4 `EnrichedJars`.

- [ ] **Step 1: Write the failing test** — add to `ApplicationLaunchTest` (imports `java.nio.file.Files`,
`java.util.ArrayList`):

```java
    @Test
    void anEnrichedCopyReplacesTheJarAndNeedsNoPatch(@TempDir Path dir) throws Exception {
        Path lib = jar(dir, "dep-lib.jar", null);
        MavenProject project = project();
        project.setArtifacts(Set.of(artifact("dep-lib", lib)));
        Path build = dir.resolve("target");
        Files.createDirectories(JpmsPatches.patchDirFor(build, "dep-lib").resolve("a"));
        assertEquals("--patch-module", ApplicationLaunch.patchModuleArgs(project, build).getFirst(),
                "without a copy, the parked classes are patched in");

        Path enriched = EnrichedJars.root(build).resolve("dep-lib.jar");
        Files.createDirectories(enriched.getParent());
        Files.copy(lib, enriched);
        List<Path> replaced = new ArrayList<>();

        assertEquals(List.of(enriched),
                ApplicationLaunch.modulePath(project, build, build.resolve("classes"), true, replaced::add));
        assertEquals(List.of(lib), replaced);
        assertEquals(List.of(), ApplicationLaunch.patchModuleArgs(project, build), "its classes are in the copy");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=ApplicationLaunchTest`
Expected: FAIL — `modulePath` returns the original jar.

- [ ] **Step 3: `ApplicationLaunch`** — in `modulePath`, replace `Path resolved = ModularizedJars.resolve(buildDir,
jar);` with `Path resolved = EnrichedJars.resolve(buildDir, jar);`; in both Javadocs, the `@param modularized` text
becomes `called with each dependency jar that a copy replaces: enriched by {@code vidocq:generate} or modularized by
{@code vauban:modularize}`. In `patchModuleArgs`, replace the loop body with:

```java
            if (artifact.getFile() != null && !EnrichedJars.isEnriched(buildDir, artifact.getFile().toPath())) {
                jarsByArtifactId.put(artifact.getArtifactId(),
                        ModularizedJars.resolve(buildDir, artifact.getFile().toPath()));
            }
```

and add to its Javadoc: `A jar with an enriched copy is left out: the copy carries the classes.`

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=ApplicationLaunchTest`
Expected: all pass.

- [ ] **Step 5: `package` and `jlink`** — in `VidocqPackageMojo`, replace

```java
                    Path src = ModularizedJars.resolve(buildDir.toPath(), original);
                    if (!src.equals(original)) {
                        getLog().info("Packaging modularized copy of " + original.getFileName()
                                + " (vauban:modularize generated its module descriptor)");
                    }
                    Path patchDir = JpmsPatches.patchDirFor(buildDir.toPath(), artifact.getArtifactId());
                    if (Files.isDirectory(patchDir)) {
```

with

```java
                    Path src = EnrichedJars.resolve(buildDir.toPath(), original);
                    boolean enriched = EnrichedJars.isEnriched(buildDir.toPath(), original);
                    if (enriched) {
                        getLog().info("Packaging enriched copy of " + original.getFileName()
                                + " (vidocq:generate added its generated code and declared it)");
                    } else if (!src.equals(original)) {
                        getLog().info("Packaging modularized copy of " + original.getFileName()
                                + " (vauban:modularize generated its module descriptor)");
                    }
                    Path patchDir = JpmsPatches.patchDirFor(buildDir.toPath(), artifact.getArtifactId());
                    if (!enriched && Files.isDirectory(patchDir)) {
```

In `VidocqJlinkMojo`, replace `Path modularized = ModularizedJars.resolve(buildDir.toPath(), f.toPath());` with
`Path modularized = EnrichedJars.resolve(buildDir.toPath(), f.toPath());`, and the condition
`if (Files.isDirectory(patchDir)) {` that follows with
`if (!EnrichedJars.isEnriched(buildDir.toPath(), f.toPath()) && Files.isDirectory(patchDir)) {`.
Remove an `import …ModularizedJars;` only if the compiler reports it unused (`VidocqJlinkMojo` still uses `root`).

- [ ] **Step 6: `VidocqGenerateMojo`** — as the first statement of `execute()`, before the `getLog().info(…)` and
the classes-directory check (both return early), add:

```java
        try {
            EnrichedJars.clear(buildDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot clear target/" + EnrichedJars.DIR_NAME, e);
        }
```

Change the config to
`new VaubanGenerator.Config(vidocqDeps, aptProcessed ? null : classesDir, classesDir, classLoader, true)`. After
`generated.addAll(result.generatedInterceptors());` add `generated.addAll(result.generatedProviders());`. After the
`relocated` logging loop, add:

```java
            // One enriched copy per scanned jar: its generated classes inside, its descriptor declaring the
            // providers, substituted for the original by dev, run, package and jlink.
            List<EnrichedJars.Scanned> scanned = new ArrayList<>();
            List<Path> closure = new ArrayList<>();
            for (var artifact : project.getArtifacts()) {
                if (artifact.getFile() == null) {
                    continue;
                }
                Path jar = artifact.getFile().toPath();
                closure.add(jar);
                if (scannedByArtifactId.containsKey(jar) && Files.isRegularFile(jar)) {
                    scanned.add(new EnrichedJars.Scanned(jar, artifact.getArtifactId(),
                            artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion()));
                }
            }
            EnrichedJars.enrichAll(buildDirectory.toPath(), scanned, result.generatedProviders(), closure,
                    getLog()::info, getLog()::warn);
```

- [ ] **Step 7: Plugin suite**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/ApplicationLaunchTest.java
git commit -s -F - <<'EOF'
feat(maven-plugin): generate providers for scanned jars and launch their enriched copy

vidocq:generate now asks for the scanned jars' _VaubanComponents, parks
them with the proxies, and writes each scanned jar's enriched copy,
after clearing stale ones. dev, run, package and jlink put the copy on
the path in place of the original and no longer patch it in.

Refs: #186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 6: Which jars are scanned — `ScanSelection`

**Files:**
- Create: `PLUGIN/src/main/java/io/vidocq/runtime/maven/ScanSelection.java`
- Modify: `PLUGIN/src/main/java/io/vidocq/runtime/maven/VidocqGenerateMojo.java`
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension/pom.xml`
- Test: `PLUGIN/src/test/java/io/vidocq/runtime/maven/ScanSelectionTest.java`

**Interfaces:**
- Produces: `ScanSelection.MANIFEST_ATTRIBUTE`; `enum Source { EXTENSION, APPLICATION, AUTOMATIC }` with
  `label()`; `record Dependency(String groupId, String artifactId, String version, Path jar)` with `coordinates()`;
  `record JarFacts(boolean beansXml, String discoveryMode, boolean processed, boolean signed, boolean automatic,
  List<String> scanPatterns)`; `record Decision(Dependency dependency, JarFacts facts, Source source,
  String excludedBecause)` with `selected()`; `static JarFacts inspect(Path jarOrDir)`;
  `static List<Decision> decide(List<Dependency>, List<String> scanDependencies, List<String> scanExcludes,
  boolean autoScan)`; `static List<Dependency> dependenciesOf(Collection<Artifact>)`;
  `static boolean matches(String pattern, String groupId, String artifactId)`.

- [ ] **Step 1: Write the failing tests** — `ScanSelectionTest`:

```java
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanSelectionTest {

    @TempDir
    Path tmp;

    Path jar(String name, Map<String, String> entries, String scanPatterns) throws Exception {
        Path file = tmp.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (scanPatterns != null) {
            manifest.getMainAttributes().putValue(ScanSelection.MANIFEST_ATTRIBUTE, scanPatterns);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    ScanSelection.Dependency dep(String g, String a, Path jar) {
        return new ScanSelection.Dependency(g, a, "1.0", jar);
    }

    static Map<String, ScanSelection.Decision> byArtifact(List<ScanSelection.Decision> decisions) {
        Map<String, ScanSelection.Decision> map = new LinkedHashMap<>();
        decisions.forEach(d -> map.put(d.dependency().artifactId(), d));
        return map;
    }

    @Test
    void aBeanArchiveIsScannedAutomaticallyUnlessItsDiscoveryModeIsNone() throws Exception {
        var annotated = dep("org.a", "annotated", jar("a.jar", Map.of("META-INF/beans.xml", ""), null));
        var none = dep("org.n", "none", jar("n.jar",
                Map.of("META-INF/beans.xml", "<beans bean-discovery-mode=\"none\"/>"), null));
        var plain = dep("org.p", "plain", jar("p.jar", Map.of("org/p/P.class", "x"), null));

        var d = byArtifact(ScanSelection.decide(List.of(annotated, none, plain), List.of(), List.of(), true));

        assertTrue(d.get("annotated").selected());
        assertEquals(ScanSelection.Source.AUTOMATIC, d.get("annotated").source());
        assertEquals("annotated", d.get("annotated").facts().discoveryMode());
        assertFalse(d.get("none").selected());
        assertFalse(d.get("plain").selected());
        assertFalse(byArtifact(ScanSelection.decide(List.of(annotated), List.of(), List.of(), false))
                .get("annotated").selected(), "autoScan=false turns the detection off");
    }

    @Test
    void anExtensionManifestSelectsTheJarsItNames() throws Exception {
        var extension = dep("io.vidocq", "ext", jar("ext.jar", Map.of(), "org.lib:*"));
        var lib = dep("org.lib", "lib", jar("lib.jar", Map.of("org/lib/L.class", "x"), null));

        var d = byArtifact(ScanSelection.decide(List.of(extension, lib), List.of(), List.of(), false));

        assertTrue(d.get("lib").selected());
        assertEquals(ScanSelection.Source.EXTENSION, d.get("lib").source());
        assertFalse(d.get("ext").selected());
    }

    @Test
    void processedJarsAndExcludesAreLeftOutWithTheirReason() throws Exception {
        var processed = dep("io.vidocq", "brick", jar("b.jar", Map.of(
                "META-INF/beans.xml", "", "META-INF/vauban-bce-processed", ""), null));
        var excluded = dep("org.x", "excluded", jar("x.jar", Map.of("META-INF/beans.xml", ""), null));

        var d = byArtifact(ScanSelection.decide(List.of(processed, excluded), List.of(), List.of("org.x:*"), true));

        assertFalse(d.get("brick").selected());
        assertTrue(d.get("brick").excludedBecause().contains("already carries generated code"));
        assertFalse(d.get("excluded").selected());
        assertTrue(d.get("excluded").excludedBecause().contains("scanExcludes"));
    }

    @Test
    void aSignedJarIsSkippedUnlessTheApplicationNamesIt() throws Exception {
        var signed = dep("org.s", "signed", jar("s.jar", Map.of("META-INF/beans.xml", "",
                "META-INF/SIGNER.SF", "Signature-Version: 1.0\n"), null));

        var auto = byArtifact(ScanSelection.decide(List.of(signed), List.of(), List.of(), true)).get("signed");
        var named = byArtifact(ScanSelection.decide(List.of(signed), List.of("org.s:signed"), List.of(), true))
                .get("signed");

        assertFalse(auto.selected());
        assertTrue(auto.excludedBecause().contains("signed"));
        assertTrue(named.selected());
        assertEquals(ScanSelection.Source.APPLICATION, named.source());
    }

    @Test
    void aReactorDirectoryIsInspectedLikeAJar() throws Exception {
        Path dir = tmp.resolve("reactor/target/classes");
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("META-INF/beans.xml"), "");

        var facts = ScanSelection.inspect(dir);

        assertTrue(facts.beansXml());
        assertTrue(facts.automatic());
        assertFalse(facts.signed());
        assertTrue(ScanSelection.decide(List.of(dep("org.r", "reactor", dir)), List.of(), List.of(), true)
                .getFirst().selected());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=ScanSelectionTest`
Expected: COMPILATION ERROR — `ScanSelection` does not exist.

- [ ] **Step 3: Create `ScanSelection`**:

```java
package io.vidocq.runtime.maven;

import org.apache.maven.artifact.Artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which dependency jars {@code vidocq:generate} scans for CDI beans to generate code for, and why each other is left
 * out. Three sources select a jar: an extension that ships it, naming it in its manifest
 * ({@value #MANIFEST_ATTRIBUTE}); the application, in {@code <scanDependencies>}; and, unless turned off, any CDI
 * bean archive — a {@code META-INF/beans.xml} whose discovery mode is not {@code none}. A selected jar is left out
 * when it already carries generated code, when {@code <scanExcludes>} names it, or when it is signed and the
 * application did not name it: enriching it would break its signature.
 */
public final class ScanSelection {

    /** Manifest attribute by which a jar asks for others to be scanned: comma-separated patterns. */
    public static final String MANIFEST_ATTRIBUTE = "Vidocq-Scan-Dependencies";

    private static final Pattern DISCOVERY_MODE = Pattern.compile("bean-discovery-mode\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final List<String> PROCESSED_MARKERS = List.of("META-INF/vauban-bce-processed",
            "META-INF/vauban-beans.list", "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");

    /** What selected a jar. */
    public enum Source {
        EXTENSION("declared by an extension"),
        APPLICATION("named in scanDependencies"),
        AUTOMATIC("a CDI bean archive");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A dependency of the project: a jar, or a reactor module's classes directory. */
    public record Dependency(String groupId, String artifactId, String version, Path jar) {
        public String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /**
     * What a jar's content says.
     *
     * @param discoveryMode the {@code bean-discovery-mode} of its {@code beans.xml}, {@code annotated} when absent
     * @param processed     it already carries Vauban-generated code (APT marker, bean list or provider service)
     * @param automatic     it has no module descriptor at its root
     * @param scanPatterns  the patterns its manifest asks to scan
     */
    public record JarFacts(boolean beansXml, String discoveryMode, boolean processed, boolean signed,
                           boolean automatic, List<String> scanPatterns) {}

    /**
     * What the selection decided for one dependency.
     *
     * @param source          what selected it, or {@code null} when nothing did
     * @param excludedBecause why it is left out although selected, or {@code null}
     */
    public record Decision(Dependency dependency, JarFacts facts, Source source, String excludedBecause) {
        public boolean selected() {
            return source != null && excludedBecause == null;
        }
    }

    private ScanSelection() {}

    /** The project's dependencies that have a file. */
    public static List<Dependency> dependenciesOf(Collection<Artifact> artifacts) {
        List<Dependency> deps = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (a.getFile() != null) {
                deps.add(new Dependency(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getFile().toPath()));
            }
        }
        return deps;
    }

    /** One decision per dependency, in their order. */
    public static List<Decision> decide(List<Dependency> dependencies, List<String> scanDependencies,
                                        List<String> scanExcludes, boolean autoScan) throws IOException {
        List<JarFacts> facts = new ArrayList<>();
        Set<String> extensionPatterns = new LinkedHashSet<>();
        for (Dependency dep : dependencies) {
            JarFacts f = inspect(dep.jar());
            facts.add(f);
            extensionPatterns.addAll(f.scanPatterns());
        }
        List<Decision> decisions = new ArrayList<>();
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dep = dependencies.get(i);
            JarFacts f = facts.get(i);
            Source source = anyMatch(scanDependencies, dep) ? Source.APPLICATION
                    : anyMatch(extensionPatterns, dep) ? Source.EXTENSION
                    : autoScan && f.beansXml() && !"none".equals(f.discoveryMode()) ? Source.AUTOMATIC
                    : null;
            String excluded = null;
            if (source != null) {
                if (f.processed()) {
                    excluded = "already carries generated code (Vauban APT or plugin)";
                } else if (anyMatch(scanExcludes, dep)) {
                    excluded = "listed in scanExcludes";
                } else if (f.signed() && source != Source.APPLICATION) {
                    excluded = "signed: enriching it would break its signature — name it in scanDependencies to"
                            + " enrich it anyway";
                }
            }
            decisions.add(new Decision(dep, f, source, excluded));
        }
        return decisions;
    }

    /** What the jar, or reactor classes directory, says about itself. */
    public static JarFacts inspect(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            String beansXml = read(jarOrDir.resolve("META-INF/beans.xml"));
            boolean processed = PROCESSED_MARKERS.stream().anyMatch(m -> Files.exists(jarOrDir.resolve(m)));
            Path manifest = jarOrDir.resolve(JarFile.MANIFEST_NAME);
            List<String> patterns = List.of();
            if (Files.isRegularFile(manifest)) {
                try (var in = Files.newInputStream(manifest)) {
                    patterns = patterns(new Manifest(in));
                }
            }
            return new JarFacts(beansXml != null, discoveryMode(beansXml), processed, false,
                    !Files.isRegularFile(jarOrDir.resolve(EnrichedJars.MODULE_INFO)), patterns);
        }
        try (JarFile jar = new JarFile(jarOrDir.toFile(), false)) {
            JarEntry beans = jar.getJarEntry("META-INF/beans.xml");
            String beansXml = beans == null ? null
                    : new String(jar.getInputStream(beans).readAllBytes(), StandardCharsets.UTF_8);
            boolean processed = PROCESSED_MARKERS.stream().anyMatch(m -> jar.getEntry(m) != null);
            boolean signed = Collections.list(jar.entries()).stream()
                    .anyMatch(e -> EnrichedJars.isSignature(e.getName()) && !e.getName().toUpperCase(Locale.ROOT)
                            .startsWith("META-INF/SIG-"));
            return new JarFacts(beansXml != null, discoveryMode(beansXml), processed, signed,
                    jar.getEntry(EnrichedJars.MODULE_INFO) == null,
                    jar.getManifest() == null ? List.of() : patterns(jar.getManifest()));
        }
    }

    /** {@code groupId:artifactId}, either side optional or ending in {@code *}. */
    public static boolean matches(String pattern, String groupId, String artifactId) {
        int colon = pattern.indexOf(':');
        String g = colon >= 0 ? pattern.substring(0, colon) : pattern;
        String a = colon >= 0 ? pattern.substring(colon + 1) : "*";
        return token(g.strip(), groupId) && token(a.isBlank() ? "*" : a.strip(), artifactId);
    }

    private static boolean anyMatch(Collection<String> patterns, Dependency dep) {
        return patterns.stream().anyMatch(p -> matches(p, dep.groupId(), dep.artifactId()));
    }

    private static boolean token(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        return pattern.endsWith("*") ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(value);
    }

    private static String discoveryMode(String beansXml) {
        if (beansXml == null) {
            return null;
        }
        Matcher m = DISCOVERY_MODE.matcher(beansXml);
        return m.find() ? m.group(1).strip() : "annotated";
    }

    private static List<String> patterns(Manifest manifest) {
        String value = manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
        return value == null ? List.of() : Stream.of(value.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }

    private static String read(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : null;
    }
}
```

(`META-INF/SIG-*` files are signature blocks too, but a jar is only "signed" with a `.SF`: the stream excludes the
`SIG-` prefix to keep the `.SF`/`.RSA`/`.DSA`/`.EC` set, which every signed jar has.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=ScanSelectionTest`
Expected: 5 tests pass.

- [ ] **Step 5: Wire it into `VidocqGenerateMojo`** — add parameters after `scanDependencies`:

```java
    /**
     * Dependencies never scanned, whatever selects them: an extension, {@code scanDependencies} or the bean-archive
     * detection. Same syntax as {@code scanDependencies}.
     */
    @Parameter
    private List<String> scanExcludes;

    /**
     * Scans every dependency that is a CDI bean archive — a {@code META-INF/beans.xml} whose discovery mode is not
     * {@code none} — besides what an extension or {@code scanDependencies} names.
     */
    @Parameter(property = "vidocq.generate.autoScan", defaultValue = "true")
    private boolean autoScan;
```

Update the `scanDependencies` Javadoc's last paragraph to: `The jars that already carry generated code are always
excluded. Extensions add their own jars through their manifest, and bean archives are detected; see
ScanSelection.` Replace `collectScannedDependencies`, `isAlreadyProcessed` and the private `Pattern` record with:

```java
    /** Scanned dependency jar → artifactId, in dependency order. */
    private Map<Path, String> collectScannedDependencies() throws IOException {
        Map<Path, String> deps = new LinkedHashMap<>();
        for (var d : ScanSelection.decide(ScanSelection.dependenciesOf(project.getArtifacts()),
                orEmpty(scanDependencies), orEmpty(scanExcludes), autoScan)) {
            if (d.selected()) {
                deps.put(d.dependency().jar(), d.dependency().artifactId());
                getLog().info("Scanning " + d.dependency().coordinates() + ": " + d.source().label());
            } else if (d.excludedBecause() != null) {
                if (d.facts().processed()) {
                    getLog().debug("Skipping " + d.dependency().coordinates() + ": " + d.excludedBecause());
                } else {
                    getLog().warn("Not scanning " + d.dependency().coordinates() + ": " + d.excludedBecause());
                }
            }
        }
        return deps;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
```

Remove the now-unused `import java.util.jar.JarFile;` if the compiler flags it.

- [ ] **Step 6: The langchain4j-cdi extension declares its jars** — in the extension `pom.xml`, inside `<plugins>`
next to the `vidocq-runtime-maven-plugin` block, add:

```xml
            <!--
                Asks every application's vidocq:generate to scan the langchain4j-cdi jars this extension brings:
                their beans get generated code instead of reflection (Vidocq/vidocq#186).
            -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Vidocq-Scan-Dependencies>dev.langchain4j.cdi.mcp:*</Vidocq-Scan-Dependencies>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
```

and in the comment above the `vidocq-runtime-maven-plugin` block, replace `the server's proxies are generated at run
time.` with `each application's vidocq:generate generates the server's code, through the manifest entry below.`

- [ ] **Step 7: Plugin suite and the extension build**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test` → BUILD SUCCESS; then
`./mvnw -ntp -pl vidocq-runtime-maven-plugin,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension clean install -DskipTests`
and check `unzip -p` of the extension jar's `META-INF/MANIFEST.MF` holds `Vidocq-Scan-Dependencies: dev.langchain4j.cdi.mcp:*`.

- [ ] **Step 8: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/ScanSelectionTest.java \
        vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension/pom.xml
git commit -s -F - <<'EOF'
feat(maven-plugin): scan what extensions declare and every CDI bean archive

vidocq:generate now scans the jars an extension names in its manifest
(Vidocq-Scan-Dependencies), those of scanDependencies, and every bean
archive whose discovery mode is not none (autoScan, on by default). It
leaves out jars that already carry generated code, those of
scanExcludes, and signed jars the application did not name. The
langchain4j-cdi MCP extension declares its jars.

Refs: #186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 7: `vidocq:analyze-deps`

**Files:**
- Create: `PLUGIN/src/main/java/io/vidocq/runtime/maven/AnalyzeReport.java`
- Create: `PLUGIN/src/main/java/io/vidocq/runtime/maven/VidocqAnalyzeDepsMojo.java`
- Test: `PLUGIN/src/test/java/io/vidocq/runtime/maven/AnalyzeReportTest.java`

**Interfaces:**
- Consumes: Task 6 `ScanSelection.decide`, `Decision`, `JarFacts`, `Source`, `dependenciesOf`.
- Produces: `static String AnalyzeReport.render(List<ScanSelection.Decision>)`.

- [ ] **Step 1: Write the failing test** — `AnalyzeReportTest`:

```java
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalyzeReportTest {

    static ScanSelection.Decision decision(String artifactId, ScanSelection.JarFacts facts,
                                           ScanSelection.Source source, String excluded) {
        return new ScanSelection.Decision(new ScanSelection.Dependency("org.x", artifactId, "1.0",
                Path.of(artifactId + ".jar")), facts, source, excluded);
    }

    @Test
    void reportsEachConcernedJarAndTheBlockThatMakesTheDetectionExplicit() {
        var explicit = new ScanSelection.JarFacts(true, "annotated", false, false, false, List.of());
        var automatic = new ScanSelection.JarFacts(true, "all", false, false, true, List.of());
        var processed = new ScanSelection.JarFacts(true, "annotated", true, false, false, List.of());
        var plain = new ScanSelection.JarFacts(false, null, false, false, true, List.of());

        String report = AnalyzeReport.render(List.of(
                decision("server", explicit, ScanSelection.Source.EXTENSION, null),
                decision("auto", automatic, ScanSelection.Source.AUTOMATIC, null),
                decision("brick", processed, ScanSelection.Source.AUTOMATIC, "already carries generated code"),
                decision("slf4j", plain, null, null)));

        assertEquals("""
                Dependencies vidocq:generate scans for CDI beans:
                  org.x:server:1.0 — bean archive (annotated), explicit module
                    scanned: declared by an extension → enriched copy
                  org.x:auto:1.0 — bean archive (all), automatic module
                    scanned: a CDI bean archive → open module synthesized, then enriched copy
                  org.x:brick:1.0 — bean archive (annotated), explicit module
                    not scanned: already carries generated code
                1 other dependency is not a bean archive and is not named.

                To make the detected bean archives explicit:
                <scanDependencies>
                    <scanDependency>org.x:auto</scanDependency>
                </scanDependencies>
                """, report);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=AnalyzeReportTest`
Expected: COMPILATION ERROR — `AnalyzeReport` does not exist.

- [ ] **Step 3: Create `AnalyzeReport`**:

```java
package io.vidocq.runtime.maven;

import java.util.List;

/** The text {@code vidocq:analyze-deps} prints: per concerned dependency, what it is and what generate does. */
final class AnalyzeReport {

    private AnalyzeReport() {}

    static String render(List<ScanSelection.Decision> decisions) {
        StringBuilder out = new StringBuilder("Dependencies vidocq:generate scans for CDI beans:\n");
        int others = 0;
        StringBuilder block = new StringBuilder();
        for (ScanSelection.Decision d : decisions) {
            ScanSelection.JarFacts f = d.facts();
            if (d.source() == null && !f.beansXml()) {
                others++;
                continue;
            }
            out.append("  ").append(d.dependency().coordinates()).append(" — ")
                    .append(f.beansXml() ? "bean archive (" + f.discoveryMode() + ")" : "not a bean archive")
                    .append(", ").append(f.automatic() ? "automatic module" : "explicit module")
                    .append(f.signed() ? ", signed" : "").append('\n');
            out.append("    ");
            if (d.selected()) {
                out.append("scanned: ").append(d.source().label()).append(" → ")
                        .append(f.automatic() ? "open module synthesized, then enriched copy" : "enriched copy");
                if (d.source() == ScanSelection.Source.AUTOMATIC) {
                    block.append("    <scanDependency>").append(d.dependency().groupId()).append(':')
                            .append(d.dependency().artifactId()).append("</scanDependency>\n");
                }
            } else if (d.excludedBecause() != null) {
                out.append("not scanned: ").append(d.excludedBecause());
            } else {
                out.append("not scanned: discovery mode none, or the bean-archive detection is off");
            }
            out.append('\n');
        }
        if (others == 1) {
            out.append("1 other dependency is not a bean archive and is not named.\n");
        } else if (others > 1) {
            out.append(others).append(" other dependencies are not bean archives and are not named.\n");
        }
        if (!block.isEmpty()) {
            out.append("\nTo make the detected bean archives explicit:\n<scanDependencies>\n").append(block)
                    .append("</scanDependencies>\n");
        }
        return out.toString();
    }
}
```

- [ ] **Step 4: Create `VidocqAnalyzeDepsMojo`**:

```java
package io.vidocq.runtime.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.util.List;

/**
 * Prints which dependencies {@code vidocq:generate} scans for CDI beans, what selects or excludes each, and what it
 * does with them, then the {@code <scanDependencies>} block that makes the bean-archive detection explicit. Reads the
 * same {@code scanDependencies}, {@code scanExcludes} and {@code autoScan} as {@code generate}: configure them at
 * plugin level so both goals see them. Changes nothing.
 */
@Mojo(name = "analyze-deps", requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class VidocqAnalyzeDepsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter
    private List<String> scanDependencies;

    @Parameter
    private List<String> scanExcludes;

    @Parameter(property = "vidocq.generate.autoScan", defaultValue = "true")
    private boolean autoScan;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var decisions = ScanSelection.decide(ScanSelection.dependenciesOf(project.getArtifacts()),
                    scanDependencies == null ? List.of() : scanDependencies,
                    scanExcludes == null ? List.of() : scanExcludes, autoScan);
            AnalyzeReport.render(decisions).lines().forEach(getLog()::info);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the dependency jars", e);
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass, then the plugin suite**

Run: `./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test -Dtest=AnalyzeReportTest` → PASS;
`./mvnw -ntp -pl vidocq-runtime-maven-plugin clean test` → BUILD SUCCESS (the plugin descriptor test, if any,
accepts the new goal).

- [ ] **Step 6: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/AnalyzeReport.java \
        vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqAnalyzeDepsMojo.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/AnalyzeReportTest.java
git commit -s -F - <<'EOF'
feat(maven-plugin): vidocq:analyze-deps explains which jars generate scans

The goal prints, per bean archive or named dependency, what it is,
what selects or excludes it and what vidocq:generate does with it, then
the scanDependencies block that makes the detection explicit. It
changes nothing.

Refs: #186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 8: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc`
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc`
- Modify: `docs/en/modules/ROOT/pages/dev-console.adoc`
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`

- [ ] **Step 1: Goals table** — in `vidocq-runtime-maven-plugin.adoc`, replace the `vidocq:generate` row text with:
`Generates the CDI bean index (`META-INF/vauban-beans.list`) and the code of the dependency jars it scans — their
`_VaubanComponents`, client proxies and intercepted subclasses — into an enriched copy of each jar that every launch
uses. See <<generate,the `vidocq:generate` goal>> [.tag-new]#NEW#. Extension discovery itself needs no index — it is
plain `ServiceLoader` over `provides` directives.` Add after the `vidocq:checkpom` row:

```
| `vidocq:analyze-deps` [.tag-new]#NEW#
| Explains which dependencies `vidocq:generate` scans, why, and what it does with each; prints the `<scanDependencies>` block that makes the detection explicit. See <<analyze-deps>>.
```

- [ ] **Step 2: Two sections** — insert before `== `modularize` moved to the Vauban plugin`:

```
[#generate]
== `vidocq:generate` goal [.tag-new]#NEW#

Bound to `process-classes`. Besides the application's own bean index, it generates code for the dependency jars it scans, so that their beans run generated code instead of reflection: per package a `_VaubanComponents` that creates the beans, injects their fields and calls their methods, a client proxy per normal-scoped bean, an intercepted subclass per intercepted one — the same code the Vauban annotation processor writes for a project, made with the Class-File API.

=== Which jars it scans

[cols="1,3"]
|===
| Source | How

| An extension
| A jar of the project declares `Vidocq-Scan-Dependencies: <groupId:artifactId patterns>` in its manifest. The langchain4j-cdi MCP extension declares `dev.langchain4j.cdi.mcp:*`, so the server's beans are generated in every application that uses it.

| The application
| `<scanDependencies>`, `groupId:artifactId` patterns, `*` accepted at the end of either side.

| A CDI bean archive
| Any jar with a `META-INF/beans.xml` whose `bean-discovery-mode` is not `none`. On by default; `-Dvidocq.generate.autoScan=false` or `<autoScan>false</autoScan>` turns it off.
|===

A selected jar is left out when it already carries generated code (a Vidocq brick compiled with the Vauban processor), when `<scanExcludes>` names it, or when it is signed and `<scanDependencies>` does not name it: the build log says which, and why.

=== The enriched copy

Each scanned jar gets a copy in `target/vidocq-enriched/`, under its original file name: the jar with its generated classes, and a module descriptor that `provides io.vidocq.vauban.api.VaubanComponentProvider` with them and `requires` the Vauban modules they call. A jar without a descriptor is first given one, an `open module` synthesized as `vauban:modularize` does. `vidocq:dev`, `vidocq:run`, `vidocq:package` and `vidocq:jlink` put the copy on the module path in place of the original.

The copy is a modified third-party jar: its signature files are dropped, and its manifest records `Vidocq-Enriched-From` (the coordinates) and `Vidocq-Enriched-Digest` (`sha256:` of the original). A packaged application ships it; declare it to your SBOM tooling, or turn the detection off. A jar for which no descriptor can be synthesized — a `ServiceLoader` lookup no explicit module may declare — keeps its original and gets its generated classes through `--patch-module`, and the build says so. An IDE launch without a Maven step uses the original jars, and the beans fall back to reflection.

[cols="1,1,3"]
|===
| Parameter | Default | Role

| `scanDependencies` | none | Jars to scan, whatever else selects them; also enriches a signed jar.
| `scanExcludes` | none | Jars never scanned.
| `autoScan` (`vidocq.generate.autoScan`) | `true` | Scan every CDI bean archive.
|===

The dev console's CDI panel shows the result: a scanned jar's beans read `Class-File` or `partial` in its `codegen` column, no longer `reflection` (xref:dev-console.adoc#cdi-panel[the CDI panel]).

[#analyze-deps]
== `vidocq:analyze-deps` goal [.tag-new]#NEW#

`mvn vidocq:analyze-deps` prints, for each bean archive and each named dependency, what it is — its discovery mode, an explicit or automatic module, signed or not — what selects or excludes it, and what `vidocq:generate` does with it: an enriched copy, an open module synthesized first, or nothing and why. It ends with the `<scanDependencies>` block that makes the detected bean archives explicit, to paste in the pom. It reads the same `scanDependencies`, `scanExcludes` and `autoScan` as `vidocq:generate`: configure them at plugin level so both goals see them. It changes nothing.
```

- [ ] **Step 3: The extension page** — in `vidocq-runtime-extensions.adoc`, in the `langchain4j-cdi-mcp` section, after
the paragraph about the boot layer (starts with `The extension's module, `), add:

```
The extension's manifest asks the application's `vidocq:generate` to scan the langchain4j-cdi jars it brings (`Vidocq-Scan-Dependencies: dev.langchain4j.cdi.mcp:*`) [.tag-new]#NEW#: the server's beans run generated code — created, injected and called by a `_VaubanComponents` in their own packages, behind build-time client proxies — instead of reflection, from an enriched copy of the jars that every launch uses. See xref:modules/vidocq-runtime-maven-plugin.adoc#generate[the `vidocq:generate` goal].
```

- [ ] **Step 4: The dev console page** — in `dev-console.adoc`, after the bullet that begins with `* `by reflection`:`,
add a paragraph:

```
A third-party jar's beans read `reflection` until `vidocq:generate` scans the jar; a CDI bean archive is scanned by default, and `mvn vidocq:analyze-deps` tells which jars are and why (xref:modules/vidocq-runtime-maven-plugin.adoc#generate[the `vidocq:generate` goal]).
```

- [ ] **Step 5: What's new** — in `whats-new.adoc`, add as the last bullet of `== Runtime`:

```
* **Generated code for third-party CDI jars** [.tag-new]#NEW# — `vidocq:generate` now scans, besides `<scanDependencies>`, the jars an extension declares in its manifest and every CDI bean archive (`vidocq.generate.autoScan`, on by default), and gives each scanned jar the code the Vauban processor gives a project: `_VaubanComponents`, client proxies, intercepted subclasses. They ship in an enriched copy of the jar — given an `open module` first when it has no descriptor — that `dev`, `run`, `package` and `jlink` use in place of the original. The langchain4j-cdi MCP server's 28 beans move from reflection to generated code. `mvn vidocq:analyze-deps` explains which jars are scanned and why (Vidocq/vidocq#186). xref:modules/vidocq-runtime-maven-plugin.adoc#generate[The `vidocq:generate` goal].
```

- [ ] **Step 6: Check the rendering** — `asciidoctor -o - docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc | grep -c 'id="generate"'`
→ `1`; same for `id="analyze-deps"`; no `asciidoctor: WARN` on the four pages.

- [ ] **Step 7: Commit**

```bash
git add docs/en/modules/ROOT/pages
git commit -s -F - <<'EOF'
docs: generated code for third-party CDI jars, and vidocq:analyze-deps

The plugin page documents what vidocq:generate scans, the enriched
copies and their parameters, and the analyze-deps goal; the extension,
dev console and what's-new pages say what changes for the
langchain4j-cdi MCP server.

Refs: #186

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

### Task 9: End-to-end verification

No new code.

- [ ] **Step 1: vauban** — `./mvnw -ntp clean install` → BUILD SUCCESS; `./run-tck.sh` → CDI Lite 774/774, AtInject
green (read `vauban-tck-runner/target/surefire-reports/TestSuite.txt`).
- [ ] **Step 2: vidocq** — `./mvnw -ntp clean install -DskipTests`, then
`./mvnw -ntp -pl vidocq-runtime-maven-plugin,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension clean test`
→ BUILD SUCCESS.
- [ ] **Step 3: The langchain4j-cdi IT** —
`./mvnw -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-langchain4j-cdi-mcp clean verify`; expected:
BUILD SUCCESS, its log shows `Scanning dev.langchain4j.cdi.mcp:… declared by an extension` and `Enriched
langchain4j-cdi-mcp-server-…jar`. A failure here is a regression to debug, never to skip.
- [ ] **Step 4: Live, on the maintainer's application** — ask the maintainer to restart their `mcp-tasks-server`
`vidocq:dev` (never start a second one in their project), then read
`GET http://127.0.0.1:8888/api/snapshot`: the `dev.langchain4j.cdi.mcp` rows of the beans table read `Class-File` or
`partial`, not `reflection`. Record the counts.
- [ ] **Step 5: Report** — test counts, IT result, live counts; push and PRs only on the maintainer's go.
