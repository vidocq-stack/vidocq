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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The default changelog of an application that Vidocq boots in a module layer of its own, in a real JVM, as
 * {@code vidocq:dev} hands it over through {@code -Dvidocq.app.path} (vidocq#96): the master changelog is read by
 * name, and its {@code includeAll} directory is listed from the layer.
 */
class LayeredLiquibaseProcessTest {

    private static final String HEADER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
            """;

    private static final Map<String, String> FILES = Map.of(
            "db/changelog/db.changelog-master.xml", HEADER
                    + "    <includeAll path=\"db/changelog/changes/\"/>\n</databaseChangeLog>\n",
            "db/changelog/changes/001-gadget.xml", HEADER + """
                        <changeSet id="gadget" author="vidocq">
                            <createTable tableName="gadget">
                                <column name="id" type="INT"><constraints primaryKey="true"/></column>
                            </createTable>
                            <insert tableName="gadget"><column name="id" valueNumeric="1"/></insert>
                        </changeSet>
                    </databaseChangeLog>
                    """);

    @TempDir
    Path dir;

    @Test
    void aChangelogInTheApplicationLayerIsMigrated() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.write(classes.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of("acme.app"), module -> module.requires(
                        ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)))));
        for (var file : FILES.entrySet()) {
            Path target = classes.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }

        String out = child(classes);

        assertTrue(out.contains("RESULT applied=1 nothingFound=false"), out);
        assertTrue(out.contains("ROWS 1"), out);
    }

    /** Runs {@link LayeredLiquibaseProbe} with {@code application} as {@code -Dvidocq.app.path}; its output. */
    private String child(Path application) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvidocq.app.path=" + application);
        command.add("-cp");
        String surefire = System.getProperty("surefire.test.class.path");
        command.add(surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path"));
        command.add(LayeredLiquibaseProbe.class.getName());
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
}
