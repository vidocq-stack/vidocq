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
package io.vidocq.runtime.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261031-10: under {@code vidocq dev}, a package the runtime reaches by reflection and the module does not
 * open is opened in {@code module-info.java} and in the compiled {@code module-info.class} at once, so the
 * application starts on the build that found it.
 */
class ModuleInfoOpenerTest {

    private static final String SOURCE = """
            module com.acme.demo {
                requires io.vidocq.runtime.core;
                opens com.acme.demo;
            }
            """;

    private static byte[] compiledModule(boolean withPackagesAttribute) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("com.acme.demo"), mb -> mb
                        .requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)
                        .opens(PackageDesc.of("com.acme.demo"), 0)),
                cb -> {
                    if (withPackagesAttribute) {
                        cb.with(ModulePackagesAttribute.ofNames(PackageDesc.of("com.acme.demo")));
                    }
                });
    }

    private static Set<String> opens(Path moduleInfoClass) throws IOException {
        try (InputStream in = Files.newInputStream(moduleInfoClass)) {
            return ModuleDescriptor.read(in).opens().stream().map(ModuleDescriptor.Opens::source)
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void opensThePackageInTheSourceAndInTheCompiledModule(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("module-info.java"), SOURCE);
        Path compiled = Files.write(dir.resolve("module-info.class"), compiledModule(false));

        List<String> written = ModuleInfoOpener.open(source, compiled, List.of("com.acme.demo.book"));

        assertEquals(List.of("opens com.acme.demo.book;"), written);
        String text = Files.readString(source);
        assertTrue(text.contains(ModuleInfoEditor.REFLECTION_HEADER
                + "\n    opens com.acme.demo.book; // vidocq:reflection\n}"), text);
        assertEquals(Set.of("com.acme.demo", "com.acme.demo.book"), opens(compiled));
    }

    @Test
    void aModulePackagesAttributeGetsThePackageToo(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("module-info.java"), SOURCE);
        Path compiled = Files.write(dir.resolve("module-info.class"), compiledModule(true));

        ModuleInfoOpener.open(source, compiled, List.of("com.acme.demo.book"));

        try (InputStream in = Files.newInputStream(compiled)) {
            // ModuleDescriptor.read refuses an opens of a package the ModulePackages attribute does not list.
            assertTrue(ModuleDescriptor.read(in).packages().contains("com.acme.demo.book"));
        }
    }

    @Test
    void aPackageAlreadyOpenedIsNotWrittenTwice(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("module-info.java"), SOURCE);
        Path compiled = Files.write(dir.resolve("module-info.class"), compiledModule(false));
        ModuleInfoOpener.open(source, compiled, List.of("com.acme.demo.book"));
        String once = Files.readString(source);

        List<String> again = ModuleInfoOpener.open(source, compiled, List.of("com.acme.demo.book", "com.acme.demo"));

        assertEquals(List.of(), again);
        assertEquals(once, Files.readString(source));
        assertEquals(Set.of("com.acme.demo", "com.acme.demo.book"), opens(compiled));
    }
}
