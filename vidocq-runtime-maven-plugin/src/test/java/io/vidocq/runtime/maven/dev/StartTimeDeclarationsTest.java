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

import io.vidocq.runtime.maven.depgen.DepgenFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartTimeDeclarationsTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void readsTheFileFromAJar(@TempDir Path dir) throws IOException {
        Path jar = declaring(dir, "cdi", "long-lived-annotations = a.ApplicationScoped, a.Singleton\n");

        var read = StartTimeDeclarations.read(List.of(jar), warnings::add);

        assertEquals(Set.of("a.ApplicationScoped", "a.Singleton"), read.longLivedAnnotations());
        assertEquals(List.of("cdi.jar"), read.sources());
        assertEquals(List.of(), warnings);
    }

    @Test
    void readsTheFileFromADirectory(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("classes");
        Path file = classes.resolve(StartTimeDeclarations.FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "start-annotations = app.Boot\n");

        var read = StartTimeDeclarations.read(List.of(classes), warnings::add);

        assertEquals(Set.of("app.Boot"), read.startAnnotations());
        assertEquals(List.of(classes.toString()), read.sources());
    }

    @Test
    void readsEveryKey(@TempDir Path dir) throws IOException {
        Path jar = declaring(dir, "all", """
                long-lived-annotations = a.Scoped
                long-lived-types = a.Servlet
                start-annotations = a.PostConstruct
                observer-annotations = a.Observes
                start-events = a.Startup
                start-callbacks = a.Servlet#init(La/ServletConfig;)V
                """);

        var read = StartTimeDeclarations.read(List.of(jar), warnings::add);

        assertEquals(Set.of("a.Scoped"), read.longLivedAnnotations());
        assertEquals(Set.of("a.Servlet"), read.longLivedTypes());
        assertEquals(Set.of("a.PostConstruct"), read.startAnnotations());
        assertEquals(Set.of("a.Observes"), read.observerAnnotations());
        assertEquals(Set.of("a.Startup"), read.startEvents());
        assertEquals(Set.of(new StartTimeDeclarations.Callback("a.Servlet", "init", "(La/ServletConfig;)V")),
                read.startCallbacks());
        assertEquals(List.of(), warnings);
    }

    @Test
    void mergesSeveralJars(@TempDir Path dir) throws IOException {
        Path cdi = declaring(dir, "cdi", "long-lived-annotations = a.ApplicationScoped\n");
        Path vaadin = declaring(dir, "vaadin", "long-lived-annotations = v.UIScoped\n");

        var read = StartTimeDeclarations.read(List.of(cdi, vaadin), warnings::add);

        assertEquals(Set.of("a.ApplicationScoped", "v.UIScoped"), read.longLivedAnnotations());
        assertEquals(List.of("cdi.jar", "vaadin.jar"), read.sources());
    }

    @Test
    void mergingTwoReadsKeepsBoth(@TempDir Path dir) throws IOException {
        var jars = StartTimeDeclarations.read(List.of(declaring(dir, "cdi", "start-events = a.Startup\n")),
                warnings::add);
        var app = StartTimeDeclarations.read(List.of(declaring(dir, "app", "start-events = app.Ready\n")),
                warnings::add);

        var merged = jars.merge(app);

        assertEquals(Set.of("a.Startup", "app.Ready"), merged.startEvents());
        assertEquals(List.of("cdi.jar", "app.jar"), merged.sources());
    }

    @Test
    void aJarWithoutTheFileAddsNothing(@TempDir Path dir) throws IOException {
        Path jar = DepgenFixtures.jar(dir, "plain", Map.of(), List.of(), Map.of("README", "nothing"));

        var read = StartTimeDeclarations.read(List.of(jar, dir.resolve("missing-classes")), warnings::add);

        assertTrue(read.isEmpty());
        assertEquals(List.of(), read.sources());
        assertEquals(List.of(), warnings);
    }

    @Test
    void valuesAreCommaSeparatedAndTrimmed(@TempDir Path dir) throws IOException {
        Path jar = declaring(dir, "web", """
                # Servlet's long-lived types
                long-lived-types = a.Servlet ,  a.Filter, \\
                    a.ServletContextListener,
                """);

        var read = StartTimeDeclarations.read(List.of(jar), warnings::add);

        assertEquals(Set.of("a.Servlet", "a.Filter", "a.ServletContextListener"), read.longLivedTypes());
        assertEquals(List.of(), warnings);
    }

    @Test
    void anUnknownKeyWarnsNamingTheJarAndTheKey(@TempDir Path dir) throws IOException {
        Path jar = declaring(dir, "typo", """
                long-lived-annotation = a.ApplicationScoped
                start-events = a.Startup
                """);

        var read = StartTimeDeclarations.read(List.of(jar), warnings::add);

        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("typo.jar"), warnings::toString);
        assertTrue(warnings.getFirst().contains("long-lived-annotation"), warnings::toString);
        assertEquals(Set.of("a.Startup"), read.startEvents());
        assertEquals(Set.of(), read.longLivedAnnotations());
    }

    @Test
    void aMalformedCallbackWarnsAndIsSkipped(@TempDir Path dir) throws IOException {
        Path jar = declaring(dir, "web",
                "start-callbacks = a.Servlet#init, a.Servlet#init(Lbad)V, a.Filter#init(La/FilterConfig;)V\n");

        var read = StartTimeDeclarations.read(List.of(jar), warnings::add);

        assertEquals(Set.of(new StartTimeDeclarations.Callback("a.Filter", "init", "(La/FilterConfig;)V")),
                read.startCallbacks());
        assertEquals(2, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).contains("web.jar") && warnings.get(0).contains("a.Servlet#init"),
                warnings::toString);
        assertTrue(warnings.get(1).contains("web.jar") && warnings.get(1).contains("a.Servlet#init(Lbad)V"),
                warnings::toString);
    }

    @Test
    void aNameThatIsNotABinaryNameWarnsAndIsSkipped(@TempDir Path dir) throws IOException {
        Path jar = declaring(dir, "odd", "long-lived-annotations = a/ApplicationScoped, a.Singleton\n");

        var read = StartTimeDeclarations.read(List.of(jar), warnings::add);

        assertEquals(Set.of("a.Singleton"), read.longLivedAnnotations());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("odd.jar") && warnings.getFirst().contains("a/ApplicationScoped"),
                warnings::toString);
    }

    @Test
    void anUnreadableJarWarnsAndAddsNothing(@TempDir Path dir) throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.jar"), "not a zip");

        var read = StartTimeDeclarations.read(List.of(broken), warnings::add);

        assertTrue(read.isEmpty());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("broken.jar"), warnings::toString);
    }

    private static Path declaring(Path dir, String name, String content) throws IOException {
        return DepgenFixtures.jar(dir, name, Map.of(), List.of(), Map.of(StartTimeDeclarations.FILE, content));
    }
}
