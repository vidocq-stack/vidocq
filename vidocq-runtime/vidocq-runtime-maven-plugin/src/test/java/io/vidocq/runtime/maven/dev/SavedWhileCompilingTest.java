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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** BUG-20261117-03: a source saved while a compile ran is compiled by the next one. */
class SavedWhileCompilingTest {

    /** When the compile of the first save started: a whole second, so that any file system keeps the times below. */
    private static final Instant STARTED = Instant.parse("2026-11-17T01:42:54Z");

    @TempDir
    Path dir;

    private Path sources;
    private Path classes;
    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private SavedWhileCompiling saved;

    @BeforeEach
    void aProject() {
        sources = dir.resolve("src/main/java");
        classes = dir.resolve("target/classes");
        saved = compiling(sources);
    }

    /** The recompile of the dev loop, of these source roots into {@link #classes}. */
    private SavedWhileCompiling compiling(Path... roots) {
        return new SavedWhileCompiling(List.of(roots), classes, "recompile", info::add, warnings::add);
    }

    /** A file last modified at {@code time}. */
    private static Path file(Path file, Instant time) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        return stamp(file, time);
    }

    private static Path stamp(Path file, Instant time) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(time));
        return file;
    }

    /**
     * The loss: javac read the source, the developer saved it again, javac wrote the class of what it had read. The
     * class is newer than the source, the compiler plugin would skip it for good. Without its class it is compiled.
     */
    @Test
    void aSourceSavedAfterJavacReadItLosesItsClassWhenTheNextCompileStarts() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        Path other = file(classes.resolve("acme/Tally.class"), STARTED.minusSeconds(60));
        file(sources.resolve("acme/Tally.java"), STARTED.minusSeconds(90));
        assertEquals(List.of(), saved.compileStarts(), "the compile of the first save");
        stamp(source, STARTED.plusSeconds(6));
        stamp(itsClass, STARTED.plusSeconds(7));
        stamp(other, STARTED.plusSeconds(7));
        saved.compileEnds();

        assertEquals(List.of(source), saved.compileStarts());

        assertFalse(Files.exists(itsClass), "the compiler plugin compiles a source that has no class");
        assertTrue(Files.exists(other), "the class of a source nobody saved");
    }

    /** A save made after the compile wrote the class: the source is newer, the compiler plugin sees it by itself. */
    @Test
    void aSourceNewerThanItsClassIsLeftToTheCompilerPlugin() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(itsClass, STARTED.plusSeconds(7));
        saved.compileEnds();
        stamp(source, STARTED.plusSeconds(20));

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass));
    }

    /** A recompile that a resource triggered: no source was saved, every class stays. */
    @Test
    void aSourceNobodySavedKeepsItsClass() throws Exception {
        file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(itsClass, STARTED.plusSeconds(7));
        saved.compileEnds();

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass));
    }

    /** Nothing is known of a compile that ran before the session: the first compile deletes nothing. */
    @Test
    void theFirstCompileHasNothingToCompareWith() throws Exception {
        file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(7));

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass));
    }

    /**
     * The compiler plugin compares the times in milliseconds: a class written a moment before the save, in the same
     * millisecond, is up to date for it. Such a source counts as skipped too.
     */
    @Test
    void aClassOfTheSameMillisecondAsTheSaveIsUpToDateForTheCompilerPlugin() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(itsClass, STARTED.plusSeconds(7).plusNanos(100_000));
        stamp(source, STARTED.plusSeconds(7).plusNanos(400_000));
        saved.compileEnds();

        assertEquals(List.of(source), saved.compileStarts());

        assertFalse(Files.exists(itsClass));
    }

    /** A source created while the compile ran, and saved again before its class was written: the same loss. */
    @Test
    void aSourceCreatedWhileTheCompileRanCounts() throws Exception {
        file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        saved.compileStarts();
        Path created = file(sources.resolve("acme/Receipt.java"), STARTED.plusSeconds(6));
        Path itsClass = file(classes.resolve("acme/Receipt.class"), STARTED.plusSeconds(7));
        saved.compileEnds();

        assertEquals(List.of(created), saved.compileStarts());

        assertFalse(Files.exists(itsClass));
    }

    /** The module descriptor is a source like another, at the root; a later root is read as the first. */
    @Test
    void theDescriptorAndTheSourcesOfEveryRootCount() throws Exception {
        Path generated = dir.resolve("src/main/generated");
        saved = compiling(sources, generated);
        Path descriptor = file(sources.resolve("module-info.java"), STARTED.minusSeconds(2));
        Path source = file(generated.resolve("acme/Tally.java"), STARTED.minusSeconds(2));
        saved.compileStarts();
        stamp(descriptor, STARTED.plusSeconds(6));
        stamp(source, STARTED.plusSeconds(6));
        Path descriptorClass = file(classes.resolve("module-info.class"), STARTED.plusSeconds(7));
        Path itsClass = file(classes.resolve("acme/Tally.class"), STARTED.plusSeconds(7));
        saved.compileEnds();

        assertEquals(List.of(source, descriptor), saved.compileStarts(), "in the order of the paths");

        assertFalse(Files.exists(descriptorClass));
        assertFalse(Files.exists(itsClass));
    }

    /** Only Java sources have a class: a resource saved while the compile ran deletes nothing. */
    @Test
    void aFileThatIsNotAJavaSourceIsIgnored() throws Exception {
        Path resource = file(sources.resolve("acme/Menu.properties"), STARTED.minusSeconds(2));
        Path sameName = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(resource, STARTED.plusSeconds(6));
        stamp(sameName, STARTED.plusSeconds(7));
        saved.compileEnds();

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(sameName));
    }

    /** A source that has no class yet, after a compile that failed: nothing to delete, the plugin compiles it. */
    @Test
    void aSourceWithoutAClassIsLeftToTheCompilerPlugin() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        saved.compileStarts();
        stamp(source, STARTED.plusSeconds(6));
        saved.compileEnds();

        assertEquals(List.of(), saved.compileStarts());
    }

    /** Once compiled again, the source is as any other: the compile after that deletes nothing. */
    @Test
    void aSourceIsCompiledAgainOnce() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(source, STARTED.plusSeconds(6));
        stamp(itsClass, STARTED.plusSeconds(7));
        saved.compileEnds();
        saved.compileStarts();
        file(itsClass, STARTED.plusSeconds(30));
        saved.compileEnds();

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass), "the class the second compile wrote");
    }

    /**
     * An IDE that builds on save writes the class itself, after the compile ended. That class is of the save, and
     * the compiler plugin is right to find nothing to compile: only a class the previous compile left is in doubt.
     */
    @Test
    void aClassAnotherBuildWroteAfterTheCompileIsLeftAlone() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        saved.compileEnds();
        stamp(source, STARTED.plusSeconds(20));
        stamp(itsClass, STARTED.plusSeconds(21));

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass), "the class the IDE compiled from the save");
        assertEquals(List.of(), info);
    }

    /** A compile that was interrupted never says it ended: every class that is not older is in doubt. */
    @Test
    void aCompileWhoseEndIsUnknownPutsEveryClassInDoubt() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(source, STARTED.plusSeconds(6));
        stamp(itsClass, STARTED.plusSeconds(7));

        assertEquals(List.of(source), saved.compileStarts());

        assertFalse(Files.exists(itsClass));
    }

    /**
     * A compile whose classes cannot be read when it ends, here because one is a link to itself: its end is unknown,
     * as if it never said it ended.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCompileWhoseClassesCannotBeReadAtItsEndPutsEveryClassInDoubt() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = classes.resolve("acme/Menu.class");
        Files.createDirectories(itsClass.getParent());
        saved.compileStarts();
        Files.createSymbolicLink(itsClass, itsClass);
        saved.compileEnds();
        Files.delete(itsClass);
        stamp(source, STARTED.plusSeconds(20));
        file(itsClass, STARTED.plusSeconds(21));

        assertEquals(List.of(source), saved.compileStarts());

        assertFalse(Files.exists(itsClass), "a class written after an end nobody noted is in doubt, an IDE's too");
        assertEquals(List.of(), warnings, "the end of a compile warns of nothing");
    }

    /**
     * A directory of sources that cannot be read: its sources cannot be followed, and the compile that starts says
     * so. Only a source that went away while its directory was read is skipped in silence.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aDirectoryOfSourcesThatCannotBeReadIsAWarning() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads every directory");
        Path closed = file(sources.resolve("acme/closed/Menu.java"), STARTED.minusSeconds(2)).getParent();
        Files.setPosixFilePermissions(closed, Set.of());
        try {
            assertEquals(List.of(), saved.compileStarts());
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
        }

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot tell whether a source was saved since the previous"
                + " recompile started (java.nio.file.AccessDeniedException"), warnings.getFirst());
    }

    /** The compile that starts says which save it compiles again, by the name of its file, and why. */
    @Test
    void theCompileThatStartsNamesTheSourceItCompilesAgain() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(source, STARTED.plusSeconds(6));
        stamp(itsClass, STARTED.plusSeconds(7));
        saved.compileEnds();

        saved.compileStarts();

        assertEquals(List.of("Menu.java was saved since the previous recompile started and its class is not older:"
                + " compiling it again."), info);
        assertEquals(List.of(), warnings);
    }

    /** Several saves: their number, and the first three names, in the order of the paths. */
    @Test
    void theCompileThatStartsNamesThreeSourcesOfMore() throws Exception {
        List<String> names = List.of("Tally", "Menu", "Receipt", "Basket");
        for (String name : names) {
            file(sources.resolve("acme/" + name + ".java"), STARTED.minusSeconds(2));
            file(classes.resolve("acme/" + name + ".class"), STARTED.minusSeconds(60));
        }
        saved.compileStarts();
        for (String name : names) {
            stamp(sources.resolve("acme/" + name + ".java"), STARTED.plusSeconds(6));
            stamp(classes.resolve("acme/" + name + ".class"), STARTED.plusSeconds(7));
        }
        saved.compileEnds();

        saved.compileStarts();

        assertEquals(List.of("4 sources were saved since the previous recompile started and their classes are not"
                + " older (Basket.java, Menu.java, Receipt.java, …): compiling them again."), info);
        assertEquals(List.of(), warnings);
    }

    /** A compile that no save preceded says nothing more than it did. */
    @Test
    void theCompileThatStartsSaysNothingWhenNoSaveWasMissed() throws Exception {
        file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        file(classes.resolve("acme/Menu.class"), STARTED.minusSeconds(60));

        saved.compileStarts();
        saved.compileEnds();
        saved.compileStarts();

        assertEquals(List.of(), info);
        assertEquals(List.of(), warnings);
    }

    /**
     * A class that cannot be deleted, here a directory that is not empty: the compile goes on, with a warning that
     * says what to do if a save does not show.
     */
    @Test
    void theCompileThatStartsWarnsWhenItCannotTell() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path notAClass = classes.resolve("acme/Menu.class");
        file(notAClass.resolve("held"), STARTED.minusSeconds(60));
        saved.compileStarts();
        stamp(source, STARTED.plusSeconds(6));
        stamp(notAClass, STARTED.plusSeconds(7));
        saved.compileEnds();

        assertEquals(List.of(), saved.compileStarts());

        assertEquals(List.of(), info);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot tell whether a source was saved since the previous"
                + " recompile started ("), warnings.getFirst());
        assertTrue(warnings.getFirst().endsWith("): if a save does not show, save the file again."),
                warnings.getFirst());
    }

    /** A root that does not exist, a source deleted since the last compile: nothing to read, nothing fails. */
    @Test
    void aMissingRootAndADeletedSourceAreIgnored() throws Exception {
        saved = compiling(sources, dir.resolve("src/main/absent"));
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.minusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(7));
        saved.compileStarts();
        saved.compileEnds();
        Files.delete(source);

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass), "the compiler plugin deletes the class of a source that is gone");
        assertEquals(List.of(), warnings);
    }

    /**
     * A file that goes away while its directory is read is one file less, and no warning. Here the classes are
     * written next to their sources, so that deleting the class of a source takes a file away from the directory
     * being read. Twelve sources: whatever the order of the directory, a class comes after its source.
     */
    @Test
    void aFileThatGoesAwayWhileTheSourcesAreReadIsNoFailure() throws Exception {
        saved = new SavedWhileCompiling(List.of(sources), sources, "recompile", info::add, warnings::add);
        List<Path> savedAgain = new ArrayList<>();
        for (int dish = 10; dish < 22; dish++) {
            savedAgain.add(file(sources.resolve("acme/Dish" + dish + ".java"), STARTED.minusSeconds(2)));
        }
        saved.compileStarts();
        for (Path source : savedAgain) {
            stamp(source, STARTED.plusSeconds(6));
            file(source.resolveSibling(source.getFileName().toString().replace(".java", ".class")),
                    STARTED.plusSeconds(7));
        }
        saved.compileEnds();

        assertEquals(savedAgain, saved.compileStarts());

        assertEquals(List.of(), warnings, "a class deleted before the walk came to it is not a failure");
    }

    /**
     * BUG-20261117-05. The build that precedes a dev session is a compile nobody noted: of its sources, only the time
     * it started is known. A source saved since, with a class that is not older, is one it may have read before the
     * save.
     */
    @Test
    void aSourceSavedSinceAnEarlierCompileStartedLosesItsClass() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.plusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(3));
        file(sources.resolve("acme/Tally.java"), STARTED.minusSeconds(1));
        Path read = file(classes.resolve("acme/Tally.class"), STARTED.plusSeconds(3));
        saved.anEarlierCompileStarted("the build", FileTime.from(STARTED));

        assertEquals(List.of(source), saved.compileStarts());

        assertFalse(Files.exists(itsClass), "saved since the build started, with a class that is not older");
        assertTrue(Files.exists(read), "saved before the build started: the build read that save");
        assertEquals(List.of("Menu.java was saved since the build started and its class is not older: compiling it"
                + " again."), info);
    }

    /** The times are compared in milliseconds, as the compiler plugin reads them: the very start counts. */
    @Test
    void aSourceSavedTheMillisecondAnEarlierCompileStartedCounts() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED);
        file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(3));
        saved.anEarlierCompileStarted("the build", FileTime.from(STARTED));

        assertEquals(List.of(source), saved.compileStarts());
    }

    /** A source newer than its class needs no help, whenever it was saved: the compiler plugin compiles it. */
    @Test
    void aSourceSavedAfterAnEarlierCompileWroteItsClassIsLeftToTheCompilerPlugin() throws Exception {
        file(sources.resolve("acme/Menu.java"), STARTED.plusSeconds(5));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(3));
        saved.anEarlierCompileStarted("the build", FileTime.from(STARTED));

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass));
        assertEquals(List.of(), info);
    }

    /**
     * The earlier compile counts once: the compile that starts notes the times of the sources, and the next one
     * compares with them. Else every recompile of the session would compile that source again.
     */
    @Test
    void theCompileAfterTheFirstOneForgetsTheEarlierCompile() throws Exception {
        Path source = file(sources.resolve("acme/Menu.java"), STARTED.plusSeconds(2));
        Path itsClass = file(classes.resolve("acme/Menu.class"), STARTED.plusSeconds(3));
        saved.anEarlierCompileStarted("the build", FileTime.from(STARTED));
        assertEquals(List.of(source), saved.compileStarts());
        file(itsClass, STARTED.plusSeconds(9));
        saved.compileEnds();
        info.clear();

        assertEquals(List.of(), saved.compileStarts());

        assertTrue(Files.exists(itsClass), "compiled from the save by the first compile");
        assertEquals(List.of(), info);
    }

    /** A failure names the earlier compile too. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFailureAfterAnEarlierCompileNamesIt() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads every directory");
        Path closed = file(sources.resolve("acme/closed/Menu.java"), STARTED.plusSeconds(2)).getParent();
        saved.anEarlierCompileStarted("the build", FileTime.from(STARTED));
        Files.setPosixFilePermissions(closed, Set.of());
        try {
            assertEquals(List.of(), saved.compileStarts());
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
        }

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("Cannot tell whether a source was saved since the build started"
                + " (java.nio.file.AccessDeniedException"), warnings.getFirst());
    }
}
