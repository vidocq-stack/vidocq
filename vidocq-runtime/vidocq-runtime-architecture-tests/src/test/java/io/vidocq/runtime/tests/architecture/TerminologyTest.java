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
package io.vidocq.runtime.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The project says "Java Modules", never the abbreviation of the Java Platform Module System: no main source of the
 * runtime (code, log lines, Javadoc, resources) carries it, since a log line or a goal description reaches every user
 * (BUG-20261123-41).
 */
class TerminologyTest {

    /** The abbreviation, assembled so that this file does not carry it. */
    private static final String FORBIDDEN = "J" + "PMS";

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", "node_modules", ".git");

    /** The {@code vidocq-runtime} folder: the parent of this module's folder, where Surefire runs. */
    private static Path runtimeRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }

    /** Every regular file under a {@code src/main} folder of the runtime. */
    private static List<Path> mainSources() throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(runtimeRoot(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                return name != null && SKIPPED_DIRECTORIES.contains(name.toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.toString().contains("/src/main/")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static boolean carriesTheAbbreviation(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(FORBIDDEN);
        } catch (MalformedInputException _) {
            return false; // a binary resource: no text to read
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theWalkFindsTheRuntimeSources() throws IOException {
        // Guards the rule below: a walk from the wrong folder would find nothing and pass.
        assertTrue(mainSources().stream().anyMatch(p -> p.endsWith(
                        Path.of("vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqRunMojo.java"))),
                () -> "the walk from " + runtimeRoot() + " does not reach the Maven plugin's sources");
    }

    @Test
    void noMainSourceUsesTheAbbreviation() throws IOException {
        List<String> offenders = mainSources().stream()
                .filter(TerminologyTest::carriesTheAbbreviation)
                .map(p -> runtimeRoot().relativize(p).toString())
                .sorted()
                .toList();
        assertEquals(List.of(), offenders, "say \"Java Modules\" or \"module path\" instead");
    }
}
