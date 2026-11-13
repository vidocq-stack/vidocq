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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles a sample {@code @VidocqDataSources}-annotated source WITH the processor on the
 * annotation-processor path (in-process via {@link JavaCompiler}) and asserts that one
 * {@code @Named} DataSource holder source is generated per declared name.
 */
class VidocqDataSourcesProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesNamedHolderPerAnnotationValue() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path gen = Files.createDirectories(tempDir.resolve("gen"));
        Path src = tempDir.resolve("App.java");
        Files.writeString(src, """
                package app;
                import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.VidocqDataSources;
                @VidocqDataSources({"analytics", "audit"})
                public class App {}
                """);

        boolean ok = compileWithProcessor(src.toFile(), classes.toFile(), gen.toFile());
        assertTrue(ok, "compilation with the VidocqDataSources processor failed");

        Path analytics = gen.resolve("app/_analytics$DataSource.java");
        Path audit = gen.resolve("app/_audit$DataSource.java");
        assertTrue(Files.exists(analytics), "missing generated holder: " + analytics);
        assertTrue(Files.exists(audit), "missing generated holder: " + audit);

        String body = Files.readString(analytics);
        assertTrue(body.contains("@Named(\"analytics\")"), body);
        assertTrue(body.contains("@Singleton"), body);
        // Marker qualifier keeps the named holder out of the @Default candidate set.
        assertTrue(body.contains("io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.ManagedDataSource"),
                body);
        assertTrue(body.contains(
                "extends io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.AbstractNamedDataSourceHolder"),
                body);
        // DataSource declared as a direct interface so the Vauban indexer sees the bean type.
        assertTrue(body.contains("implements javax.sql.DataSource"), body);
        // NOT final: a managed bean must stay subclassable so Vauban can wrap it for interception
        // (a final bean fails the container-build proxyability check when interceptors are present).
        assertFalse(body.contains("final class"), "generated holder must not be final:\n" + body);
        assertTrue(body.contains("super(\"analytics\")"), body);
    }

    /** BUG-20261022-01: the annotation on a module declaration used to crash javac with a NullPointerException. */
    @Test
    void generatesHolderWhenTheAnnotationIsOnTheModuleDeclaration() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("mclasses"));
        Path gen = Files.createDirectories(tempDir.resolve("mgen"));
        Path moduleInfo = tempDir.resolve("module-info.java");
        Files.writeString(moduleInfo, """
                @io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.VidocqDataSources("audit")
                module com.example.app {
                    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
                    requires java.sql;
                    requires jakarta.cdi;
                    requires jakarta.inject;
                }
                """);
        Path app = Files.createDirectories(tempDir.resolve("app")).resolve("App.java");
        Files.writeString(app, """
                package com.example.app;
                public class App {}
                """);

        List<String> messages = new ArrayList<>();
        boolean ok = compileModule(List.of(moduleInfo.toFile(), app.toFile()), classes.toFile(), gen.toFile(), messages);
        assertTrue(ok, "compilation of an annotated module failed: " + messages);

        Path audit = gen.resolve("com/example/app/_audit$DataSource.java");
        assertTrue(Files.exists(audit), "missing generated holder: " + audit);
    }

    @Test
    void refusesAModuleWithoutAnyPackageInsteadOfCrashing() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("eclasses"));
        Path gen = Files.createDirectories(tempDir.resolve("egen"));
        Path moduleInfo = tempDir.resolve("module-info.java");
        Files.writeString(moduleInfo, """
                @io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.VidocqDataSources("audit")
                module com.example.empty {
                    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
                }
                """);

        List<String> messages = new ArrayList<>();
        boolean ok = compileModule(List.of(moduleInfo.toFile()), classes.toFile(), gen.toFile(), messages);
        assertFalse(ok, "an annotated module with no package has nowhere to put a holder");
        assertTrue(messages.stream().anyMatch(m -> m.contains("@VidocqDataSources") && m.contains("package")),
                "expected a diagnostic naming the annotation and the package: " + messages);
        assertFalse(messages.stream().anyMatch(m -> m.contains("NullPointerException")), messages.toString());
    }

    /**
     * BUG-20261113-01: a {@code vidocq.pool.<name>.url} of the build's {@code vidocq.properties} declares the pool
     * alone; javac never ran the processor of a module that carries no {@code @VidocqDataSources}.
     */
    @Test
    void theNamesOfTheBuildsPropertiesNeedNoAnnotation() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("pclasses"));
        Path gen = Files.createDirectories(tempDir.resolve("pgen"));
        Files.writeString(classes.resolve("vidocq.properties"), """
                vidocq.pool.url=jdbc:h2:mem:default
                vidocq.pool.archive.url=jdbc:h2:mem:archive
                vidocq.pool.archive.maxSize=2
                """);
        List<File> sources = plainModule("com.example.ledger");

        List<String> messages = new ArrayList<>();
        boolean ok = compileModule(sources, classes.toFile(), gen.toFile(), messages);
        assertTrue(ok, "compilation of a module without the annotation failed: " + messages);

        Path archive = gen.resolve("com/example/ledger/_archive$DataSource.java");
        assertTrue(Files.exists(archive), "missing generated holder: " + archive);
        try (var generated = Files.walk(gen)) {
            assertEquals(List.of(archive), generated.filter(Files::isRegularFile).toList(),
                    "one holder, for the named pool: the default pool has a bean of its own");
        }
    }

    @Test
    void aModuleWithoutTheAnnotationOrANamedPoolGetsNoHolder() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("nclasses"));
        Path gen = Files.createDirectories(tempDir.resolve("ngen"));
        Files.writeString(classes.resolve("vidocq.properties"), "vidocq.pool.url=jdbc:h2:mem:default\n");

        List<String> messages = new ArrayList<>();
        boolean ok = compileModule(plainModule("com.example.plain"), classes.toFile(), gen.toFile(), messages);

        assertTrue(ok, messages.toString());
        try (var generated = Files.walk(gen)) {
            assertEquals(List.of(), generated.filter(Files::isRegularFile).toList());
        }
    }

    /**
     * Only the compilation of a module declaration takes the names of the properties alone: the tests of a module,
     * compiled into it without its declaration, would otherwise generate a second bean per name.
     */
    @Test
    void aCompilationWithoutAModuleDeclarationNeedsTheAnnotation() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("tclasses"));
        Path gen = Files.createDirectories(tempDir.resolve("tgen"));
        Files.writeString(classes.resolve("vidocq.properties"), "vidocq.pool.archive.url=jdbc:h2:mem:archive\n");
        Path test = Files.createDirectories(tempDir.resolve("tsrc/com/example/ledger")).resolve("LedgerTest.java");
        Files.writeString(test, """
                package com.example.ledger;
                class LedgerTest {}
                """);

        assertTrue(compileWithProcessor(test.toFile(), classes.toFile(), gen.toFile()));
        try (var generated = Files.walk(gen)) {
            assertEquals(List.of(), generated.filter(Files::isRegularFile).toList());
        }
    }

    @Test
    void aModuleWithoutAPackageAndWithoutTheAnnotationCompilesWithoutAHolder() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("xclasses"));
        Path gen = Files.createDirectories(tempDir.resolve("xgen"));
        Files.writeString(classes.resolve("vidocq.properties"), "vidocq.pool.archive.url=jdbc:h2:mem:archive\n");
        Path moduleInfo = Files.createDirectories(tempDir.resolve("xsrc")).resolve("module-info.java");
        Files.writeString(moduleInfo, "module com.example.nothing { }\n");

        List<String> messages = new ArrayList<>();
        boolean ok = compileModule(List.of(moduleInfo.toFile()), classes.toFile(), gen.toFile(), messages);

        assertTrue(ok, "a module that declares no bean has no holder to receive, and no error: " + messages);
        try (var generated = Files.walk(gen)) {
            assertEquals(List.of(), generated.filter(Files::isRegularFile).toList());
        }
    }

    /** A module declaration without the annotation, and one class in the package of the module's name. */
    private List<File> plainModule(String name) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(name));
        Path moduleInfo = root.resolve("module-info.java");
        Files.writeString(moduleInfo, """
                module %s {
                    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
                    requires java.sql;
                    requires jakarta.cdi;
                    requires jakarta.inject;
                }
                """.formatted(name));
        Path app = Files.createDirectories(root.resolve(name.replace('.', '/'))).resolve("App.java");
        Files.writeString(app, """
                package %s;
                public class App {}
                """.formatted(name));
        return List.of(moduleInfo.toFile(), app.toFile());
    }

    private boolean compileModule(List<File> sources, File classesOut, File genOut, List<String> messages)
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available");
        String cp = effectiveClasspath();
        List<String> options = List.of(
                "--module-path", cp,
                "-processorpath", cp,
                "-processor", VidocqDataSourcesProcessor.class.getName(),
                "-d", classesOut.getAbsolutePath(),
                "-s", genOut.getAbsolutePath());
        javax.tools.DiagnosticListener<JavaFileObject> listener =
                d -> messages.add(d.getKind() + ": " + d.getMessage(null));
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
            try {
                return compiler.getTask(null, fm, listener, options, null, units).call();
            } catch (RuntimeException e) {
                messages.add("EXCEPTION: " + e);
                return false;
            }
        }
    }

    private boolean compileWithProcessor(File source, File classesOut, File genOut) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available");
        String cp = effectiveClasspath();
        List<String> options = List.of(
                "-classpath", cp,
                "-processorpath", cp,
                "-processor", VidocqDataSourcesProcessor.class.getName(),
                "-d", classesOut.getAbsolutePath(),
                "-s", genOut.getAbsolutePath());
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(source);
            return compiler.getTask(null, fm, null, options, null, units).call();
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
                    } catch (Exception _) {
                        // non-file URL — skip
                    }
                }
            }
        }
        return String.join(File.pathSeparator, new ArrayList<>(entries));
    }
}
