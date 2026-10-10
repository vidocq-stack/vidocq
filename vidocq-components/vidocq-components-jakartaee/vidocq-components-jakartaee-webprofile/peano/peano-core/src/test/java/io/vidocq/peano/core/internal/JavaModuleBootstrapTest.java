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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import jakarta.el.ExpressionFactory;
import io.vidocq.peano.spi.AccessorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaModuleBootstrapTest {
    @TempDir
    Path temporary;

    @Test
    void loadsAccessorTablesFromAnIndependentClosedConsumerModule() throws Exception {
        Path api = Path.of(ExpressionFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path core = Path.of(PeanoExpressionFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path spi = Path.of(AccessorProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path source = temporary.resolve("accessor-source");
        copyResource("/accessor-bootstrap/module-info.java", source.resolve("module-info.java"));
        copyResource("/accessor-bootstrap/Main.java", source.resolve("Main.java"));
        copyResource("/accessor-bootstrap/PublicItem.java", source.resolve("PublicItem.java"));
        Path classes = temporary.resolve("accessor-classes");
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        assertEquals(0, run(javaBin.resolve("javac").toString(), "--module-path",
                String.join(File.pathSeparator, api.toString(), spi.toString()),
                "-d", classes.toString(), source.resolve("module-info.java").toString(),
                source.resolve("Main.java").toString(), source.resolve("PublicItem.java").toString()));
        assertEquals(0, run(javaBin.resolve("java").toString(), "--module-path",
                String.join(File.pathSeparator, api.toString(), spi.toString(), core.toString(), classes.toString()),
                "--module", "peano.accessorconsumer/io.vidocq.peano.accessorconsumer.Main"));
        assertTrue(Files.readString(temporary.resolve("process.log")).contains("PEANO_ACCESSORS_OK"));
    }

    @Test
    void discoversTheProviderFromAnIndependentNamedConsumer() throws Exception {
        Path api = Path.of(ExpressionFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path core = Path.of(PeanoExpressionFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path spi = Path.of(AccessorProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path source = temporary.resolve("source");
        copyResource("/bootstrap/module-info.java", source.resolve("module-info.java"));
        copyResource("/bootstrap/io/vidocq/peano/bootstrap/Main.java", source.resolve("Main.java"));
        copyResource("/bootstrap/io/vidocq/peano/bootstrap/hidden/HiddenBean.java", source.resolve("HiddenBean.java"));
        copyResource("/bootstrap/io/vidocq/peano/bootstrap/exported/PublicRecord.java", source.resolve("PublicRecord.java"));
        Path classes = temporary.resolve("classes");
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        assertEquals(0, run(javaBin.resolve("javac").toString(), "--module-path", api.toString(),
                "-d", classes.toString(), source.resolve("module-info.java").toString(),
                source.resolve("Main.java").toString(), source.resolve("HiddenBean.java").toString(),
                source.resolve("PublicRecord.java").toString()));
        // No classpath and no explicit root for Peano: service binding must resolve it.
        assertEquals(0, run(javaBin.resolve("java").toString(), "--module-path",
                String.join(File.pathSeparator, api.toString(), spi.toString(), core.toString(), classes.toString()),
                "--module", "peano.bootstrap/io.vidocq.peano.bootstrap.Main"));
        assertTrue(Files.readString(temporary.resolve("process.log")).contains("PEANO_MODULE_OK"));
    }

    private void copyResource(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (var input = getClass().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing resource " + resource);
            Files.copy(input, target);
        }
    }

    private int run(String... command) throws Exception {
        Path output = temporary.resolve("process.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "Module bootstrap process timed out");
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(output);
            } catch (IOException error) {
                return error.toString();
            }
        });
        return process.exitValue();
    }
}

