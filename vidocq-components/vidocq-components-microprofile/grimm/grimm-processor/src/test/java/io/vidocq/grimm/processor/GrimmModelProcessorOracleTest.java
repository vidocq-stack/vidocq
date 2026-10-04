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

import io.vidocq.grimm.internal.OpenApiSerializers;
import io.vidocq.grimm.internal.config.GrimmConfig;
import io.vidocq.grimm.internal.scanner.AnnotationScanner;
import io.vidocq.grimm.internal.serialization.JsonDeserializer;
import io.vidocq.grimm.spi.gen.OpenApiContribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden oracle for CG-06: a fixture compiled WITH grimm-processor must produce a
 * {@code $$GrimmModel} fragment <strong>identical</strong> to what the runtime
 * {@link AnnotationScanner} derives for the same class — the scan IS the behavioural
 * reference; any divergence is a processor bug. Comparison is done on the canonical
 * JSON serialization of both models (same serializer, same code path).
 */
class GrimmModelProcessorOracleTest {

    @TempDir
    Path tempDir;

    private record Compilation(URLClassLoader loader, File outputDir) {
    }

    private Compilation compile(File... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        File outputDir = Files.createDirectories(tempDir.resolve("classes-" + System.nanoTime())).toFile();
        List<File> cpFiles = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) cpFiles.add(new File(entry));
        }
        ClassLoader cl = getClass().getClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader ucl) {
                for (java.net.URL url : ucl.getURLs()) {
                    if ("file".equals(url.getProtocol())) cpFiles.add(new File(url.toURI()));
                }
            }
            cl = cl.getParent();
        }
        ModuleLayer layer = getClass().getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
                if ("file".equals(uri.getScheme())) cpFiles.add(new File(uri));
            }));
        }
        List<File> dedupCp = cpFiles.stream().distinct().filter(File::exists).toList();

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
        fm.setLocation(StandardLocation.CLASS_PATH, dedupCp);
        fm.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, dedupCp);
        boolean ok = compiler.getTask(null, fm, diags, List.of("--release", "25", "-proc:full"),
                null, fm.getJavaFileObjects(sources)).call();
        if (!ok) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            diags.getDiagnostics().forEach(d -> sb.append(d.getKind()).append(": ")
                    .append(d.getMessage(Locale.ROOT)).append('\n'));
            fail(sb.toString());
        }
        fm.close();
        return new Compilation(new URLClassLoader(
                new java.net.URL[] { outputDir.toURI().toURL() }, getClass().getClassLoader()), outputDir);
    }

    private File writeSource(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toFile();
    }

    /** Compares the companion fragment with the runtime scan, canonical JSON form. */
    private void assertFragmentMatchesScan(Compilation compilation, String resourceFqn) throws Exception {
        Class<?> resource = compilation.loader().loadClass(resourceFqn);
        OpenApiContribution contribution = (OpenApiContribution) compilation.loader()
                .loadClass(resourceFqn + "$$GrimmModel").getDeclaredConstructor().newInstance();
        assertEquals(resource, contribution.resourceClass());

        String oracle = canonical(scanJson(compilation, resourceFqn));
        String fragment = canonical(contribution.openApiJson());

        assertEquals(oracle, fragment,
                "Compile-time fragment diverges from the runtime scan for " + resourceFqn);
    }

    /** The runtime scan of the compiled class — the oracle — as JSON. */
    private static String scanJson(Compilation compilation, String resourceFqn) throws Exception {
        Class<?> resource = compilation.loader().loadClass(resourceFqn);
        var scanner = new AnnotationScanner(GrimmConfig.defaults().scan());
        return OpenApiSerializers.toJson(scanner.scanClasses(List.of(resource)));
    }

    /**
     * Canonical form: same deserializer/serializer pair on both sides, path items
     * re-inserted in alphabetical order (path order in an OpenAPI document carries no
     * semantics — the scan follows reflection order, the processor follows source order).
     */
    private static String canonical(String json) {
        var model = new JsonDeserializer().deserialize(json);
        if (model.getPaths() != null && model.getPaths().getPathItems() != null) {
            var sorted = new java.util.TreeMap<>(model.getPaths().getPathItems());
            var paths = org.eclipse.microprofile.openapi.OASFactory
                    .createObject(org.eclipse.microprofile.openapi.models.Paths.class);
            sorted.forEach(paths::addPathItem);
            model.setPaths(paths);
        }
        return OpenApiSerializers.toJson(model);
    }

    // ------------------------------------------------------------------
    // Oracle fixtures (M1 subset)
    // ------------------------------------------------------------------

    @Test
    void simpleGetResource_matchesScan() throws Exception {
        var compilation = compile(writeSource("t/PingResource.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.Produces;

                @Path("/ping")
                public class PingResource {

                    @GET
                    @Path("/{id}")
                    @Produces("text/plain")
                    public String get(@PathParam("id") String id) {
                        return id;
                    }
                }
                """));
        assertFragmentMatchesScan(compilation, "t.PingResource");
    }

    @Test
    void multiVerbsParamsAndScalars_matchScan() throws Exception {
        var compilation = compile(writeSource("t/CrudResource.java", """
                package t;
                import jakarta.ws.rs.DELETE;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.HeaderParam;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.Produces;
                import jakarta.ws.rs.QueryParam;

                @Path("items")
                @Produces("application/json")
                public class CrudResource {

                    @GET
                    public long count(@QueryParam("filter") String filter,
                                      @HeaderParam("X-Trace") String trace) {
                        return 0L;
                    }

                    @GET
                    @Path("/{id}")
                    public boolean exists(@PathParam("id") int id) {
                        return false;
                    }

                    @DELETE
                    @Path("/{id}")
                    public void remove(@PathParam("id") long id) {
                    }
                }
                """));
        assertFragmentMatchesScan(compilation, "t.CrudResource");
    }

    @Test
    void noProducesVoidReturn_matchesScan() throws Exception {
        var compilation = compile(writeSource("t/Bare.java", """
                package t;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;

                @Path("/bare")
                public class Bare {

                    @POST
                    public void touch() {
                    }
                }
                """));
        assertFragmentMatchesScan(compilation, "t.Bare");
    }

    // ------------------------------------------------------------------
    // Safety valve — runtime scan keeps these classes
    // ------------------------------------------------------------------

    private void assertSkipped(Compilation compilation, String resourceFqn) {
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass(resourceFqn + "$$GrimmModel"));
    }

    @Test
    void valve_mpOpenApiAnnotation() throws Exception {
        var compilation = compile(writeSource("t/Annotated.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import org.eclipse.microprofile.openapi.annotations.Operation;

                @Path("/annotated")
                public class Annotated {
                    @GET
                    @Operation(summary = "custom")
                    public String get() { return ""; }
                }
                """));
        assertSkipped(compilation, "t.Annotated");
    }

    @Test
    void valve_pojoReturnType() throws Exception {
        var compilation = compile(
                writeSource("t/Pojo.java", """
                        package t;
                        public class Pojo { public String name; }
                        """),
                writeSource("t/PojoResource.java", """
                        package t;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.Produces;

                        @Path("/pojo")
                        public class PojoResource {
                            @GET
                            @Produces("application/json")
                            public Pojo get() { return new Pojo(); }
                        }
                        """));
        assertSkipped(compilation, "t.PojoResource");
    }

    @Test
    void valve_requestBody() throws Exception {
        var compilation = compile(writeSource("t/BodyResource.java", """
                package t;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;

                @Path("/body")
                public class BodyResource {
                    @POST
                    public void accept(String body) { }
                }
                """));
        assertSkipped(compilation, "t.BodyResource");
    }

    @Test
    void valve_subResourceLocator() throws Exception {
        var compilation = compile(
                writeSource("t/Sub.java", """
                        package t;
                        import jakarta.ws.rs.GET;
                        public class Sub {
                            @GET
                            public String read() { return ""; }
                        }
                        """),
                writeSource("t/Locator.java", """
                        package t;
                        import jakarta.ws.rs.Path;

                        @Path("/locator")
                        public class Locator {
                            @Path("/sub")
                            public Sub sub() { return new Sub(); }
                        }
                        """));
        assertSkipped(compilation, "t.Locator");
    }

    /**
     * BUG-20261004-03: the runtime scan derives schema facets from Bean Validation on a parameter
     * ({@code @Min(1)} gives {@code minimum: 1}); the processor does not, so it hands the class to
     * the scan. The constraint is a fixture of its own: the processor matches it by name.
     */
    @Test
    void valve_beanValidationOnParameter() throws Exception {
        var compilation = compile(
                writeSource("jakarta/validation/constraints/Min.java", """
                        package jakarta.validation.constraints;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;

                        @Retention(RetentionPolicy.RUNTIME)
                        public @interface Min {
                            long value();
                            Class<?>[] groups() default {};
                        }
                        """),
                writeSource("t/Validated.java", """
                        package t;
                        import jakarta.validation.constraints.Min;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.PathParam;
                        import jakarta.ws.rs.Produces;

                        @Path("/validated")
                        public class Validated {
                            @GET
                            @Path("/{id}")
                            @Produces("text/plain")
                            public String get(@PathParam("id") @Min(1) long id) { return ""; }
                        }
                        """));
        assertTrue(scanJson(compilation, "t.Validated").contains("\"minimum\":1"),
                "the runtime scan applies @Min to the parameter schema");
        assertSkipped(compilation, "t.Validated");
    }

    /** BUG-20261004-03: the scan honours {@code javax.validation} too ({@code @NotNull}: required). */
    @Test
    void valve_javaxBeanValidationOnParameter() throws Exception {
        var compilation = compile(
                writeSource("javax/validation/constraints/NotNull.java", """
                        package javax.validation.constraints;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;

                        @Retention(RetentionPolicy.RUNTIME)
                        public @interface NotNull {
                            Class<?>[] groups() default {};
                        }
                        """),
                writeSource("t/LegacyValidated.java", """
                        package t;
                        import javax.validation.constraints.NotNull;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/legacy")
                        public class LegacyValidated {
                            @GET
                            public void find(@QueryParam("q") @NotNull String q) { }
                        }
                        """));
        assertTrue(scanJson(compilation, "t.LegacyValidated").contains("\"required\":true"),
                "the runtime scan makes a @NotNull query parameter required");
        assertSkipped(compilation, "t.LegacyValidated");
    }

    @Test
    void servicesFile_listsGeneratedContributions() throws Exception {
        var compilation = compile(writeSource("t/Listed.java", """
                package t;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/listed")
                public class Listed {
                    @GET
                    public String get() { return ""; }
                }
                """));
        Path services = compilation.outputDir().toPath()
                .resolve("META-INF/services/io.vidocq.grimm.spi.gen.OpenApiContribution");
        assertTrue(Files.exists(services));
        assertTrue(Files.readString(services).contains("t.Listed$$GrimmModel"));
    }
}
