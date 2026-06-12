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
}
