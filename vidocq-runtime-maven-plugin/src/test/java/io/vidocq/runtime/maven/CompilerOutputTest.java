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

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The check {@code vidocq:dev} and {@code vidocq:run} make before launching: every file the compiler
 * created, annotation processor output included, is still in {@code target/classes} (Vidocq/vidocq#237).
 */
class CompilerOutputTest {

    @TempDir
    Path build;

    private Path classes;

    private void compiled(String execution, String... createdFiles) throws IOException {
        Path state = build.resolve("maven-status/maven-compiler-plugin/compile").resolve(execution);
        Files.createDirectories(state);
        Files.writeString(state.resolve("createdFiles.lst"), String.join("\n", createdFiles) + "\n");
        Files.writeString(state.resolve("inputFiles.lst"), "/src/A.java\n");
        classes = build.resolve("classes");
        for (String file : createdFiles) {
            Path path = classes.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
    }

    @Test
    void nothingIsMissingRightAfterTheCompiler() throws IOException {
        compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class", "META-INF/services/x.Y");

        assertEquals(List.of(), CompilerOutput.missing(build, classes));
    }

    @Test
    void listsTheCreatedFilesAnotherBuildRemoved() throws IOException {
        compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class", "META-INF/services/x.Y");
        Files.delete(classes.resolve("a/A$$FoyComponent.class"));
        Files.delete(classes.resolve("META-INF/services/x.Y"));

        assertEquals(List.of("META-INF/services/x.Y", "a/A$$FoyComponent.class"),
                CompilerOutput.missing(build, classes));
    }

    @Test
    void readsEveryCompileExecution() throws IOException {
        compiled("default-compile", "a/A.class");
        compiled("java-25-compile", "b/B.class");
        Files.delete(classes.resolve("b/B.class"));

        assertEquals(List.of("b/B.class"), CompilerOutput.missing(build, classes));
    }

    @Test
    void aBuildWithoutCompilerStateMissesNothing() throws IOException {
        assertEquals(List.of(), CompilerOutput.missing(build, build.resolve("classes")));
    }

    @Test
    void aCompleteOutputIsLaunchedAsItIs() throws Exception {
        compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class");
        AtomicInteger recompiles = new AtomicInteger();
        List<String> warnings = new ArrayList<>();

        CompilerOutput.ensureComplete(build, classes, () -> {
            recompiles.incrementAndGet();
            return 0;
        }, warnings::add);

        assertEquals(0, recompiles.get());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aGapResetsTheIncrementalStateAndRecompilesOnce() throws Exception {
        compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class");
        Files.delete(classes.resolve("a/A$$FoyComponent.class"));
        List<String> warnings = new ArrayList<>();
        AtomicInteger recompiles = new AtomicInteger();

        CompilerOutput.ensureComplete(build, classes, () -> {
            recompiles.incrementAndGet();
            // The compiler only recompiles everything once its incremental state is gone.
            assertFalse(Files.exists(build.resolve("maven-status/maven-compiler-plugin/compile/default-compile")));
            compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class");
            return 0;
        }, warnings::add);

        assertEquals(1, recompiles.get());
        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.getFirst();
        assertTrue(warning.contains("1 file(s) the compiler created are missing from " + classes), warning);
        assertTrue(warning.contains("a/A$$FoyComponent.class"), warning);
        assertTrue(warning.contains("without the annotation processors"), warning);
        assertTrue(warning.contains("recompiling"), warning);
    }

    // vidocq:dev starts from a project nobody built: after "mvn clean" there is no class to launch, no compiler state,
    // and so nothing for ensureComplete to compare. A first "mvn process-classes" builds it, Vaadin's frontend included.

    @Test
    void aProjectWithoutAClassesDirectoryIsBuiltBeforeTheLaunch() throws Exception {
        classes = build.resolve("classes");
        List<String> warnings = new ArrayList<>();
        AtomicInteger builds = new AtomicInteger();

        CompilerOutput.ensureBuilt(classes, () -> {
            builds.incrementAndGet();
            Files.createDirectories(classes.resolve("a"));
            Files.writeString(classes.resolve("a/A.class"), "x");
            return 0;
        }, warnings::add);

        assertEquals(1, builds.get());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("nothing in " + classes), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("building the project first"), warnings.getFirst());
    }

    @Test
    void aClassesDirectoryHoldingOnlyEmptyDirectoriesIsBuiltToo() throws Exception {
        classes = build.resolve("classes");
        Files.createDirectories(classes.resolve("META-INF/services"));
        AtomicInteger builds = new AtomicInteger();

        CompilerOutput.ensureBuilt(classes, () -> {
            builds.incrementAndGet();
            return 0;
        }, warning -> { });

        assertEquals(1, builds.get());
    }

    @Test
    void aBuiltProjectIsLaunchedAsItIs() throws Exception {
        compiled("default-compile", "a/A.class");
        AtomicInteger builds = new AtomicInteger();
        List<String> warnings = new ArrayList<>();

        CompilerOutput.ensureBuilt(classes, () -> {
            builds.incrementAndGet();
            return 0;
        }, warnings::add);

        assertEquals(0, builds.get());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aResourceOnlyOutputCountsAsBuilt() throws Exception {
        classes = build.resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("application.properties"), "a=b");
        AtomicInteger builds = new AtomicInteger();

        CompilerOutput.ensureBuilt(classes, () -> {
            builds.incrementAndGet();
            return 0;
        }, warning -> { });

        assertEquals(0, builds.get());
    }

    @Test
    void aFailedFirstBuildFailsTheLaunchWithItsExitCode() {
        classes = build.resolve("classes");

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> CompilerOutput.ensureBuilt(classes, () -> 1, warning -> { }));

        assertTrue(failure.getMessage().contains("exit 1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("mvn process-classes"), failure.getMessage());
    }

    @Test
    void aFirstBuildThatCannotStartFailsTheLaunch() {
        classes = build.resolve("classes");

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> CompilerOutput.ensureBuilt(classes, () -> {
                    throw new IOException("mvn not found");
                }, warning -> { }));

        assertTrue(failure.getMessage().contains("Cannot build " + classes), failure.getMessage());
        assertTrue(failure.getMessage().contains("mvn not found"), failure.getMessage());
    }

    @Test
    void anInterruptedFirstBuildFailsTheLaunchAndKeepsTheInterruption() {
        classes = build.resolve("classes");
        try {
            MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                    () -> CompilerOutput.ensureBuilt(classes, () -> {
                        throw new InterruptedException("stop");
                    }, warning -> { }));

            assertTrue(failure.getMessage().contains("Interrupted while building " + classes), failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();   // do not leak the flag into the next test
        }
    }

    @Test
    void anUnreadableDirectoryIsNotBuiltOnAGuess() throws Exception {
        classes = build.resolve("classes");
        Path locked = classes.resolve("locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("A.class"), "x");
        Set<PosixFilePermission> none = Set.of();
        assumeTrue(Files.getFileAttributeView(locked, PosixFileAttributeView.class) != null);
        Files.setPosixFilePermissions(locked, none);
        try {
            assumeTrue(!Files.isReadable(locked), "the tests run with rights that ignore permissions");
            AtomicInteger builds = new AtomicInteger();

            CompilerOutput.ensureBuilt(classes, () -> {
                builds.incrementAndGet();
                return 0;
            }, warning -> { });

            assertEquals(0, builds.get());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aProjectWithNothingToCompileStillLaunchesAfterTheFirstBuild() throws Exception {
        classes = build.resolve("classes");
        AtomicInteger builds = new AtomicInteger();

        CompilerOutput.ensureBuilt(classes, () -> {
            builds.incrementAndGet();
            return 0;
        }, warning -> { });

        assertEquals(1, builds.get());
    }

    @Test
    void aFailedRecompileFailsTheLaunch() throws IOException {
        compiled("default-compile", "a/A.class");
        Files.delete(classes.resolve("a/A.class"));

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> CompilerOutput.ensureComplete(build, classes, () -> 1, warning -> { }));

        assertTrue(failure.getMessage().contains("exit 1"), failure.getMessage());
    }

    @Test
    void anOutputStillIncompleteAfterTheRecompileFailsTheLaunch() throws IOException {
        compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class");
        Files.delete(classes.resolve("a/A$$FoyComponent.class"));

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> CompilerOutput.ensureComplete(build, classes, () -> {
                    compiled("default-compile", "a/A.class", "a/A$$FoyComponent.class");
                    Files.delete(classes.resolve("a/A$$FoyComponent.class"));
                    return 0;
                }, warning -> { }));

        assertTrue(failure.getMessage().contains("a/A$$FoyComponent.class"), failure.getMessage());
        assertTrue(failure.getMessage().contains("mvn clean"), failure.getMessage());
    }
}
