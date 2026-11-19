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
package io.vidocq.runtime.arquillian;

import jakarta.servlet.Servlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a war's classes and lib jars are laid out for its JSP pages, before any page is compiled. */
class WarJspPagesTest {

    private static final String TAG_LIBRARY = "META-INF/acme.tld";
    private static final String CLASS = "com/acme/Page.class";

    /** Writes a jar at {@code jar} whose entries are {@code names}, each holding its own name. */
    private static Path jar(Path jar, String... names) throws IOException {
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : names) {
                out.putNextEntry(new JarEntry(name));
                if (!name.endsWith("/")) {
                    out.write(name.getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Path file(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, name);
    }

    @Test
    void aWarWithoutPagesHasNoPageLayer(@TempDir Path root, @TempDir Path work) throws IOException {
        file(root, "META-INF/resources/index.html");
        file(root, "META-INF/resources/WEB-INF/classes/hidden.jsp");
        jar(root.resolve("META-INF/resources/WEB-INF/lib/plain.jar"), CLASS, "META-INF/notes.txt");

        assertEquals(Optional.empty(), WarJspPages.compile(root, getClass().getClassLoader(), work));
    }

    @Test
    void theWarsClassesAreCopiedButThoseOfItsMetaInfAndWebInf(@TempDir Path root, @TempDir Path target)
            throws IOException {
        file(root, CLASS);
        file(root, "META-INF/versions/21/com/acme/Page.class");
        file(root, "WEB-INF/classes/com/acme/Page.class");
        file(root, "com/acme/readme.txt");

        WarJspPages.copyClasses(root, target);

        assertTrue(Files.isRegularFile(target.resolve(CLASS)));
        assertFalse(Files.exists(target.resolve("META-INF")));
        assertFalse(Files.exists(target.resolve("WEB-INF")));
        assertFalse(Files.exists(target.resolve("com/acme/readme.txt")));
    }

    @Test
    void aLibraryGivesItsClassesAndItsTagLibrariesInADirectoryOfItsOwn(@TempDir Path work) throws IOException {
        Path first = jar(work.resolve("lib/first.jar"), "com/", CLASS, "module-info.class",
                "META-INF/versions/21/com/acme/Other.class", "META-INF/MANIFEST.MF", TAG_LIBRARY, "../escape.class");
        Path second = jar(work.resolve("lib/second.jar"), TAG_LIBRARY);
        Path target = Files.createDirectories(work.resolve("classes"));

        WarJspPages.unpackLibraries(List.of(first, second), target);

        assertEquals(CLASS, Files.readString(target.resolve(CLASS)));
        assertTrue(Files.isRegularFile(target.resolve("META-INF/war-libraries/0000/" + TAG_LIBRARY)));
        assertTrue(Files.isRegularFile(target.resolve("META-INF/war-libraries/0001/" + TAG_LIBRARY)));
        assertFalse(Files.exists(target.resolve("module-info.class")), "the library's module descriptor");
        assertFalse(Files.exists(target.resolve("META-INF/versions")), "a versioned class");
        assertFalse(Files.exists(target.resolve("META-INF/MANIFEST.MF")), "anything else of META-INF");
        assertFalse(Files.exists(target.resolve(TAG_LIBRARY)), "a tag library at the root");
        assertFalse(Files.exists(work.resolve("escape.class")), "an entry outside the target");
    }

    @Test
    void thePagesCompileAgainstTheModularJarsOfTheirApis() throws Exception {
        Path servlet = Path.of(Servlet.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        List<Path> modulePath = WarJspPages.compileModulePath();

        assertTrue(modulePath.contains(servlet), modulePath.toString());
        for (Path jar : modulePath) {
            assertEquals(1, ModuleFinder.of(jar).findAll().size(), jar + " is a modular jar");
        }
    }
}
