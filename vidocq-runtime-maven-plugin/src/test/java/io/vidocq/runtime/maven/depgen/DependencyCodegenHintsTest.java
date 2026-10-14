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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCodegenHintsTest {

    @TempDir
    Path dir;

    private static Properties props(String... kv) {
        var p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void parsesEveryKey() {
        var hints = DependencyCodegenHints.parse(props(
                "brick", "foy",
                "resources", " META-INF/web-fragment.xml , META-INF/web.xml,, ",
                "class-bytes", "jakarta/servlet/annotation/Web",
                "markers", "META-INF/vidocq/foy-processed",
                "merge", "META-INF/vidocq/foy.index"), "foy-bundle");
        assertEquals("foy", hints.brick());
        assertEquals(List.of("META-INF/web-fragment.xml", "META-INF/web.xml"), hints.resources());
        assertEquals(List.of("jakarta/servlet/annotation/Web"), hints.classBytes());
        assertEquals(List.of("META-INF/vidocq/foy-processed"), hints.markers());
        assertEquals(List.of("META-INF/vidocq/foy.index"), hints.merge());
    }

    @Test
    void missingBrickFails() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> DependencyCodegenHints.parse(props("resources", "a"), "bundle-x"));
        assertTrue(e.getMessage().contains("bundle-x"), e.getMessage());
        assertTrue(e.getMessage().contains("brick"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> DependencyCodegenHints.parse(props("brick", "  ", "resources", "a"), "bundle-x"));
    }

    @Test
    void unknownKeyFailsNamingTheBundle() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> DependencyCodegenHints.parse(props("brick", "foy", "resources", "a", "bogus", "1"), "foy-bundle"));
        assertEquals("foy-bundle: unknown key 'bogus' in META-INF/vidocq/dependency-codegen.properties "
                + "(allowed: brick, resources, class-bytes, markers, merge)", e.getMessage());
    }

    @Test
    void noHintFails() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> DependencyCodegenHints.parse(props("brick", "foy", "markers", "m"), "foy-bundle"));
        assertTrue(e.getMessage().contains("foy-bundle"), e.getMessage());
        assertTrue(e.getMessage().contains("resources") && e.getMessage().contains("class-bytes"), e.getMessage());
    }

    @Test
    void readsFromAJar() throws IOException {
        var jar = dir.resolve("bundle.jar");
        var w = new StringWriter();
        props("brick", "foy", "resources", "META-INF/web-fragment.xml").store(w, null);
        try (OutputStream os = Files.newOutputStream(jar); var out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry(DependencyCodegenHints.PATH));
            out.write(w.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.closeEntry();
        }
        var hints = DependencyCodegenHints.read(jar).orElseThrow();
        assertEquals("foy", hints.brick());
        assertEquals(List.of("META-INF/web-fragment.xml"), hints.resources());
    }

    @Test
    void readsFromADirectory() throws IOException {
        var file = dir.resolve(DependencyCodegenHints.PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "brick=foy\nclass-bytes=x/Y\n");
        assertEquals(List.of("x/Y"), DependencyCodegenHints.read(dir).orElseThrow().classBytes());
    }

    @Test
    void aBundleWithoutTheFileHasNone() throws IOException {
        var jar = dir.resolve("plain.jar");
        try (OutputStream os = Files.newOutputStream(jar); var out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry("a.txt"));
            out.closeEntry();
        }
        assertTrue(DependencyCodegenHints.read(jar).isEmpty());
        assertTrue(DependencyCodegenHints.read(dir).isEmpty());
    }
}
