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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * vidocq#228: a class is attributed to the jar its class file resolves to through the application's
 * loader, so the components of a web fragment an absolute ordering excludes stay out, even when the
 * class itself was loaded from elsewhere (the TCK harness loads classes parent-first from the TCK jar,
 * and resources from the war's own jars first).
 */
class ComponentSourcesTest {

    @TempDir
    Path tmp;

    private static final String CLASS_FILE = ComponentSourcesTest.class.getName().replace('.', '/') + ".class";

    /** A loader that finds its own resources first and loads classes parent-first, as the harness does. */
    private static ClassLoader resourcesFirst(URL root) {
        return new URLClassLoader(new URL[] {root}, ComponentSourcesTest.class.getClassLoader()) {
            @Override
            public URL getResource(String name) {
                URL local = findResource(name);
                return local != null ? local : super.getResource(name);
            }
        };
    }

    private Path jarWithThisClassFile() throws IOException {
        Path jar = tmp.resolve("fragment-7.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar));
             var in = ComponentSourcesTest.class.getClassLoader().getResourceAsStream(CLASS_FILE)) {
            out.putNextEntry(new JarEntry(CLASS_FILE));
            in.transferTo(out);
        }
        return jar;
    }

    @Test
    void a_class_is_attributed_to_the_jar_its_class_file_resolves_to() throws IOException {
        Path jar = jarWithThisClassFile();

        URL source = ComponentSources.classFile(resourcesFirst(jar.toUri().toURL()), ComponentSourcesTest.class);

        assertEquals("jar:" + jar.toUri().toURL() + "!/", source.toString());
    }

    @Test
    void a_class_in_a_directory_is_attributed_to_the_directory() throws IOException {
        Path root = tmp.resolve("classes");
        Path file = root.resolve(CLASS_FILE);
        Files.createDirectories(file.getParent());
        try (var in = ComponentSourcesTest.class.getClassLoader().getResourceAsStream(CLASS_FILE)) {
            Files.copy(in, file);
        }

        URL source = ComponentSources.classFile(resourcesFirst(root.toUri().toURL()), ComponentSourcesTest.class);

        assertEquals(root.toUri().toURL().toString(), source.toString());
    }

    @Test
    void without_its_class_file_a_class_keeps_its_code_source() {
        ClassLoader empty = new ClassLoader(null) {};

        URL source = ComponentSources.classFile(empty, ComponentSourcesTest.class);

        assertEquals(ComponentSourcesTest.class.getProtectionDomain().getCodeSource().getLocation(), source);
    }
}
