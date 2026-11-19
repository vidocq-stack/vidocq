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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The default {@code classpath:db/migration} of an application that Vidocq boots in a module layer of its own, in a
 * real JVM, as {@code vidocq:dev} hands it over (a directory) and as the default packaged launcher does (a jar), both
 * through {@code -Dvidocq.app.path} (vidocq#96). There, the context class loader lists no directory: Flyway's own
 * scanner found nothing, logged one WARN, and the boot went on with an empty schema.
 */
class LayeredMigrationProcessTest {

    private static final Map<String, String> SCRIPTS = new TreeMap<>(Map.of(
            "db/migration/V1__create_gadget.sql", "CREATE TABLE gadget (id INT PRIMARY KEY);",
            "db/migration/V2__seed_gadget.sql", "INSERT INTO gadget (id) VALUES (1);"));

    @TempDir
    Path dir;

    @Test
    void aDirectoryInTheApplicationLayerIsMigrated() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.write(classes.resolve("module-info.class"), moduleInfo());
        for (var script : SCRIPTS.entrySet()) {
            Path file = classes.resolve(script.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, script.getValue());
        }

        String out = child(classes);

        assertTrue(out.contains("RESULT applied=2 version=2 nothingFound=false"), out);
        assertTrue(out.contains("ROWS 1"), out);
    }

    @Test
    void aJarInTheApplicationLayerIsMigrated() throws Exception {
        Path jar = dir.resolve("acme-app.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(file)) {
            zip.putNextEntry(new JarEntry("module-info.class"));
            zip.write(moduleInfo());
            for (var script : SCRIPTS.entrySet()) {
                zip.putNextEntry(new JarEntry(script.getKey()));
                zip.write(script.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }

        String out = child(jar);

        assertTrue(out.contains("RESULT applied=2 version=2 nothingFound=false"), out);
        assertTrue(out.contains("ROWS 1"), out);
    }

    /** The descriptor of {@code acme.app}, which does not open {@code db.migration}: the layer reads it anyway. */
    private static byte[] moduleInfo() {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("acme.app"), module -> module.requires(
                ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
    }

    /** Runs {@link LayeredMigrationProbe} with {@code application} as {@code -Dvidocq.app.path}; its output. */
    private String child(Path application) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvidocq.app.path=" + application);
        command.add("-cp");
        command.add(classPath());
        command.add(LayeredMigrationProbe.class.getName());
        command.add("jdbc:h2:mem:layered-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Path out = dir.resolve("out-" + System.nanoTime() + ".txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child JVM did not finish within 60 s");
        }
        String output = Files.readString(out, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    /** The test class path: Surefire's full one when it says it, else this JVM's. */
    private static String classPath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }
}
