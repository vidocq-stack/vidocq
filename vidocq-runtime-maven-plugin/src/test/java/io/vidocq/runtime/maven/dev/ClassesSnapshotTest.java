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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassesSnapshotTest {

    @Test
    void everyFileOfEveryRootIsListed(@TempDir Path dir) throws IOException {
        Path classes = HotSwapFixtures.compile(dir, "app", Map.of(
                "app/A.java", "package app; class A {}",
                "app/B.java", "package app; class B { class Inner {} }"));
        Files.writeString(classes.resolve("application.properties"), "a=1");
        Path other = HotSwapFixtures.compile(dir, "lib", Map.of("lib/C.java", "package lib; class C {}"));

        var snapshot = ClassesSnapshot.take(List.of(classes, other, dir.resolve("absent")), ClassesSnapshot.EMPTY);

        assertEquals(List.of("app/A.class", "app/B$Inner.class", "app/B.class", "application.properties"),
                List.copyOf(snapshot.byRoot().get(classes).keySet()));
        assertEquals(List.of("lib/C.class"), List.copyOf(snapshot.byRoot().get(other).keySet()));
        assertEquals("app.B$Inner",
                snapshot.byRoot().get(classes).get("app/B$Inner.class").fingerprint().orElseThrow().binaryName());
    }

    @Test
    void onlyMovedFilesAreParsedAgain(@TempDir Path dir) throws IOException {
        Path classes = HotSwapFixtures.compile(dir, "app", Map.of(
                "app/A.java", "package app; class A {}",
                "app/B.java", "package app; class B {}"));
        var parses = new AtomicInteger();
        Function<byte[], ClassFingerprint> counting = bytes -> {
            parses.incrementAndGet();
            return ClassFingerprint.of(bytes);
        };

        var first = ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY, counting);
        assertEquals(2, parses.get());

        var unchanged = ClassesSnapshot.take(List.of(classes), first, counting);
        assertEquals(2, parses.get());
        assertEquals(first.byRoot(), unchanged.byRoot());

        Path b = classes.resolve("app/B.class");
        Files.write(b, HotSwapFixtures.bytes(HotSwapFixtures.compile(dir, "v2",
                Map.of("app/B.java", "package app; class B { int x; }")), "app.B"));
        Files.setLastModifiedTime(b, FileTime.fromMillis(Files.getLastModifiedTime(b).toMillis() + 2_000));
        var moved = ClassesSnapshot.take(List.of(classes), unchanged, counting);
        assertEquals(3, parses.get());
        assertNotEquals(unchanged.byRoot().get(classes).get("app/B.class").sha256(),
                moved.byRoot().get(classes).get("app/B.class").sha256());
    }

    @Test
    void resourcesHaveNoFingerprint(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(classes.resolve("application.properties"), "a=1");

        var entry = ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY)
                .byRoot().get(classes).get("application.properties");

        assertTrue(entry.fingerprint().isEmpty());
        assertEquals(64, entry.sha256().length());
    }

    @Test
    void aClassFileThatCannotBeParsedHasNoFingerprint(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(classes.resolve("Broken.class"), "not a class");

        var entry = ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY).byRoot().get(classes)
                .get("Broken.class");

        assertTrue(entry.fingerprint().isEmpty());
    }
}
