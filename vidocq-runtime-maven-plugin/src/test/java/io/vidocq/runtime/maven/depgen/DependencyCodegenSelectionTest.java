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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCodegenSelectionTest {

    @TempDir
    Path dir;

    private static DependencyCodegenHints brick(String name, List<String> resources, List<String> classBytes,
                                                List<String> markers) {
        return new DependencyCodegenHints(name, resources, classBytes, markers, List.of());
    }

    private Path jar(String name, String... namesAndContents) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            entries.put(namesAndContents[i], namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        var path = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(path); var out = new JarOutputStream(os)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    @Test
    void aResourceHintMatches() throws IOException {
        var jar = jar("a.jar", "META-INF/web-fragment.xml", "<x/>");
        var foy = brick("foy", List.of("META-INF/web-fragment.xml"), List.of(), List.of());
        var d = DependencyCodegenSelection.decide(jar, List.of(foy));
        assertTrue(d.selected());
        assertEquals(jar, d.jar());
        assertEquals(List.of(new DependencyCodegenSelection.Match(foy, "resource META-INF/web-fragment.xml")),
                d.matches());
        assertFalse(d.signed());
    }

    @Test
    void aClassByteHintMatchesAndNamesTheClass() throws IOException {
        var jar = jar("a.jar", "com/acme/Boot.class", "Êþ..jakarta/servlet/annotation/WebServlet..");
        var foy = brick("foy", List.of(), List.of("jakarta/servlet/annotation/Web"), List.of());
        var d = DependencyCodegenSelection.decide(jar, List.of(foy));
        assertEquals("class bytes jakarta/servlet/annotation/Web in com/acme/Boot.class",
                d.matches().getFirst().reason());
    }

    @Test
    void aVersionedClassDoesNotMatch() throws IOException {
        var jar = jar("a.jar", "META-INF/versions/21/com/acme/Boot.class", "jakarta/servlet/annotation/Web");
        var foy = brick("foy", List.of(), List.of("jakarta/servlet/annotation/Web"), List.of());
        assertFalse(DependencyCodegenSelection.decide(jar, List.of(foy)).selected());
    }

    @Test
    void aMarkerSkipsTheJar() throws IOException {
        var jar = jar("a.jar", "META-INF/web-fragment.xml", "<x/>", "META-INF/vidocq/foy-processed", "");
        var foy = brick("foy", List.of("META-INF/web-fragment.xml"), List.of(), List.of("META-INF/vidocq/foy-processed"));
        var d = DependencyCodegenSelection.decide(jar, List.of(foy));
        assertFalse(d.selected());
        assertEquals(List.of(new DependencyCodegenSelection.Skip(foy, "META-INF/vidocq/foy-processed")), d.skips());
    }

    @Test
    void aJarNoBrickWantsIsNotSelected() throws IOException {
        var jar = jar("a.jar", "com/acme/Plain.class", "nothing");
        var foy = brick("foy", List.of("META-INF/web-fragment.xml"), List.of("x/Y"), List.of());
        var d = DependencyCodegenSelection.decide(jar, List.of(foy));
        assertFalse(d.selected());
        assertTrue(d.skips().isEmpty());
    }

    @Test
    void twoBricksCanMatchOneJar() throws IOException {
        var jar = jar("a.jar", "META-INF/web-fragment.xml", "<x/>", "com/acme/R.class", "jakarta/ws/rs/Path");
        var foy = brick("foy", List.of("META-INF/web-fragment.xml"), List.of(), List.of());
        var cassini = brick("cassini", List.of(), List.of("jakarta/ws/rs/Path"), List.of());
        var d = DependencyCodegenSelection.decide(jar, List.of(foy, cassini));
        assertEquals(2, d.matches().size());
        assertEquals("foy", d.matches().get(0).brick().brick());
        assertEquals("cassini", d.matches().get(1).brick().brick());
    }

    @Test
    void aSignedJarIsFlagged() throws IOException {
        var jar = jar("a.jar", "META-INF/web-fragment.xml", "<x/>", "META-INF/FOO.SF", "sig");
        var foy = brick("foy", List.of("META-INF/web-fragment.xml"), List.of(), List.of());
        assertTrue(DependencyCodegenSelection.decide(jar, List.of(foy)).signed());
    }

    @Test
    void aDirectoryIsNeverSelected() throws IOException {
        var file = dir.resolve("META-INF/web-fragment.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<x/>");
        var foy = brick("foy", List.of("META-INF/web-fragment.xml"), List.of(), List.of());
        var d = DependencyCodegenSelection.decide(dir, List.of(foy));
        assertFalse(d.selected());
        assertFalse(d.signed());
    }
}
