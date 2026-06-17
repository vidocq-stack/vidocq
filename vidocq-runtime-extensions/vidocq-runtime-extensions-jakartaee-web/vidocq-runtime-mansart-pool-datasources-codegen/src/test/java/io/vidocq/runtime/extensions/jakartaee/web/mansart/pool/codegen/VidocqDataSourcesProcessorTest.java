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
                    } catch (Exception ignored) {
                        // non-file URL — skip
                    }
                }
            }
        }
        return String.join(File.pathSeparator, new ArrayList<>(entries));
    }
}
