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
package io.vidocq.runtime.maven.depgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyCodegenCacheTest {

    @TempDir
    Path tmp;

    private static Brick brick(String name, Path... processorPath) {
        return new Brick(new DependencyCodegenHints(name, List.of(), List.of(), List.of(), List.of()),
                "g:" + name + ":1", List.of(processorPath));
    }

    private Path file(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void putThenGetGivesTheSameFiles() throws IOException {
        DependencyCodegenCache cache = new DependencyCodegenCache(tmp.resolve("cache"));
        var output = new DependencyCodegen.Output(
                Map.of("a/B.class", new byte[] {1, 2, 3}, "META-INF/services/x", "y".getBytes()),
                List.of("warn one", "warn two"));
        assertTrue(cache.get("k").isEmpty());
        cache.put("k", output);
        var got = cache.get("k").orElseThrow();
        assertEquals(output.files().keySet(), got.files().keySet());
        assertArrayEquals(new byte[] {1, 2, 3}, got.files().get("a/B.class"));
        assertEquals(List.of("warn one", "warn two"), got.warnings());
    }

    @Test
    void anEmptyResultIsCachedToo() throws IOException {
        DependencyCodegenCache cache = new DependencyCodegenCache(tmp.resolve("cache"));
        cache.put("k", new DependencyCodegen.Output(Map.of(), List.of()));
        var got = cache.get("k").orElseThrow();
        assertTrue(got.files().isEmpty());
        assertTrue(got.warnings().isEmpty());
    }

    @Test
    void aDamagedEntryIsIgnoredAndRewritten() throws IOException {
        Path dir = tmp.resolve("cache");
        DependencyCodegenCache cache = new DependencyCodegenCache(dir);
        cache.put("k", new DependencyCodegen.Output(Map.of("A.class", new byte[] {9}), List.of()));
        Files.delete(dir.resolve("k").resolve("complete"));
        assertTrue(cache.get("k").isEmpty());
        assertFalse(Files.exists(dir.resolve("k")));
        cache.put("k", new DependencyCodegen.Output(Map.of("B.class", new byte[] {8}), List.of()));
        assertEquals(java.util.Set.of("B.class"), cache.get("k").orElseThrow().files().keySet());
    }

    @Test
    void theKeyChangesWithTheJarTheProcessorsTheRelease() throws IOException {
        Path jar = file("a.jar", "jar-1");
        Path cp = file("p.jar", "proc-1");
        String base = DependencyCodegenCache.key(jar, List.of(brick("b", cp)), 25);
        assertEquals(base, DependencyCodegenCache.key(jar, List.of(brick("b", cp)), 25));
        assertNotEquals(base, DependencyCodegenCache.key(jar, List.of(brick("b", cp)), 24));
        assertNotEquals(base, DependencyCodegenCache.key(jar, List.of(brick("c", cp)), 25));
        Path other = file("o.jar", "jar-2");
        assertNotEquals(base, DependencyCodegenCache.key(other, List.of(brick("b", cp)), 25));
        Files.writeString(cp, "proc-2");
        assertNotEquals(base, DependencyCodegenCache.key(jar, List.of(brick("b", cp)), 25));
    }

    @Test
    void theKeyIgnoresTheOrderOfBricks() throws IOException {
        Path jar = file("a.jar", "j");
        Path cp = file("p.jar", "p");
        assertEquals(DependencyCodegenCache.key(jar, List.of(brick("a", cp), brick("b", cp)), 25),
                DependencyCodegenCache.key(jar, List.of(brick("b", cp), brick("a", cp)), 25));
    }

    @Test
    void directoryPrecedence() {
        assertEquals(Path.of("/prop"),
                DependencyCodegenCache.directory("/prop", Map.of("XDG_CACHE_HOME", "/xdg"), "/home/u"));
        assertEquals(Path.of("/xdg/vidocq/depgen"),
                DependencyCodegenCache.directory(null, Map.of("XDG_CACHE_HOME", "/xdg"), "/home/u"));
        assertEquals(Path.of("/home/u/.cache/vidocq/depgen"),
                DependencyCodegenCache.directory("", Map.of(), "/home/u"));
    }

    @Test
    void putKeepsACompleteEntry() throws IOException {
        DependencyCodegenCache cache = new DependencyCodegenCache(tmp.resolve("cache"));
        cache.put("k", new DependencyCodegen.Output(Map.of("A.class", new byte[] {1}), List.of()));
        cache.put("k", new DependencyCodegen.Output(Map.of("B.class", new byte[] {2}), List.of()));
        assertEquals(java.util.Set.of("A.class"), cache.get("k").orElseThrow().files().keySet());
        try (var siblings = Files.list(tmp.resolve("cache"))) {
            assertEquals(1, siblings.count());
        }
    }

    @Test
    void aVanishingEntryIsAMiss() throws IOException {
        Path dir = tmp.resolve("cache");
        new DependencyCodegenCache(dir)
                .put("k", new DependencyCodegen.Output(Map.of("A.class", new byte[] {1}), List.of()));
        var racing = new DependencyCodegenCache(dir, file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertTrue(racing.get("k").isEmpty());
    }

    @Test
    void anEscapingFileNameIsRejected() {
        DependencyCodegenCache cache = new DependencyCodegenCache(tmp.resolve("cache"));
        var output = new DependencyCodegen.Output(Map.of("../evil", new byte[] {1}), List.of());
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> cache.put("k", output));
        assertFalse(Files.exists(tmp.resolve("evil")));
    }

    @Test
    void putSweepsOldTemporaryDirectories() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("cache"));
        Path old = Files.createDirectories(dir.resolve("abc.tmp123"));
        Files.writeString(old.resolve("x"), "x");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));
        Path fresh = Files.createDirectories(dir.resolve("def.tmp456"));
        new DependencyCodegenCache(dir).put("k", new DependencyCodegen.Output(Map.of(), List.of()));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }
}
