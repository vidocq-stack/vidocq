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
package io.vidocq.runtime.core;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Module fixtures shared by the application-layer tests. */
final class TestModules {

    private TestModules() {}

    /** An exploded explicit module: a module-info requiring {@code requires}, and one empty class. */
    static Path explodedModule(Path dir, String name, List<String> requires, String className)
            throws IOException {
        var root = dir.resolve(name);
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of(name), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (var required : requires) {
                        mb.requires(ModuleRequireInfo.of(ModuleDesc.of(required), 0, null));
                    }
                })));
        var classFile = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, ClassFile.of().build(ClassDesc.of(className),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
        return root;
    }

    /** An automatic module: a jar named {@code fileName}, without a descriptor, holding one empty class. */
    static Path automaticJar(Path dir, String fileName, String className) throws IOException {
        Path jar = dir.resolve(fileName);
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.zip.ZipEntry(className.replace('.', '/') + ".class"));
            out.write(ClassFile.of().build(ClassDesc.of(className),
                    cb -> cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)));
            out.closeEntry();
        }
        return jar;
    }
}
