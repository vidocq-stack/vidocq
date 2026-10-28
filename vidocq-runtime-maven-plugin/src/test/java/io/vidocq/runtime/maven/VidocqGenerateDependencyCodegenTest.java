/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.maven;

import io.vidocq.runtime.maven.depgen.Brick;
import io.vidocq.runtime.maven.depgen.DependencyCodegen;
import io.vidocq.runtime.maven.depgen.DependencyCodegenCache;
import io.vidocq.runtime.maven.depgen.DependencyCodegenHints;
import io.vidocq.runtime.maven.depgen.DepgenFixtures;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dependency codegen pass of {@code vidocq:generate}: selection, cache, patch directory and failure lines. */
class VidocqGenerateDependencyCodegenTest {

    private static final int RELEASE = Runtime.version().feature();
    private static final String ALPHA_MARKER = "META-INF/fixture/alpha";

    @TempDir
    Path dir;

    private final List<String> info = new ArrayList<>();
    private final List<String> warn = new ArrayList<>();
    private final List<String> error = new ArrayList<>();
    private final AtomicInteger generated = new AtomicInteger();

    // --- fixtures ---------------------------------------------------------------------------------------------

    private static DependencyCodegenHints hints(String name) {
        // The class-bytes hint is the annotation's descriptor: the fixture jars hold fixture.Marked itself, and a hint
        // that prefixes one of a jar's own classes would not count there.
        return new DependencyCodegenHints(name, List.of("META-INF/fixture/" + name.toLowerCase()),
                List.of("Lfixture/Marked;"),
                List.of("META-INF/fixture/" + name.toLowerCase() + "-done"), List.of("META-INF/fixture/a.list"));
    }

    /** A brick named {@code name} selecting the jars that hold {@code META-INF/fixture/<name>}, run by {@code processor}. */
    private Brick brick(String name, String processor) throws IOException {
        return new Brick(hints(name), "io.vidocq:" + name.toLowerCase() + "-codegen:1.0",
                List.of(DepgenFixtures.processorJar(dir.resolve("bricks"), processor)));
    }

    /** A brick whose processor path is a plain file: for the fake generator. */
    private Brick fakeBrick(String name) throws IOException {
        Path processor = dir.resolve(name + "-codegen-1.0.jar");
        Files.writeString(processor, name);
        return new Brick(hints(name), "io.vidocq:" + name.toLowerCase() + "-codegen:1.0", List.of(processor));
    }

    private static byte[] classBytes(String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), cb -> {
        });
    }

    /** {@code <name>.jar} holding {@code com.acme.Foo} and {@code entries}. */
    private Path plainJar(String name, Map<String, String> entries) throws IOException {
        Path jar = Files.createDirectories(dir.resolve("repo")).resolve(name + ".jar");
        try (OutputStream os = Files.newOutputStream(jar); var out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry("com/acme/Foo.class"));
            out.write(classBytes("com.acme.Foo"));
            out.closeEntry();
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** A library compiled from {@code com.acme.Foo}, annotated {@code @fixture.Marked}, that Alpha selects. */
    private Path markedLib(String name) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(DepgenFixtures.ANNOTATIONS);
        sources.put("com/acme/Foo.java", "package com.acme; @fixture.Marked public class Foo {}");
        return DepgenFixtures.jar(Files.createDirectories(dir.resolve("repo")), name, sources, List.of(),
                Map.of(ALPHA_MARKER, ""));
    }

    private static ScanSelection.Dependency dep(Path jar) {
        String file = jar.getFileName().toString();
        String artifactId = file.substring(0, file.lastIndexOf('-'));
        return new ScanSelection.Dependency("com.acme", artifactId, "1.0", jar);
    }

    /** A generator that counts its runs and generates {@code com.acme.Foo$$Gen} and the given resources. */
    private DependencyCodegenPass.Generator fake(Map<String, String> resources, List<String> warnings) {
        return (_, _, _, _, _, _, _) -> {
            generated.incrementAndGet();
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("com/acme/Foo$$Gen.class", classBytes("com.acme.Foo$$Gen"));
            resources.forEach((k, v) -> files.put(k, v.getBytes(StandardCharsets.UTF_8)));
            return new DependencyCodegen.Output(files, warnings);
        };
    }

    private DependencyCodegenPass pass(Path buildDir, List<Brick> bricks, DependencyCodegenPass.Generator generator) {
        return new DependencyCodegenPass(bricks, new DependencyCodegenCache(dir.resolve("cache")), generator,
                buildDir, dir, new DependencyCodegenPass.Messages(info::add, warn::add, error::add));
    }

    private DependencyCodegenPass.Result run(Path buildDir, List<Brick> bricks, DependencyCodegenPass.Generator generator,
                                             List<Path> jars, List<String> scanDependencies, List<String> scanExcludes)
            throws Exception {
        return pass(buildDir, bricks, generator).run(jars.stream().map(VidocqGenerateDependencyCodegenTest::dep)
                .toList(), jars, RELEASE, scanDependencies, scanExcludes);
    }

    private DependencyCodegenPass.Result run(Path buildDir, List<Brick> bricks, DependencyCodegenPass.Generator generator,
                                             Path... jars) throws Exception {
        return run(buildDir, bricks, generator, List.of(jars), List.of(), List.of());
    }

    private static String brickInput(Brick brick) throws IOException {
        return "brick " + brick.hints().brick() + " " + brick.coordinates() + " sha256:"
                + DependencyCodegenCache.classpathDigest(brick.processorPath());
    }

    // --- tests -------------------------------------------------------------------------------------------------

    @Test
    void aSelectedJarGetsItsGeneratedCodeInItsPatchDirectory() throws Exception {
        Path lib = markedLib("lib-1.0");
        Brick alpha = brick("Alpha", "ProcessorA");
        Path buildDir = dir.resolve("target");

        var result = run(buildDir, List.of(alpha), DependencyCodegenPass.JAVAC, lib);

        Path patch = JpmsPatches.patchDirFor(buildDir, "lib");
        assertTrue(Files.isRegularFile(patch.resolve("com/acme/Foo$$Gen.class")));
        assertEquals("com.acme.Foo\n", Files.readString(patch.resolve("META-INF/fixture/a.list")));
        assertEquals(List.of(new EnrichedJars.Scanned(lib, "lib", "com.acme:lib:1.0", List.of("Alpha"),
                List.of(brickInput(alpha)))), result.processed());
        assertEquals(Set.of("META-INF/fixture/a.list"), result.mergedResources());
        assertTrue(info.contains("vidocq:generate: lib-1.0.jar — Alpha (1 class, generated)"), info::toString);
        assertTrue(Files.isRegularFile(buildDir.resolve("vidocq-depgen/lib.log")));
    }

    @Test
    void aSecondRunTakesTheOutputFromTheCacheAndKeepsWhatVaubanParked() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Brick alpha = fakeBrick("Alpha");
        var generator = fake(Map.of("META-INF/fixture/a.list", "com.acme.Foo\n"), List.of());

        run(dir.resolve("first"), List.of(alpha), generator, lib);
        Path second = dir.resolve("second");
        Path vauban = JpmsPatches.patchDirFor(second, "lib").resolve("com/acme/Foo$$VaubanProxy.class");
        Files.createDirectories(vauban.getParent());
        Files.write(vauban, classBytes("com.acme.Foo$$VaubanProxy"));
        var result = run(second, List.of(alpha), generator, lib);

        assertEquals(1, generated.get(), "the second run is a cache hit");
        assertEquals(List.of("vidocq:generate: lib-1.0.jar — Alpha (1 class, generated)",
                "vidocq:generate: lib-1.0.jar — Alpha (1 class, cached)"), info);
        Path patch = JpmsPatches.patchDirFor(second, "lib");
        assertTrue(Files.isRegularFile(patch.resolve("com/acme/Foo$$Gen.class")));
        assertEquals("com.acme.Foo\n", Files.readString(patch.resolve("META-INF/fixture/a.list")));
        assertTrue(Files.isRegularFile(vauban), "what Vauban parked stays");
        assertEquals(List.of("Alpha"), result.processed().getFirst().bricks());
    }

    @Test
    void aJarNoLongerProcessedLosesWhatTheBricksWroteButKeepsWhatVaubanParked() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Brick alpha = fakeBrick("Alpha");
        var generator = fake(Map.of("META-INF/fixture/a.list", "com.acme.Foo\n"), List.of());
        Path buildDir = dir.resolve("target");
        Path patch = JpmsPatches.patchDirFor(buildDir, "lib");
        // What the mojo does on each run: forget the bricks' last output, then run the pass.
        DependencyCodegenPass.forgetWritten(buildDir);
        run(buildDir, List.of(alpha), generator, lib);
        assertTrue(Files.isRegularFile(patch.resolve("com/acme/Foo$$Gen.class")));

        DependencyCodegenPass.forgetWritten(buildDir);
        run(buildDir, List.of(alpha), generator, List.of(lib), List.of(), List.of("com.acme:lib"));

        assertFalse(Files.exists(patch), "the brick files and their emptied patch directory are gone");
        assertEquals(List.of(), JpmsPatches.patchModuleArgs(buildDir, Map.of("lib", lib)),
                "run and dev no longer patch the jar");

        // A jar Vauban also parked classes for keeps them.
        DependencyCodegenPass.forgetWritten(buildDir);
        run(buildDir, List.of(alpha), generator, lib);
        Path vauban = patch.resolve("com/acme/Foo$$VaubanProxy.class");
        Files.write(vauban, classBytes("com.acme.Foo$$VaubanProxy"));
        DependencyCodegenPass.forgetWritten(buildDir);
        run(buildDir, List.of(alpha), generator, List.of(lib), List.of(), List.of("com.acme:lib"));

        assertTrue(Files.isRegularFile(vauban), "what Vauban parked stays");
        assertFalse(Files.exists(patch.resolve("com/acme/Foo$$Gen.class")));
        assertFalse(Files.exists(patch.resolve("META-INF/fixture/a.list")));
    }

    @Test
    void aJarProcessedAgainGetsOnlyItsNewOutput() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Path buildDir = dir.resolve("target");
        Path patch = JpmsPatches.patchDirFor(buildDir, "lib");
        DependencyCodegenPass.forgetWritten(buildDir);
        run(buildDir, List.of(fakeBrick("Alpha")), fake(Map.of("META-INF/fixture/old.list", "x\n"), List.of()), lib);

        DependencyCodegenPass.forgetWritten(buildDir);
        run(buildDir, List.of(fakeBrick("Beta")), (_, _, _, _, _, _, _) ->
                new DependencyCodegen.Output(Map.of("META-INF/fixture/new.list", "y\n".getBytes(StandardCharsets.UTF_8)),
                        List.of()), plainJar("lib-1.0", Map.of("META-INF/fixture/beta", "")));

        assertTrue(Files.isRegularFile(patch.resolve("META-INF/fixture/new.list")));
        assertFalse(Files.exists(patch.resolve("META-INF/fixture/old.list")), "the earlier output is replaced");
        assertFalse(Files.exists(patch.resolve("com/acme/Foo$$Gen.class")));
    }

    @Test
    void anotherVersionOfAClasspathDependencyMissesTheCache() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Path api = plainJar("api-1.0", Map.of());
        Brick alpha = fakeBrick("Alpha");
        var generator = fake(Map.of(), List.of());
        var withApi10 = List.of(dep(lib), new ScanSelection.Dependency("org.x", "api", "1.0", api));
        var withApi11 = List.of(dep(lib), new ScanSelection.Dependency("org.x", "api", "1.1", api));

        pass(dir.resolve("a"), List.of(alpha), generator).run(withApi10, List.of(lib, api), RELEASE, List.of(),
                List.of());
        pass(dir.resolve("b"), List.of(alpha), generator).run(withApi10, List.of(lib, api), RELEASE, List.of(),
                List.of());
        pass(dir.resolve("c"), List.of(alpha), generator).run(withApi11, List.of(lib, api), RELEASE, List.of(),
                List.of());

        assertEquals(2, generated.get(), "same classpath: a hit; another version on it: a miss");
    }

    @Test
    void onlyTheHintedClassesAreProcessedSoAnUnrelatedClassMissingAnApiDoesNotFail() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path annotations = DepgenFixtures.jar(repo, "annotations-1.0", DepgenFixtures.ANNOTATIONS, List.of(),
                Map.of());
        Path optionalApi = DepgenFixtures.jar(repo, "optional-api-1.0",
                Map.of("missing/Api.java", "package missing; public class Api {}"), List.of(), Map.of());
        // Endpoint needs an optional API the build does not have, as atmosphere-runtime needs jakarta.websocket.
        Path lib = DepgenFixtures.jar(repo, "lib-1.0", Map.of(
                "com/acme/Foo.java", "package com.acme; @fixture.Marked public class Foo {}",
                "com/acme/Endpoint.java", "package com.acme; public class Endpoint extends missing.Api {}"),
                List.of(annotations, optionalApi), Map.of());
        Brick marked = new Brick(new DependencyCodegenHints("Alpha", List.of(), List.of("fixture/Marked"),
                List.of(), List.of("META-INF/fixture/a.list")), "io.vidocq:alpha-codegen:1.0",
                List.of(DepgenFixtures.processorJar(dir.resolve("bricks"), "ProcessorA")));
        Path buildDir = dir.resolve("target");

        pass(buildDir, List.of(marked), DependencyCodegenPass.JAVAC).run(List.of(dep(lib),
                new ScanSelection.Dependency("org.x", "annotations", "1.0", annotations)),
                List.of(lib, annotations), RELEASE, List.of(), List.of());

        assertEquals(List.of(), error);
        Path patch = JpmsPatches.patchDirFor(buildDir, "lib");
        assertTrue(Files.isRegularFile(patch.resolve("com/acme/Foo$$Gen.class")));
        assertEquals("com.acme.Foo\n", Files.readString(patch.resolve("META-INF/fixture/a.list")));
    }

    @Test
    void aJarSelectedByAServiceFileGetsTheListedClassProcessed() throws Exception {
        String sci = "META-INF/services/jakarta.servlet.ServletContainerInitializer";
        Path lib = plainJar("lib-1.0", Map.of(sci, "com.acme.Foo\n"));
        Brick foy = new Brick(new DependencyCodegenHints("Foy", List.of(sci), List.of(), List.of(), List.of()),
                "io.vidocq:foy-codegen:1.0", List.of(Files.writeString(dir.resolve("foy.jar"), "foy")));
        List<List<String>> passed = new ArrayList<>();

        run(dir.resolve("target"), List.of(foy), (_, _, classes, _, _, _, _) -> {
            passed.add(classes);
            return new DependencyCodegen.Output(Map.of(), List.of());
        }, lib);

        assertEquals(List.of(List.of("com.acme.Foo")), passed);
    }

    @Test
    void aSelectedJarWithNoClassToProcessRunsNothingAndIsCached() throws Exception {
        String fragment = "META-INF/web-fragment.xml";
        Path lib = plainJar("lib-1.0", Map.of(fragment, "<web-fragment/>"));
        Brick foy = new Brick(new DependencyCodegenHints("Foy", List.of(fragment), List.of("jakarta/servlet/x"),
                List.of(), List.of()), "io.vidocq:foy-codegen:1.0",
                List.of(Files.writeString(dir.resolve("foy.jar"), "foy")));

        run(dir.resolve("a"), List.of(foy), DependencyCodegenPass.JAVAC, lib);
        run(dir.resolve("b"), List.of(foy), DependencyCodegenPass.JAVAC, lib);

        assertEquals(List.of("vidocq:generate: lib-1.0.jar — Foy (0 classes, generated)",
                "vidocq:generate: lib-1.0.jar — Foy (0 classes, cached)"), info);
        assertFalse(Files.exists(dir.resolve("a/vidocq-depgen/lib.log")), "no javac ran");
    }

    @Test
    void aWholeJarResourceIsDroppedFromThePatchButCachedRaw() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Path processor = Files.writeString(dir.resolve("alpha-codegen-1.0.jar"), "alpha");
        Brick alpha = new Brick(new DependencyCodegenHints("Alpha", List.of(ALPHA_MARKER), List.of(), List.of(),
                List.of(), List.of("META-INF/fixture/whole.list")), "io.vidocq:alpha-codegen:1.0", List.of(processor));
        var generator = fake(Map.of("META-INF/fixture/whole.list", "com.acme.Foo\n"), List.of());

        run(dir.resolve("first"), List.of(alpha), generator, lib);
        run(dir.resolve("second"), List.of(alpha), generator, lib);

        for (String build : List.of("first", "second")) {
            Path patch = JpmsPatches.patchDirFor(dir.resolve(build), "lib");
            assertTrue(Files.isRegularFile(patch.resolve("com/acme/Foo$$Gen.class")), build);
            assertFalse(Files.exists(patch.resolve("META-INF/fixture/whole.list")),
                    build + ": it would describe only the hinted classes");
        }
        assertEquals(1, generated.get(), "the second build is a hit: the raw output is cached");
        try (var entries = Files.walk(dir.resolve("cache"))) {
            assertTrue(entries.anyMatch(p -> p.endsWith("META-INF/fixture/whole.list")),
                    "the cache keeps it, so a hints change needs no new entry");
        }
    }

    @Test
    void theFailuresOfEveryJarFailTheBuildTogether() throws Exception {
        Path lib = markedLib("lib-1.0");
        Path other = markedLib("other-1.0");
        Brick epsilon = new Brick(hints("Alpha"), "io.vidocq:epsilon-codegen:1.0",
                List.of(DepgenFixtures.processorJar(dir.resolve("bricks"), "ProcessorE")));
        Path buildDir = dir.resolve("target");

        var failure = assertThrows(MojoFailureException.class,
                () -> run(buildDir, List.of(epsilon), DependencyCodegenPass.JAVAC, lib, other));

        assertEquals(List.of(
                "vidocq:generate: lib-1.0.jar — Alpha cannot process com.acme.Foo: cannot handle com.acme.Foo."
                        + " Fix the library, or leave the jar out with <scanExcludes>com.acme:lib</scanExcludes>."
                        + " Full javac output: " + Path.of("target", "vidocq-depgen", "lib.log"),
                "vidocq:generate: other-1.0.jar — Alpha cannot process com.acme.Foo: cannot handle com.acme.Foo."
                        + " Fix the library, or leave the jar out with <scanExcludes>com.acme:other</scanExcludes>."
                        + " Full javac output: " + Path.of("target", "vidocq-depgen", "other.log")), error);
        assertEquals(String.join("\n", error), failure.getMessage());
        assertFalse(Files.exists(JpmsPatches.patchDirFor(buildDir, "lib")));
    }

    @Test
    void aSignedJarIsLeftAloneWithOneLine() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, "", "META-INF/ACME.SF", "", "META-INF/ACME.RSA", ""));

        var result = run(dir.resolve("target"), List.of(fakeBrick("Alpha")), fake(Map.of(), List.of()), lib);

        assertEquals(0, generated.get());
        assertEquals(List.of(), result.processed());
        assertEquals(List.of("vidocq:generate: lib-1.0.jar is signed; not processed for Alpha"
                + " (name it in scanDependencies to process it)"), info);
    }

    @Test
    void aSignedJarNamedInScanDependenciesIsProcessed() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, "", "META-INF/ACME.SF", ""));

        var result = run(dir.resolve("target"), List.of(fakeBrick("Alpha")), fake(Map.of(), List.of()),
                List.of(lib), List.of("com.acme:lib"), List.of());

        assertEquals(1, generated.get());
        assertEquals(1, result.processed().size());
    }

    @Test
    void aJarInScanExcludesOrCarryingTheMarkerIsLeftAlone() throws Exception {
        Path excluded = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Path processed = plainJar("done-1.0", Map.of(ALPHA_MARKER, "", "META-INF/fixture/alpha-done", ""));

        var result = run(dir.resolve("target"), List.of(fakeBrick("Alpha")), fake(Map.of(), List.of()),
                List.of(excluded, processed), List.of(), List.of("com.acme:lib"));

        assertEquals(0, generated.get());
        assertEquals(List.of(), result.processed());
    }

    @Test
    void aGeneratedResourceTheJarHasMustBeOneTheBrickMerges() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, "", "META-INF/fixture/a.list", "x\n",
                "META-INF/fixture/b.list", "y\n"));
        Path buildDir = dir.resolve("target");

        var failure = assertThrows(MojoFailureException.class, () -> run(buildDir, List.of(fakeBrick("Alpha")),
                fake(Map.of("META-INF/fixture/a.list", "z\n", "META-INF/fixture/b.list", "z\n"), List.of()), lib));

        String line = "vidocq:generate: lib-1.0.jar — Alpha generated META-INF/fixture/b.list, which the jar already"
                + " has; it is not a mergeable resource of that brick. Leave the jar out with"
                + " <scanExcludes>com.acme:lib</scanExcludes>.";
        assertEquals(List.of(line), error);
        assertEquals(line, failure.getMessage());
        assertFalse(Files.exists(JpmsPatches.patchDirFor(buildDir, "lib")), "nothing is written for that jar");
    }

    @Test
    void processorWarningsBecomeBuildWarnings() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        String warning = "vidocq:generate: lib-1.0.jar — Alpha warns about com.acme.Foo: looks odd";

        run(dir.resolve("target"), List.of(fakeBrick("Alpha")), fake(Map.of(), List.of(warning)), lib);

        assertEquals(List.of(warning), warn);
    }

    @Test
    void anEmptyOutputLeavesTheJarAsItIs() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        DependencyCodegenPass.Generator nothing = (_, _, _, _, _, _, _) ->
                new DependencyCodegen.Output(Map.of(), List.of());
        Path buildDir = dir.resolve("target");

        var result = run(buildDir, List.of(fakeBrick("Alpha")), nothing, lib);

        assertEquals(List.of(), result.processed());
        assertFalse(Files.exists(JpmsPatches.patchDirFor(buildDir, "lib")));
        assertEquals(List.of("vidocq:generate: lib-1.0.jar — Alpha (0 classes, generated)"), info);
    }

    @Test
    void theLineCountsTwoClassesInThePlural() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        DependencyCodegenPass.Generator two = (_, _, _, _, _, _, _) ->
                new DependencyCodegen.Output(Map.of("com/acme/Foo$$A.class", classBytes("com.acme.Foo$$A"),
                        "com/acme/Foo$$B.class", classBytes("com.acme.Foo$$B")), List.of());

        run(dir.resolve("target"), List.of(fakeBrick("Alpha")), two, lib);

        assertEquals(List.of("vidocq:generate: lib-1.0.jar — Alpha (2 classes, generated)"), info);
    }

    @Test
    void aJarOfTheVidocqRuntimeIsNoBrickCandidate() throws Exception {
        Path own = plainJar("foy-core-1.0", Map.of(ALPHA_MARKER, ""));
        Path sub = plainJar("ext-1.0", Map.of(ALPHA_MARKER, ""));
        Path lookalike = plainJar("lookalike-1.0", Map.of(ALPHA_MARKER, ""));
        var deps = List.of(new ScanSelection.Dependency("io.vidocq", "foy-core", "1.0", own),
                new ScanSelection.Dependency("io.vidocq.ext", "ext", "1.0", sub),
                new ScanSelection.Dependency("io.vidocqx", "lookalike", "1.0", lookalike));

        var result = pass(dir.resolve("target"), List.of(fakeBrick("Alpha")), fake(Map.of(), List.of()))
                .run(deps, List.of(own, sub, lookalike), RELEASE, List.of(), List.of());

        assertEquals(List.of("lookalike"), result.processed().stream().map(EnrichedJars.Scanned::artifactId).toList());
    }

    @Test
    void theSelectionNamesTheBricksOfEachJarItKeeps() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, "", "META-INF/fixture/beta", ""));
        Path done = plainJar("done-1.0", Map.of(ALPHA_MARKER, "", "META-INF/fixture/alpha-done", ""));
        Path excluded = plainJar("excluded-1.0", Map.of(ALPHA_MARKER, ""));
        Path plain = plainJar("plain-1.0", Map.of());
        var bricks = List.of(fakeBrick("Beta"), fakeBrick("Alpha"));

        var selection = DependencyCodegenPass.select(bricks, List.of(lib, done, excluded, plain).stream()
                .map(VidocqGenerateDependencyCodegenTest::dep).toList(), List.of("com.acme:excluded"));

        assertEquals(List.of("lib", "done"), selection.stream().map(s -> s.dependency().artifactId()).toList(),
                "a jar a brick skips for its marker stays, to be reported");
        assertEquals(Map.of(lib, List.of("Beta", "Alpha")), DependencyCodegenPass.bricksByJar(selection));
    }

    @Test
    void withoutABrickNothingRuns() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));

        var result = run(dir.resolve("target"), List.of(), fake(Map.of(), List.of()), lib);

        assertEquals(0, generated.get());
        assertEquals(List.of(), result.processed());
        assertEquals(Set.of(), result.mergedResources());
        assertEquals(List.of(), info);
    }

    @Test
    void theBrickInputsAreSortedOneLinePerBrick() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, "", "META-INF/fixture/beta", ""));
        Brick beta = fakeBrick("Beta");
        Brick alpha = fakeBrick("Alpha");

        var result = run(dir.resolve("target"), List.of(beta, alpha), fake(Map.of(), List.of()), lib);

        var scanned = result.processed().getFirst();
        assertEquals(List.of("Alpha", "Beta"), scanned.bricks());
        assertEquals(List.of(brickInput(alpha), brickInput(beta)), scanned.brickInputs());
        assertEquals(List.of("vidocq:generate: lib-1.0.jar — Beta, Alpha (1 class, generated)"), info);
    }

    @Test
    void oneScannedPerJarJoinsVaubanAndTheBricks() {
        Path a = dir.resolve("a-1.0.jar");
        Path b = dir.resolve("b-1.0.jar");
        Path c = dir.resolve("c-1.0.jar");
        var vaubanA = new EnrichedJars.Scanned(a, "a", "g:a:1.0");
        var vaubanB = new EnrichedJars.Scanned(b, "b", "g:b:1.0");
        var bricksB = new EnrichedJars.Scanned(b, "b", "g:b:1.0", List.of("Foy"), List.of("brick Foy x sha256:1"));
        var bricksC = new EnrichedJars.Scanned(c, "c", "g:c:1.0", List.of("Foy"), List.of("brick Foy x sha256:1"));

        assertEquals(List.of(vaubanA, bricksB, bricksC),
                DependencyCodegenPass.onePerJar(List.of(vaubanA, vaubanB), List.of(bricksB, bricksC)));
    }

    @Test
    void packagingReadsBackTheBricksOfTheCopy() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Brick alpha = fakeBrick("Alpha");
        Path buildDir = dir.resolve("target");
        var result = run(buildDir, List.of(alpha), fake(Map.of(), List.of()), lib);
        EnrichedJars.enrichAll(buildDir, result.processed(), List.of(), List.of(lib),
                new EnrichedJars.BrickMerge(result.mergedResources(), packages -> Set.of()), info::add, warn::add);
        var bare = new EnrichedJars.Scanned(lib, "lib", "com.acme:lib:1.0");

        assertEquals(result.processed().getFirst(),
                DependencyBricks.forPackaging(buildDir, bare, () -> List.of(alpha)));
        assertEquals(bare, DependencyBricks.forPackaging(buildDir, bare, List::of),
                "a brick the project no longer has: the copy is kept as generate wrote it");
        Path otherBuild = dir.resolve("other");
        assertEquals(bare, DependencyBricks.forPackaging(otherBuild, bare, () -> {
            throw new AssertionError("no copy: the bricks are not looked up");
        }));
    }

    @Test
    void jlinkRebuildsABrickCopyOnTheModularizedJarWithTheSameBricks() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of(ALPHA_MARKER, ""));
        Brick alpha = fakeBrick("Alpha");
        Path buildDir = dir.resolve("target");
        var result = run(buildDir, List.of(alpha), fake(Map.of("META-INF/fixture/a.list", "com.acme.Foo\n"),
                List.of()), lib);
        EnrichedJars.enrichAll(buildDir, result.processed(), List.of(), List.of(lib),
                new EnrichedJars.BrickMerge(result.mergedResources(), packages -> Set.of()), info::add, warn::add);
        var bare = new EnrichedJars.Scanned(lib, "lib", "com.acme:lib:1.0");
        AtomicInteger lookups = new AtomicInteger();
        DependencyBricks.Source source = () -> {
            lookups.incrementAndGet();
            return List.of(alpha);
        };

        new DependencyBricks.Packaging(buildDir, source, List.of(lib), info::add, true).rebase(bare, info::add);
        assertEquals(0, lookups.get(), "nothing modularized: the bricks are not looked up");

        Path modularized = Files.createDirectories(buildDir.resolve("vauban-modularized")).resolve("lib-1.0.jar");
        try (OutputStream os = Files.newOutputStream(modularized); var out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry("module-info.class"));
            out.write(EnrichedJarsTest.descriptor("org.lib"));
            out.putNextEntry(new JarEntry("com/acme/Foo.class"));
            out.write(classBytes("com.acme.Foo"));
            out.putNextEntry(new JarEntry(ALPHA_MARKER));
            out.closeEntry();
        }
        new DependencyBricks.Packaging(buildDir, source, List.of(lib), info::add, true).rebase(bare, info::add);

        assertEquals(1, lookups.get());
        Path copy = EnrichedJars.resolve(buildDir, lib);
        try (var jar = new java.util.jar.JarFile(copy.toFile())) {
            assertEquals("Alpha", jar.getManifest().getMainAttributes().getValue("Vidocq-Enriched-By"));
            assertEquals("org.lib", java.lang.module.ModuleDescriptor.read(
                    jar.getInputStream(jar.getEntry("module-info.class"))).name());
            assertNotNull(jar.getEntry("com/acme/Foo$$Gen.class"));
        }
        assertTrue(info.contains("Rebuilt the enriched copy of lib-1.0.jar on the module descriptor"
                + " vauban:modularize generated"), info::toString);
    }

    @Test
    void theModulesOfPackagesComeFromTheDependenciesAsAModulePath() throws Exception {
        Path lib = plainJar("lib-1.0", Map.of());

        assertEquals(Set.of("lib"), DependencyBricks.modulesOfPackages(List.of(lib), info::add)
                .apply(Set.of("com.acme", "org.unknown")));
        assertEquals(List.of(), info);
    }

    @Test
    void dependenciesThatDoNotResolveAsModulesRequireNothing() throws Exception {
        Path invalid = plainJar("1-1.0", Map.of());
        List<String> debug = new ArrayList<>();

        assertEquals(Set.of(), DependencyBricks.modulesOfPackages(List.of(invalid), debug::add)
                .apply(Set.of("com.acme")));
        assertEquals(1, debug.size(), debug::toString);
    }

    @Test
    void oneDependencyThatIsNotAModuleDoesNotHideTheOthers() throws Exception {
        Path invalid = plainJar("1-1.0", Map.of());
        Path other = DepgenFixtures.jar(Files.createDirectories(dir.resolve("repo")), "other-1.0",
                Map.of("org/other/Thing.java", "package org.other; public class Thing {}"), List.of(), Map.of());
        List<String> warnings = new ArrayList<>();

        assertEquals(Set.of("other"), DependencyBricks.modulesOfPackages(List.of(invalid, other), warnings::add)
                .apply(Set.of("org.other", "com.acme")));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("1-1.0.jar"), warnings::toString);
    }

    @Test
    void aLogPathThatCannotBeRelativizedIsShownAbsolute() {
        Path log = dir.resolve("target/vidocq-depgen/lib.log");

        assertEquals("target/vidocq-depgen/lib.log", DependencyCodegenPass.display(dir, log));
        assertEquals(log.toString(), DependencyCodegenPass.display(Path.of("relative/base"), log));
    }

    @Test
    void theReleaseIsTheProjectsElseTheRunningJdks() {
        assertEquals(21, DependencyCodegenPass.release("21"));
        assertEquals(RELEASE, DependencyCodegenPass.release(null));
        assertEquals(RELEASE, DependencyCodegenPass.release(" "));
    }
}
