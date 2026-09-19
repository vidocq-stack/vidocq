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
package io.vidocq.runtime.spi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where an extension finds the application's files when no class loader lists them (vidocq#96). */
class ApplicationLayerTest {

    @TempDir
    Path dir;

    @Test
    void listsADirectoryOfEveryModuleOfTheLayerFromADirectoryAndFromAJar() throws Exception {
        Path exploded = explodedModule("acme.app", Map.of(
                "db/migration/V1__create.sql", "CREATE TABLE a (id INT);",
                "db/migration/nested/V2__nested.sql", "CREATE TABLE b (id INT);",
                "db/other/V9__elsewhere.sql", "CREATE TABLE c (id INT);"));
        Path jar = jarModule("acme.lib", Map.of("db/migration/V3__from_the_jar.sql", "CREATE TABLE d (id INT);"));
        ModuleLayer layer = layer(exploded, jar);

        assertEquals(List.of("db/migration/V1__create.sql", "db/migration/V3__from_the_jar.sql",
                "db/migration/nested/V2__nested.sql"), ApplicationLayer.list(layer, "db/migration"));
        assertEquals(ApplicationLayer.list(layer, "db/migration"), ApplicationLayer.list(layer, "/db/migration/"),
                "a leading or trailing slash names the same directory");
        assertEquals(List.of(), ApplicationLayer.list(layer, "db/nowhere"));
        assertTrue(ApplicationLayer.list(layer, "").contains("module-info.class"), "\"\" lists every file");
    }

    @Test
    void thereIsNoApplicationLayerWithoutARuntime() {
        assertTrue(ApplicationLayer.current().isEmpty(), "no runtime on the path implements ApplicationLayer");
    }

    // ---------------------------------------------------------------- fixtures

    private static ModuleLayer layer(Path... archives) {
        ModuleFinder finder = ModuleFinder.of(archives);
        Set<String> roots = new java.util.HashSet<>();
        finder.findAll().forEach(reference -> roots.add(reference.descriptor().name()));
        var configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), roots);
        // one loader per module: two modules of one loader cannot share the package db.migration
        return ModuleLayer.boot().defineModulesWithManyLoaders(configuration, ClassLoader.getSystemClassLoader());
    }

    private Path explodedModule(String name, Map<String, String> files) throws Exception {
        Path root = Files.createDirectories(dir.resolve(name));
        Files.write(root.resolve("module-info.class"), moduleInfo(name));
        for (var file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return root;
    }

    private Path jarModule(String name, Map<String, String> files) throws Exception {
        Path jar = dir.resolve(name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("module-info.class"));
            zip.write(moduleInfo(name));
            for (var file : files.entrySet()) {
                zip.putNextEntry(new JarEntry(file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return jar;
    }

    private static byte[] moduleInfo(String name) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(name), module -> module.requires(
                ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
    }
}
