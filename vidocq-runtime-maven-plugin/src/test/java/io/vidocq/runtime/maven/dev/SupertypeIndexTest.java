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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupertypeIndexTest {

    /** A stand-in of the Servlet API, as a dependency jar. */
    static Path servletApi(Path dir) {
        return HotSwapFixtures.jar(dir, "servlet-api", Map.of(
                "jakarta/servlet/ServletConfig.java", "package jakarta.servlet; public interface ServletConfig {}",
                "jakarta/servlet/Servlet.java",
                "package jakarta.servlet; public interface Servlet { void init(ServletConfig c); }",
                "jakarta/servlet/GenericServlet.java", """
                        package jakarta.servlet;
                        public abstract class GenericServlet implements Servlet {
                            public void init(ServletConfig c) { init(); }
                            public void init() {}
                        }
                        """), List.of());
    }

    private final List<String> warnings = new ArrayList<>();

    @Test
    void walksApplicationThenJars(@TempDir Path dir) {
        Path api = servletApi(dir);
        Path classes = HotSwapFixtures.compile(dir, "app", Map.of(
                "app/Base.java", "package app; public abstract class Base extends jakarta.servlet.GenericServlet {}",
                "app/Hello.java", "package app; public class Hello extends Base {}"), List.of(api));

        var index = SupertypeIndex.of(ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY), List.of(api),
                warnings::add);

        assertTrue(index.isA("app.Hello", "jakarta.servlet.Servlet"));
        assertTrue(index.isA("app.Hello", "jakarta.servlet.GenericServlet"));
        assertTrue(index.isA("app.Hello", "app.Hello"));
        assertFalse(index.isA("app.Hello", "jakarta.servlet.Filter"));
        assertFalse(index.unresolved("app.Hello"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void readsJdkClasses(@TempDir Path dir) {
        Path classes = HotSwapFixtures.compile(dir, "app", Map.of(
                "app/Tick.java", "package app; class Tick extends java.util.TimerTask { public void run() {} }"));

        var index = SupertypeIndex.of(ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY), List.of(),
                warnings::add);

        assertTrue(index.isA("app.Tick", "java.lang.Runnable"));
        assertFalse(index.unresolved("app.Tick"));
    }

    @Test
    void aMissingSupertypeWarnsOnceAndIsUnresolved(@TempDir Path dir) {
        Path api = servletApi(dir);
        Path classes = HotSwapFixtures.compile(dir, "app", Map.of(
                "app/One.java", "package app; class One extends jakarta.servlet.GenericServlet {}",
                "app/Two.java", "package app; class Two extends jakarta.servlet.GenericServlet {}"), List.of(api));

        var index = SupertypeIndex.of(ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY), List.of(),
                warnings::add);

        assertFalse(index.isA("app.One", "jakarta.servlet.Servlet"));
        assertFalse(index.isA("app.Two", "jakarta.servlet.Servlet"));
        assertTrue(index.unresolved("app.One"));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("jakarta.servlet.GenericServlet"), warnings::toString);
    }
}
