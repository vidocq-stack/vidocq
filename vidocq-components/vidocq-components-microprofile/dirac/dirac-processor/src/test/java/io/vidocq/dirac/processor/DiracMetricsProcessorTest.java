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
package io.vidocq.dirac.processor;

import io.vidocq.dirac.spi.gen.MetricsCompanion;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles fixture metric beans WITH dirac-processor on the annotation-processor path
 * (in-process javax.tools.JavaCompiler — same zero-dep harness as the cassini and
 * cyrano processors) and verifies the generated {@code $$DiracMetrics} companions:
 * resolved names/units/tags/scopes, gauge accessors, ServiceLoader registration, and
 * the skip-on-complexity safety valve.
 */
class DiracMetricsProcessorTest {

    @TempDir
    Path tempDir;

    private record Compilation(URLClassLoader loader, File outputDir) {

        MetricsCompanion companionOf(String beanFqn) throws Exception {
            return (MetricsCompanion) loader.loadClass(beanFqn + "$$DiracMetrics")
                    .getDeclaredConstructor().newInstance();
        }
    }

    private Compilation compile(File... sources) throws Exception {
        return compile(List.of(), "-proc:full", sources);
    }

    /**
     * Compiles {@code sources} against the test class path and {@code libraries}; {@code proc} says whether the
     * processors run.
     */
    private Compilation compile(List<File> libraries, String proc, File... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        File outputDir = Files.createDirectories(tempDir.resolve("classes-" + System.nanoTime())).toFile();
        List<File> cpFiles = new ArrayList<>(libraries);
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
        boolean ok = compiler.getTask(null, fm, diags, List.of("--release", "25", proc),
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

    private File meteredBean() throws Exception {
        return writeSource("t/Svc.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Counted;
                import org.eclipse.microprofile.metrics.annotation.Gauge;
                import org.eclipse.microprofile.metrics.annotation.Timed;

                public class Svc {

                    @Counted
                    public Svc() {}

                    @Timed(name = "t1", tags = {"region=eu"}, description = "d1", unit = "milliseconds")
                    public void process() {}

                    @Counted(absolute = true, name = "hits")
                    public void hit() {}

                    @Gauge(unit = "celsius", tags = {"room=lab"})
                    public int temperature() { return 21; }

                    @Gauge(unit = "seconds", absolute = true, name = "uptime")
                    public static long uptime() { return 99L; }
                }
                """);
    }

    @Test
    void companionCarriesResolvedSpecs() throws Exception {
        var compilation = compile(meteredBean());
        MetricsCompanion companion = compilation.companionOf("t.Svc");

        assertEquals("t.Svc", companion.beanClass().getName());
        assertEquals(List.of(new MetricsCompanion.MetricSpec(
                        "t.Svc.t1", "d1", "milliseconds", List.of("region=eu"), "application")),
                companion.timers());
        assertEquals(List.of(
                        new MetricsCompanion.MetricSpec("hits", "", "none", List.of(), "application"),
                        new MetricsCompanion.MetricSpec("t.Svc.Svc", "", "none", List.of(), "application")),
                companion.counters());

        assertEquals(2, companion.gauges().size());
        var temperature = companion.gauges().get(0);
        assertEquals("t.Svc.temperature", temperature.name());
        assertEquals("celsius", temperature.unit());
        assertEquals(List.of("room=lab"), temperature.tags());
        assertFalse(temperature.staticMethod());
        var uptime = companion.gauges().get(1);
        assertEquals("uptime", uptime.name());
        assertTrue(uptime.staticMethod());
    }

    @Test
    void gaugeAccessors_invokeTheRealMethods() throws Exception {
        var compilation = compile(meteredBean());
        MetricsCompanion companion = compilation.companionOf("t.Svc");
        Object bean = compilation.loader().loadClass("t.Svc").getDeclaredConstructor().newInstance();

        assertEquals(21, companion.gauges().get(0).invoker().apply(bean));
        assertEquals(99L, companion.gauges().get(1).invoker().apply(null));
    }

    @Test
    void classLevelTimed_appliesToMethodsAndConstructor() throws Exception {
        var compilation = compile(writeSource("t/Klass.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Timed;

                @Timed
                public class Klass {
                    public Klass() {}
                    public void a() {}
                    private void hidden() {}
                }
                """));
        MetricsCompanion companion = compilation.companionOf("t.Klass");
        assertEquals(List.of(
                        new MetricsCompanion.MetricSpec("t.Klass.a", "", "nanoseconds", List.of(), "application"),
                        new MetricsCompanion.MetricSpec("t.Klass.Klass", "", "nanoseconds", List.of(), "application")),
                companion.timers(),
                "class-level @Timed: non-private methods + constructor, private skipped");
    }

    @Test
    void registersCompanionInServiceLoaderFile() throws Exception {
        var compilation = compile(meteredBean());
        Path services = compilation.outputDir().toPath()
                .resolve("META-INF/services/io.vidocq.dirac.spi.gen.MetricsCompanion");
        assertTrue(Files.exists(services));
        assertTrue(Files.readString(services).contains("t.Svc$$DiracMetrics"));
    }

    @Test
    void valve_privateGaugeMethod() throws Exception {
        var compilation = compile(writeSource("t/PrivGauge.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Gauge;

                public class PrivGauge {
                    @Gauge(unit = "none")
                    private int hidden() { return 1; }
                }
                """));
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass("t.PrivGauge$$DiracMetrics"));
    }

    @Test
    void valve_invalidGaugeSignature_keepsRuntimeDeploymentError() throws Exception {
        var compilation = compile(writeSource("t/BadGauge.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Gauge;

                public class BadGauge {
                    @Gauge(unit = "none")
                    public String notNumeric() { return "x"; }
                }
                """));
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass("t.BadGauge$$DiracMetrics"));
    }

    @Test
    void valve_malformedTag() throws Exception {
        var compilation = compile(writeSource("t/BadTag.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Counted;

                public class BadTag {
                    @Counted(tags = {"missingseparator"})
                    public void hit() {}
                }
                """));
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass("t.BadTag$$DiracMetrics"));
    }

    @Test
    void plainClass_getsNoCompanion() throws Exception {
        var compilation = compile(writeSource("t/Plain.java", """
                package t;
                public class Plain {
                    public void nothing() {}
                }
                """));
        assertThrows(ClassNotFoundException.class,
                () -> compilation.loader().loadClass("t.Plain$$DiracMetrics"));
    }

    @Test
    void namingRulesMatchTheRuntimeScan() throws Exception {
        var compilation = compile(writeSource("t/Names.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Counted;
                import org.eclipse.microprofile.metrics.annotation.Gauge;
                import org.eclipse.microprofile.metrics.annotation.Timed;

                @Counted(name = "grp", tags = {" ", "k=v"})
                public class Names {
                    @Timed(name = "ctor", absolute = true, scope = " ")
                    public Names() {}

                    @Timed(name = "rel")
                    Names(int ignored) {}

                    @Timed(absolute = true, scope = "vendor")
                    protected Names(long ignored) {}

                    private Names(String ignored) {}

                    @Timed(name = "named")
                    public void a() {}

                    @Timed(absolute = true)
                    public void b() {}

                    @Gauge(name = "g1", unit = "none")
                    public Integer boxed() { return 1; }

                    @Gauge(absolute = true, unit = "none")
                    public double ratio() { return 0.5d; }
                }
                """));
        MetricsCompanion companion = compilation.companionOf("t.Names");

        assertEquals(List.of(
                        new MetricsCompanion.MetricSpec("t.Names.named", "", "nanoseconds", List.of(), "application"),
                        new MetricsCompanion.MetricSpec("b", "", "nanoseconds", List.of(), "application"),
                        new MetricsCompanion.MetricSpec("ctor", "", "nanoseconds", List.of(), "application"),
                        new MetricsCompanion.MetricSpec("t.Names.rel", "", "nanoseconds", List.of(), "application"),
                        new MetricsCompanion.MetricSpec("Names", "", "nanoseconds", List.of(), "vendor")),
                companion.timers());
        var counterNames = companion.counters().stream().map(MetricsCompanion.MetricSpec::name).toList();
        assertEquals(List.of("t.grp.a", "t.grp.b", "t.grp.boxed", "t.grp.ratio",
                "t.grp.Names", "t.grp.Names", "t.grp.Names"), counterNames);
        assertEquals(List.of("k=v"), companion.counters().get(0).tags());
        assertEquals(List.of("t.Names.g1", "ratio"),
                companion.gauges().stream().map(MetricsCompanion.GaugeSpec::name).toList());
    }

    @Test
    void absoluteClassLevelNameAndMetaAnnotations() throws Exception {
        var compilation = compile(writeSource("t/Meta.java", """
                package t;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import org.eclipse.microprofile.metrics.annotation.Counted;
                import org.eclipse.microprofile.metrics.annotation.Timed;

                @Timed(name = "abs", absolute = true)
                public class Meta {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.METHOD)
                    @Counted(name = "meta.count", absolute = true)
                    public @interface Hit {}

                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.METHOD)
                    @Timed(name = "meta.time", absolute = true)
                    public @interface Slow {}

                    @Hit
                    public void hit() {}

                    @Slow
                    public void slow() {}
                }
                """));
        MetricsCompanion companion = compilation.companionOf("t.Meta");

        assertEquals(List.of("abs", "meta.time", "abs.Meta"),
                companion.timers().stream().map(MetricsCompanion.MetricSpec::name).toList());
        assertEquals(List.of("meta.count"),
                companion.counters().stream().map(MetricsCompanion.MetricSpec::name).toList());
    }

    @Test
    void defaultPackageBeanGetsACompanion() throws Exception {
        var compilation = compile(writeSource("Root.java", """
                import org.eclipse.microprofile.metrics.annotation.Timed;

                @Timed(name = "grp")
                public class Root {
                    public void a() {}
                }
                """));
        MetricsCompanion companion = compilation.companionOf("Root");

        assertEquals(List.of("grp.a", "grp.Root"),
                companion.timers().stream().map(MetricsCompanion.MetricSpec::name).toList());
    }

    @Test
    void literalsAreEscapedInTheGeneratedSource() throws Exception {
        var compilation = compile(writeSource("t/Esc.java", """
                package t;
                import org.eclipse.microprofile.metrics.annotation.Counted;

                public class Esc {
                    @Counted(name = "esc", absolute = true, description = "q\\"x\\" b\\\\ n\\n t\\t r\\r c\\001")
                    public void hit() {}
                }
                """));
        MetricsCompanion companion = compilation.companionOf("t.Esc");

        assertEquals("q\"x\" b\\ n\n t\t r\r c\001", companion.counters().get(0).description());
    }

    @Test
    void valve_reservedTagParameterGaugeAndNonNumericPrimitive() throws Exception {
        var compilation = compile(
                writeSource("t/Reserved.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Timed;

                        public class Reserved {
                            @Timed(tags = {"mp_scope=x"})
                            public void a() {}
                        }
                        """),
                writeSource("t/ParamGauge.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Gauge;

                        public class ParamGauge {
                            @Gauge(unit = "none")
                            public int value(int factor) { return factor; }
                        }
                        """),
                writeSource("t/BoolGauge.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Gauge;

                        public class BoolGauge {
                            @Gauge(unit = "none")
                            public boolean up() { return true; }
                        }
                        """),
                writeSource("t/EmptyValueTag.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Counted;

                        public class EmptyValueTag {
                            @Counted(tags = {"k="})
                            public void a() {}
                        }
                        """));
        for (String bean : List.of("t.Reserved", "t.ParamGauge", "t.BoolGauge", "t.EmptyValueTag")) {
            assertThrows(ClassNotFoundException.class,
                    () -> compilation.loader().loadClass(bean + "$$DiracMetrics"), bean);
        }
    }

    @Test
    void interceptorsAndInterfacesGetNoCompanion() throws Exception {
        var compilation = compile(
                writeSource("t/Icpt.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Timed;

                        @jakarta.interceptor.Interceptor
                        @Timed
                        public class Icpt {
                            public void a() {}
                        }
                        """),
                writeSource("t/Api.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Timed;

                        public interface Api {
                            @Timed
                            void a();
                        }
                        """));
        assertThrows(ClassNotFoundException.class, () -> compilation.loader().loadClass("t.Icpt$$DiracMetrics"));
        assertThrows(ClassNotFoundException.class, () -> compilation.loader().loadClass("t.Api$$DiracMetrics"));
        assertFalse(Files.exists(compilation.outputDir().toPath()
                .resolve("META-INF/services/io.vidocq.dirac.spi.gen.MetricsCompanion")));
    }

    @Test
    void existingCompanionSourceIsKept() throws Exception {
        var compilation = compile(
                writeSource("t/Dup.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Timed;

                        public class Dup {
                            @Timed
                            public void a() {}
                        }
                        """),
                writeSource("t/Dup$$DiracMetrics.java", """
                        package t;

                        public class Dup$$DiracMetrics {
                            public static final String MARKER = "hand-written";
                        }
                        """));
        Class<?> kept = compilation.loader().loadClass("t.Dup$$DiracMetrics");

        assertEquals("hand-written", kept.getField("MARKER").get(null));
    }

    /**
     * DRC-011 on the generated path: a bean compiled with the processor, which has metrics of its own, inherits without
     * overriding it a {@code @Counted} method of a superclass from another jar, which the container does not index.
     * CDI 4.1, §4.2, passes the binding on; MicroProfile Metrics 5.1 ("Responsibility of the MicroProfile Metrics
     * implementation") registers the metric at deployment, and it counts the call.
     */
    @Test
    void aBeanCompiledWithTheProcessorRegistersTheMetricOfAMethodInheritedFromAnotherJar() throws Exception {
        File library = jar(compile(List.of(), "-proc:none", writeSource("lib/lib/Base.java", """
                package lib;
                import org.eclipse.microprofile.metrics.annotation.Counted;

                public class Base {
                    @Counted(name = "generated.inherited", absolute = true)
                    public String ping() { return "pong"; }
                }
                """)).outputDir());
        var application = compile(List.of(library), "-proc:full", writeSource("app/app/Svc.java", """
                package app;
                import jakarta.enterprise.context.ApplicationScoped;
                import org.eclipse.microprofile.metrics.annotation.Counted;

                @ApplicationScoped
                public class Svc extends lib.Base {
                    @Counted(name = "generated.own", absolute = true)
                    public void own() {}
                }
                """));
        assertNotNull(application.loader().getResource("app/Svc$$DiracMetrics.class"), "the processor ran");
        assertNull(application.loader().getResource("lib/Base.class"), "the superclass is not compiled with the bean");

        try (var loader = new URLClassLoader(new java.net.URL[] {application.outputDir().toURI().toURL(),
                library.toURI().toURL()}, getClass().getClassLoader())) {
            Class<?> bean = loader.loadClass("app.Svc");
            var thread = Thread.currentThread();
            var previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            // The superclass is not added to the deployment: only the bean is, so the container does not index it.
            try (var container = jakarta.enterprise.inject.se.SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addBeanClasses(io.vidocq.dirac.cdi.internal.DiracExtension.class,
                            io.vidocq.dirac.cdi.internal.CountedInterceptor.class,
                            io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean.class,
                            io.vidocq.dirac.cdi.internal.GaugeRegistrationBean.class, bean)
                    .initialize()) {
                var registry = container.select(org.eclipse.microprofile.metrics.MetricRegistry.class).get();
                var counter = registry.getCounter(new org.eclipse.microprofile.metrics.MetricID("generated.inherited"));
                assertNotNull(registry.getCounter(new org.eclipse.microprofile.metrics.MetricID("generated.own")),
                        "the bean's own counter, from its companion, is registered");
                assertNotNull(counter, "the inherited counter is registered when the bean is deployed");
                // Called by name: the bean class is not visible to this test at compile time.
                assertEquals("pong", bean.getMethod("ping").invoke(container.select(bean).get()));
                assertEquals(1, counter.getCount(), "the inherited counter counts the call");
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /**
     * DRC-011: the companion lists the metrics of the methods the bean inherits and does not override, named after
     * the class that declares them, as the runtime scan does; an overridden, a private or a static method passes
     * nothing on (CDI 4.1, §4.2). An override through a type argument has another erasure, and still overrides.
     */
    @Test
    void companionListsTheMetricsOfInheritedMethodsNamedAfterTheirDeclaringClass() throws Exception {
        var compilation = compile(
                writeSource("t/Grand.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Counted;
                        import org.eclipse.microprofile.metrics.annotation.Timed;

                        public class Grand<T> {
                            @Timed public void timedInherited() {}
                            @Counted public void countedFromGrandparent() {}
                            @Counted public void overriddenByTheParent() {}
                            @Counted public void overriddenThroughATypeArgument(T value) {}
                        }
                        """),
                writeSource("t/Parent.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Counted;

                        public class Parent extends Grand<String> {
                            @Counted public void countedInherited() {}
                            @Counted public void overriddenByTheChild(String value) {}
                            @Counted private void hidden() {}
                            @Counted public static void shared() {}
                            @Override public void overriddenByTheParent() { hidden(); }
                            @Override public void overriddenThroughATypeArgument(String value) {}
                        }
                        """),
                writeSource("t/Child.java", """
                        package t;
                        import org.eclipse.microprofile.metrics.annotation.Counted;

                        public class Child extends Parent {
                            @Counted(name = "own", absolute = true) public void own() {}
                            @Override public void overriddenByTheChild(String value) {}
                        }
                        """));
        MetricsCompanion companion = compilation.companionOf("t.Child");

        assertEquals(Set.of("t.Grand.timedInherited"), names(companion.timers()));
        assertEquals(Set.of("own", "t.Parent.countedInherited", "t.Grand.countedFromGrandparent"),
                names(companion.counters()));
    }

    /**
     * DRC-011: a package-private method of a superclass in another package is not inherited (JLS 8.4.8), so no
     * subclass of the bean's package intercepts it: the companion lists no metric for it, whether the bean declares a
     * method of the same signature or not, as the runtime scan does.
     */
    @Test
    void companionListsNoMetricForAPackagePrivateMethodOfASuperclassInAnotherPackage() throws Exception {
        var compilation = compile(
                writeSource("pp/lib/Base.java", """
                        package lib;
                        import org.eclipse.microprofile.metrics.annotation.Counted;

                        public class Base {
                            @Counted(name = "base.public", absolute = true) public void open() {}
                            @Counted(name = "base.packagePrivate", absolute = true) void packagePrivate() {}
                        }
                        """),
                writeSource("pp/app/Heir.java", """
                        package app;
                        import org.eclipse.microprofile.metrics.annotation.Counted;

                        public class Heir extends lib.Base {
                            @Counted(name = "heir.own", absolute = true) public void own() {}
                        }
                        """),
                writeSource("pp/app/Redeclarer.java", """
                        package app;
                        import org.eclipse.microprofile.metrics.annotation.Counted;

                        public class Redeclarer extends lib.Base {
                            @Counted(name = "redeclarer.own", absolute = true) public void own() {}
                            void packagePrivate() {}
                        }
                        """));

        assertEquals(Set.of("heir.own", "base.public"), names(compilation.companionOf("app.Heir").counters()));
        assertEquals(Set.of("redeclarer.own", "base.public"),
                names(compilation.companionOf("app.Redeclarer").counters()));
    }

    private static Set<String> names(List<MetricsCompanion.MetricSpec> specs) {
        return specs.stream().map(MetricsCompanion.MetricSpec::name).collect(java.util.stream.Collectors.toSet());
    }

    /** Packs a compiled library into a jar of its own. */
    private File jar(File classesDir) throws Exception {
        Path jar = tempDir.resolve("library-" + System.nanoTime() + ".jar");
        Path root = classesDir.toPath();
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new java.util.jar.JarEntry(root.relativize(file).toString()
                        .replace(File.separatorChar, '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar.toFile();
    }
}
