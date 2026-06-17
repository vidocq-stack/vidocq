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
package io.vidocq.runtime.codegen.commons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles a sample NAMED module (with a real {@code module-info.java}) under {@link
 * RequirementCheckTestProcessor}, which requires {@code requires java.sql} + an unqualified
 * {@code opens db.migration}. Asserts the compile succeeds iff both directives are declared, and that
 * a missing directive produces an actionable, copy-pasteable error.
 */
class ModuleInfoRequirementsTest {

    @TempDir
    Path tempDir;

    @Test
    void compilesWhenAllRequiredDirectivesPresent() throws Exception {
        Result r = compile("requires java.sql; opens db.migration;", true);
        assertTrue(r.success(), "expected success, diagnostics:\n" + r.messages());
    }

    @Test
    void failsWithActionableErrorWhenRequiresMissing() throws Exception {
        // declares the opens but NOT requires java.sql
        Result r = compile("opens db.migration;", true);
        assertFalse(r.success(), "compile must fail when a required directive is missing");
        assertTrue(r.messages().contains("add  requires java.sql;"),
                "diagnostic must name the exact directive to add; got:\n" + r.messages());
        assertTrue(r.messages().contains("javax.sql.DataSource"),
                "diagnostic must carry the reason; got:\n" + r.messages());
        assertFalse(r.messages().contains("add  opens db.migration;"),
                "the satisfied opens must NOT be reported; got:\n" + r.messages());
    }

    @Test
    void failsWhenUnqualifiedOpensMissing() throws Exception {
        // declares requires java.sql but no db.migration package / opens at all
        Result r = compile("requires java.sql;", false);
        assertFalse(r.success(), "compile must fail when the required opens is missing");
        assertTrue(r.messages().contains("add  opens db.migration;"),
                "diagnostic must name the missing opens; got:\n" + r.messages());
        assertFalse(r.messages().contains("add  requires java.sql;"),
                "the satisfied requires must NOT be reported; got:\n" + r.messages());
    }

    @Test
    void qualifiedOpensDoesNotSatisfyAnUnqualifiedRequirement() throws Exception {
        // a qualified `opens ... to X` must NOT satisfy a required unqualified `opens`
        Result r = compile("requires java.sql; opens db.migration to java.base;", true);
        assertFalse(r.success(), "a qualified opens must not satisfy an unqualified requirement");
        assertTrue(r.messages().contains("add  opens db.migration;"),
                "diagnostic must still demand the unqualified opens; got:\n" + r.messages());
    }

    @Test
    void requirementRendersRequiresDirective() {
        assertEquals("requires java.sql;", Requirement.requires("java.sql", "x").directive());
    }

    @Test
    void requirementRendersQualifiedOpensDirective() {
        assertEquals("opens io.app to io.vidocq.vauban.core;",
                Requirement.opensTo("io.app", Set.of("io.vidocq.vauban.core"), "x").directive());
    }

    // --- harness ---

    private record Result(boolean success, String messages) {
    }

    private Result compile(String moduleInfoBody, boolean withMigrationPackage) throws Exception {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path moduleInfo = src.resolve("module-info.java");
        Files.writeString(moduleInfo, "module sample.app {\n    " + moduleInfoBody + "\n}\n");

        Path appDir = Files.createDirectories(src.resolve("sample"));
        Path app = appDir.resolve("App.java");
        Files.writeString(app, "package sample;\npublic class App {}\n");

        List<File> files = new ArrayList<>(List.of(moduleInfo.toFile(), app.toFile()));
        if (withMigrationPackage) {
            Path mig = Files.createDirectories(src.resolve("db").resolve("migration"));
            Path keep = mig.resolve("Keep.java");
            Files.writeString(keep, "package db.migration;\npublic class Keep {}\n");
            files.add(keep.toFile());
        }

        Path out = Files.createDirectories(tempDir.resolve("out"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        String cp = effectiveClasspath();
        List<String> options = List.of(
                "-classpath", cp,
                "-processorpath", cp,
                "-processor", RequirementCheckTestProcessor.class.getName(),
                "-d", out.toString());
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(files);
            boolean ok = compiler.getTask(null, fm, diags, options, null, units).call();
            String messages = diags.getDiagnostics().stream()
                    .map(d -> d.getMessage(null))
                    .collect(Collectors.joining("\n"));
            return new Result(ok, messages);
        }
    }

    /** java.class.path plus any URLClassLoader URLs, to work under classpath or module-path Surefire. */
    private String effectiveClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        String jcp = System.getProperty("java.class.path");
        if (jcp != null && !jcp.isBlank()) {
            entries.addAll(List.of(jcp.split(File.pathSeparator)));
        }
        for (ClassLoader cl = getClass().getClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader ucl) {
                for (URL u : ucl.getURLs()) {
                    try {
                        entries.add(new File(u.toURI()).getAbsolutePath());
                    } catch (Exception ignored) {
                        // non-file URL — skip
                    }
                }
            }
        }
        return String.join(File.pathSeparator, new ArrayList<>(entries));
    }
}
