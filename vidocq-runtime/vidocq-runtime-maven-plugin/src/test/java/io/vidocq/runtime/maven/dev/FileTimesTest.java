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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Vidocq/vidocq#651: one pass of {@link FileTimes} reports what changed since the previous pass, as the JDK's watcher
 * would have, without its 2 s polling interval. Each test runs passes directly: no clock, no thread.
 */
class FileTimesTest {

    private static final SourceWatcher.Change NOTHING = new SourceWatcher.Change(false, false);
    private static final SourceWatcher.Change MAIN = new SourceWatcher.Change(true, false);
    private static final SourceWatcher.Change TEST = new SourceWatcher.Change(false, true);

    @TempDir
    Path tmp;
    private Path main;
    private Path test;
    private Path hello;

    @BeforeEach
    void sources() throws IOException {
        main = Files.createDirectories(tmp.resolve("src/main/java"));
        test = Files.createDirectories(tmp.resolve("src/test/java"));
        hello = Files.writeString(Files.createDirectories(main.resolve("acme")).resolve("Hello.java"), "class Hello {}");
        Files.writeString(test.resolve("HelloTest.java"), "class HelloTest {}");
    }

    private FileTimes times() {
        return new FileTimes(List.of(main), List.of(test));
    }

    @Test
    void aPassWithNoSaveReportsNothing() {
        FileTimes times = times();

        assertEquals(NOTHING, times.pass());
        assertEquals(NOTHING, times.pass());
    }

    @Test
    void aNewModificationTimeIsAChangeOnce() throws IOException {
        FileTimes times = times();
        later(hello);

        assertEquals(MAIN, times.pass());
        assertEquals(NOTHING, times.pass(), "the same time again is no change");
    }

    /** A save within the resolution of the file times still changes the size. */
    @Test
    void aNewSizeAtTheSameTimeIsAChange() throws IOException {
        FileTimes times = times();
        FileTime time = Files.getLastModifiedTime(hello);
        Files.writeString(hello, "class Hello { /* a longer body */ }");
        Files.setLastModifiedTime(hello, time);

        assertEquals(MAIN, times.pass());
    }

    @Test
    void aSaveUnderATestRootIsATestChange() throws IOException {
        FileTimes times = times();
        later(test.resolve("HelloTest.java"));

        assertEquals(TEST, times.pass());
    }

    @Test
    void aFileOfANewPackageIsAChange() throws IOException {
        FileTimes times = times();
        Files.writeString(Files.createDirectories(main.resolve("acme/book")).resolve("Book.java"), "class Book {}");

        assertEquals(MAIN, times.pass());
    }

    @Test
    void aDeletedFileIsAChange() throws IOException {
        FileTimes times = times();
        Files.delete(hello);

        assertEquals(MAIN, times.pass());
        assertEquals(NOTHING, times.pass());
    }

    @Test
    void aFileOfAnotherExtensionIsNoChange() throws IOException {
        Path log = Files.writeString(main.resolve("build.log"), "noise");
        FileTimes times = times();
        later(log);
        Files.writeString(main.resolve("logo.png"), "png");

        assertEquals(NOTHING, times.pass());
    }

    /** #138: a root that does not exist yet, such as a first src/test/java, is read once it appears. */
    @Test
    void aRootCreatedLaterIsRead() throws IOException {
        Path later = tmp.resolve("src/it/java");
        FileTimes times = new FileTimes(List.of(main), List.of(later));
        assertEquals(NOTHING, times.pass());

        Files.writeString(Files.createDirectories(later.resolve("a")).resolve("ItTest.java"), "class ItTest {}");

        assertEquals(TEST, times.pass());
    }

    /** A time two seconds after the current one: a save every file system tells from the previous one. */
    private static void later(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000));
    }
}
