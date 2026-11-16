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
package io.vidocq.runtime.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vidocq:dev}, {@code vidocq:run} and the packaging goals start the application's {@code @VidocqMain} class when
 * {@code vidocq.mainClass} names none: a trampoline written by hand runs, with the {@code VidocqApp} it passes to
 * {@code Vidocq.run}, without a property to keep in step with it.
 */
class AnnotatedMainClassTest {

    @TempDir
    Path classes;

    private final List<String> logged = new ArrayList<>();

    /** The configured main class wins over the annotated one. */
    @Test
    void aConfiguredMainClassWins() throws Exception {
        write("com.acme.App", true);

        assertEquals("com.acme.Other", AnnotatedMainClass.resolve("com.acme.Other", classes.toFile(), logged::add));
        assertTrue(logged.isEmpty(), logged.toString());
    }

    /** With no configured main class, the one class annotated {@code @VidocqMain} is the main class, and it is said. */
    @Test
    void theAnnotatedClassIsTheMainClassWhenNoneIsConfigured() throws Exception {
        write("com.acme.App", true);
        write("com.acme.Greeter", false);

        assertEquals("com.acme.App", AnnotatedMainClass.resolve(null, classes.toFile(), logged::add));
        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.getFirst().contains("com.acme.App"), logged.getFirst());
        assertTrue(logged.getFirst().contains("vidocq.mainClass"), logged.getFirst());
    }

    /** The runtime's own main class, the default of {@code vidocq:package}, is not a choice of the application. */
    @Test
    void theRuntimeMainClassCountsAsNoneConfigured() throws Exception {
        write("com.acme.App", true);

        assertEquals("com.acme.App",
                AnnotatedMainClass.resolve(ApplicationMainClass.RUNTIME_MAIN_CLASS, classes.toFile(), logged::add));
    }

    /** Without an annotated class, nothing changes: the runtime boots the application. */
    @Test
    void noAnnotatedClassLeavesTheConfiguredValue() throws Exception {
        write("com.acme.Greeter", false);

        assertNull(AnnotatedMainClass.resolve(null, classes.toFile(), logged::add));
        assertEquals(ApplicationMainClass.RUNTIME_MAIN_CLASS,
                AnnotatedMainClass.resolve(ApplicationMainClass.RUNTIME_MAIN_CLASS, classes.toFile(), logged::add));
        assertTrue(logged.isEmpty(), logged.toString());
    }

    /** A module not compiled yet, or a goal given no output directory, has no annotated class. */
    @Test
    void aMissingOutputDirectoryHasNoAnnotatedClass() throws Exception {
        assertNull(AnnotatedMainClass.resolve(null, classes.resolve("absent").toFile(), logged::add));
        assertNull(AnnotatedMainClass.resolve(null, null, logged::add));
    }

    /** Two annotated classes are an error that names both and the property that settles it. */
    @Test
    void twoAnnotatedClassesAskForTheProperty() throws Exception {
        write("com.acme.App", true);
        write("com.acme.admin.AdminApp", true);

        MojoExecutionException error = assertThrows(MojoExecutionException.class,
                () -> AnnotatedMainClass.resolve(null, classes.toFile(), logged::add));

        assertTrue(error.getMessage().contains("com.acme.App"), error.getMessage());
        assertTrue(error.getMessage().contains("com.acme.admin.AdminApp"), error.getMessage());
        assertTrue(error.getMessage().contains("vidocq.mainClass"), error.getMessage());
    }

    /** A file that is not a class file is not read as one. */
    @Test
    void otherFilesAreIgnored() throws Exception {
        write("com.acme.App", true);
        Files.writeString(classes.resolve("application.properties"), "a=b");
        Files.write(classes.resolve("broken.class"), new byte[] {1, 2, 3});

        assertEquals("com.acme.App", AnnotatedMainClass.resolve(null, classes.toFile(), logged::add));
    }

    private void write(String name, boolean vidocqMain) throws Exception {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(name), builder -> {
            if (vidocqMain) {
                builder.with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(ClassDesc.of("io.vidocq.runtime.spi.VidocqMain"))));
            }
        });
        Path file = classes.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
