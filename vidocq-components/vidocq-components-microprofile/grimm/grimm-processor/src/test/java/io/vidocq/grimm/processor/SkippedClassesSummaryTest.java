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
package io.vidocq.grimm.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classes left to the run-time scan are said once per build, not once per class: each was an INFO line at every
 * build of an application, for a fallback that works (BUG-20261030-26 of vidocq). {@code -Agrimm.processor.verbose}
 * lists them, with the reason.
 */
class SkippedClassesSummaryTest {

    @TempDir
    Path workDir;

    private static final String TAGGED = """
            package app;

            @jakarta.ws.rs.Path("/%1$s")
            @org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "%1$s")
            public class %2$s {
                @jakarta.ws.rs.GET
                public String list() { return "%1$s"; }
            }
            """;

    private List<String> compile(List<String> options) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var src = Files.createDirectories(workDir.resolve("src/app"));
        Files.writeString(src.resolve("Books.java"), TAGGED.formatted("books", "Books"));
        Files.writeString(src.resolve("Loans.java"), TAGGED.formatted("loans", "Loans"));
        var classes = Files.createDirectories(workDir.resolve("classes"));
        var classPath = new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        ModuleLayer.boot().configuration().modules().forEach(module -> module.reference().location()
                .filter(uri -> "file".equals(uri.getScheme()))
                .ifPresent(uri -> classPath.add(Path.of(uri).toString())));
        var args = new ArrayList<>(List.of("-d", classes.toString(), "--release", "25", "-proc:full",
                "-classpath", String.join(File.pathSeparator, classPath)));
        args.addAll(options);
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, args, null,
                    fileManager.getJavaFileObjects(src.resolve("Books.java").toFile(), src.resolve("Loans.java").toFile()));
            task.setProcessors(List.of(new GrimmModelProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getMessage(Locale.ROOT).contains("[grimm-processor]"))
                .map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT))
                .toList();
    }

    @Test
    void theClassesLeftToTheRunTimeScanAreSaidOnce() throws IOException {
        var messages = compile(List.of());

        assertEquals(1, messages.size(), messages::toString);
        assertTrue(messages.getFirst().contains("2 resource classes"), messages::toString);
        assertTrue(messages.getFirst().contains("-Agrimm.processor.verbose=true"), messages::toString);
    }

    @Test
    void theVerboseOptionListsThemWithTheirReason() throws IOException {
        var messages = compile(List.of("-Agrimm.processor.verbose=true"));

        assertTrue(messages.stream().anyMatch(m -> m.contains("app.Books") && m.contains("Tag")), messages::toString);
        assertTrue(messages.stream().anyMatch(m -> m.contains("app.Loans")), messages::toString);
    }
}
