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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.report.ContributorFixtures;
import io.vidocq.runtime.core.report.Section;
import io.vidocq.runtime.core.report.StartupReport;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup report contributors on real boots: an extension that is a contributor and a library found by the
 * {@link java.util.ServiceLoader} of the context class loader write their sections after the core's, before
 * {@code Vidocq - Started in}; a layer twin writes one; a contributor that throws costs its section, never the
 * boot; at {@code off} the contributors are still called and their anomalies still logged; the context reads
 * the container without creating a bean it is only asked about.
 */
class StartupReportContributorBootTest {

    private static final String REPORT_LOGGER = "io.vidocq.startup";
    private static final String ANOMALY_LOGGER = "io.vidocq.startup.anomaly";
    private static final String REPORT_KEY = "vidocq.startup.report";
    private static final String MODE_KEY = "vidocq.launch.mode";

    @TempDir
    Path dir;

    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void startFromAnUnconfiguredJvm() {
        for (String key : List.of(REPORT_KEY, MODE_KEY)) {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreTheConfiguration() {
        saved.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    void anExtensionAndALibraryWriteTheirSectionsAfterTheCoreOnes() throws Exception {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");
        ContributorFixtures.onClassPath(dir, "fixture.library.LibraryReport", "library", 1000,
                "found by the service loader");
        VidocqExtension glue = new Contributor("glue", 10,
                (context, section) -> section.summary("written by an extension"));

        try (URLClassLoader loader = ContributorFixtures.classPathLoader(dir);
             LogRecords records = new LogRecords("")) {
            VidocqBootstrap bootstrap = withContextLoader(loader, () -> bootstrap(glue).configure().start());
            try {
                String report = records.messages(REPORT_LOGGER, Level.INFO).getFirst();
                assertTrue(report.endsWith("\n  glue        written by an extension"
                        + "\n  library     found by the service loader"
                        + "\n  anomalies   none"), report);
                assertEquals(List.of("layer", "configuration", "extensions", "glue", "library"),
                        bootstrap.startupReport().orElseThrow().sections().stream().map(Section::id).toList());
                List<LogRecord> all = records.all();
                assertEquals(all.size() - 1, indexOf(all, "Vidocq - Started in "), "Started in stays the last line");
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void aLibraryLoadedTwiceByTheLayersWritesOneSection() throws Exception {
        System.setProperty(REPORT_KEY, "summary");
        ClassLoader child = ContributorFixtures.twinLayers(dir);

        try (LogRecords records = new LogRecords(ANOMALY_LOGGER)) {
            VidocqBootstrap bootstrap = withContextLoader(child, () -> bootstrap().configure().start());
            try {
                StartupReport report = bootstrap.startupReport().orElseThrow();
                List<Section> twins = report.sections().stream()
                        .filter(section -> section.id().equals(ContributorFixtures.TWIN_ID))
                        .toList();
                assertEquals(1, twins.size(), report.sections().toString());
                assertEquals(ContributorFixtures.TWIN_SUMMARY, twins.getFirst().summary());
                assertEquals(List.of(), report.anomalyCodes());
                assertEquals(List.of(), records.messages(ANOMALY_LOGGER, Level.WARNING));
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void aContributorThatThrowsCostsItsSectionAndNotTheBoot() {
        System.setProperty(REPORT_KEY, "summary");
        IllegalStateException boom = new IllegalStateException("boom");
        VidocqExtension failing = new Contributor("failing", 10, (context, section) -> {
            section.summary("never printed");
            throw boom;
        });
        VidocqExtension fine = new Contributor("fine", 20, (context, section) -> section.summary("printed"));

        try (LogRecords records = new LogRecords("")) {
            VidocqBootstrap bootstrap = bootstrap(failing, fine).configure().start();
            try {
                List<LogRecord> warnings = records.all().stream()
                        .filter(r -> ANOMALY_LOGGER.equals(r.getLoggerName()) && r.getLevel() == Level.WARNING)
                        .toList();
                assertEquals(1, warnings.size(), warnings.toString());
                assertEquals("[VIDOCQ-RPT-001] Startup report contributor 'failing' (" + failing.getClass().getName()
                        + ") failed; its section is skipped", LogRecords.message(warnings.getFirst()));
                assertSame(boom, warnings.getFirst().getThrown());
                String report = records.messages(REPORT_LOGGER, Level.INFO).getFirst();
                assertFalse(report.contains("never printed"), report);
                assertTrue(report.endsWith("\n  fine        printed\n  anomalies   1: VIDOCQ-RPT-001 (logged above)"),
                        report);
                assertTrue(indexOf(records.all(), "Vidocq - Started in ") >= 0, "the boot went on");
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void offStillCallsTheContributorsAndLogsTheirAnomalies() {
        System.setProperty(REPORT_KEY, "off");
        VidocqExtension acme = new Contributor("acme", 10, (context, section) -> section
                .summary("kept in the report")
                .anomaly("ACME-001", "Acme is not configured.", "Set acme.url."));

        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap(acme).configure().start();
            try {
                assertEquals(List.of(), records.messages(REPORT_LOGGER, Level.INFO));
                assertEquals(List.of("[ACME-001] Acme is not configured. Set acme.url."),
                        records.messages(ANOMALY_LOGGER, Level.WARNING));
                StartupReport report = bootstrap.startupReport().orElseThrow();
                assertEquals(Verbosity.OFF, report.verbosity());
                assertEquals(List.of("ACME-001"), report.anomalyCodes());
                assertEquals("kept in the report", report.section("acme").orElseThrow().summary());
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void theContextReadsTheContainerWithoutCreatingWhatItIsOnlyAskedAbout() throws Exception {
        System.setProperty(MODE_KEY, "test");
        System.setProperty(REPORT_KEY, "detailed");
        ProbeBeans.write(dir);
        Map<String, Object> seen = new LinkedHashMap<>();

        try (URLClassLoader beans = ContributorFixtures.classPathLoader(dir)) {
            Class<?> probe = beans.loadClass(ProbeBeans.PROBE);
            Class<?> counted = beans.loadClass(ProbeBeans.COUNTED);
            Class<?> shared = beans.loadClass(ProbeBeans.SHARED);
            Class<?> exploding = beans.loadClass(ProbeBeans.EXPLODING);
            VidocqExtension asking = new Contributor("probe", 10, (context, section) -> {
                seen.put("verbosity", context.verbosity());
                seen.put("launchMode", context.launchMode());
                seen.put("interface", context.hasBeanOfType(ProbeBeans.PROBE));
                seen.put("class", context.hasBeanOfType(ProbeBeans.COUNTED));
                seen.put("unknown", context.hasBeanOfType("com.acme.Nothing"));
                seen.put("created while asked", ProbeBeans.created(counted));
                seen.put("probe", context.lookup(probe).map(bean -> bean.getClass().getName()).orElse("none"));
                seen.put("created on lookup", ProbeBeans.created(counted));
                seen.put("ambiguous", context.lookup(shared).isPresent());
                seen.put("failing", context.lookup(exploding).isPresent());
                seen.put("unsatisfied", context.lookup(Runnable.class).isPresent());
                seen.put("bean manager", context.lookup(BeanManager.class).isPresent());
            }) {
                @Override
                public void beforeStart(VaubanContainerBuilder builder) {
                    builder.addSyntheticArchiveClass(counted);
                    for (String bean : List.of(ProbeBeans.SHARED_ONE, ProbeBeans.SHARED_TWO, ProbeBeans.EXPLODING)) {
                        try {
                            builder.addSyntheticArchiveClass(beans.loadClass(bean));
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            };

            VidocqBootstrap bootstrap = bootstrap(asking).configure().start();
            bootstrap.shutdown();
        }

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("verbosity", Verbosity.DETAILED);
        expected.put("launchMode", LaunchMode.TEST);
        expected.put("interface", true);
        expected.put("class", true);
        expected.put("unknown", false);
        expected.put("created while asked", 0);
        expected.put("probe", ProbeBeans.COUNTED);
        expected.put("created on lookup", 1);
        expected.put("ambiguous", false);
        expected.put("failing", false);
        expected.put("unsatisfied", false);
        expected.put("bean manager", true);
        assertEquals(expected, seen);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static VidocqBootstrap bootstrap(VidocqExtension... extensions) {
        return VidocqBootstrap.create().banner(BannerMode.OFF).extensions(List.of(extensions));
    }

    /** Runs {@code boot} with {@code loader} as the context class loader, whose services the report looks up. */
    private static VidocqBootstrap withContextLoader(ClassLoader loader, Supplier<VidocqBootstrap> boot) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return boot.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static int indexOf(List<LogRecord> records, String prefix) {
        for (int i = 0; i < records.size(); i++) {
            if (LogRecords.message(records.get(i)).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** An extension that is also a startup report contributor. */
    private static class Contributor implements VidocqExtension, StartupReportContributor {
        private final String id;
        private final int priority;
        private final BiConsumer<StartupReportContext, StartupReportSection> body;

        Contributor(String id, int priority, BiConsumer<StartupReportContext, StartupReportSection> body) {
            this.id = id;
            this.priority = priority;
            this.body = body;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Set<String> configKeys() {
            return Set.of();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            body.accept(context, section);
        }
    }

    /**
     * The beans the context is asked about, emitted into a class output of their own: a class of the test sources
     * belongs to the module {@code io.vidocq.runtime.core}, which Vauban may not open to create it, while a class
     * of the class path can be created.
     *
     * <pre>
     * public interface Probe {}
     * public class CountedProbe implements Probe { public static int CREATED; public CountedProbe() { CREATED++; } }
     * public interface Shared {}
     * public class SharedOne implements Shared {}
     * public class SharedTwo implements Shared {}
     * public class Exploding { public Exploding() { throw new IllegalStateException(); } }
     * </pre>
     */
    private static final class ProbeBeans {
        static final String PROBE = "fixture.beans.Probe";
        static final String COUNTED = "fixture.beans.CountedProbe";
        static final String SHARED = "fixture.beans.Shared";
        static final String SHARED_ONE = "fixture.beans.SharedOne";
        static final String SHARED_TWO = "fixture.beans.SharedTwo";
        static final String EXPLODING = "fixture.beans.Exploding";

        private static final ClassDesc ILLEGAL_STATE = ClassDesc.of("java.lang.IllegalStateException");

        static void write(Path root) throws IOException {
            write(root, PROBE, anInterface(PROBE));
            write(root, SHARED, anInterface(SHARED));
            write(root, COUNTED, aClass(COUNTED, PROBE, code -> code
                    .getstatic(ClassDesc.of(COUNTED), "CREATED", ConstantDescs.CD_int)
                    .iconst_1()
                    .iadd()
                    .putstatic(ClassDesc.of(COUNTED), "CREATED", ConstantDescs.CD_int)
                    .return_()));
            write(root, SHARED_ONE, aClass(SHARED_ONE, SHARED, CodeBuilder::return_));
            write(root, SHARED_TWO, aClass(SHARED_TWO, SHARED, CodeBuilder::return_));
            write(root, EXPLODING, aClass(EXPLODING, null, code -> code
                    .new_(ILLEGAL_STATE)
                    .dup()
                    .invokespecial(ILLEGAL_STATE, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                    .athrow()));
        }

        /** How many {@code CountedProbe}s were created. */
        static int created(Class<?> counted) {
            try {
                return counted.getField("CREATED").getInt(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] anInterface(String name) {
            return ClassFile.of().build(ClassDesc.of(name), type -> type
                    .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT));
        }

        /** A public class with a public no-argument constructor that goes on with {@code rest}. */
        private static byte[] aClass(String name, String implemented, Consumer<CodeBuilder> rest) {
            return ClassFile.of().build(ClassDesc.of(name), type -> {
                type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                if (implemented != null) {
                    type.withInterfaceSymbols(ClassDesc.of(implemented));
                }
                if (name.equals(COUNTED)) {
                    type.withField("CREATED", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
                }
                type.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
                    code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                    rest.accept(code);
                });
            });
        }

        private static void write(Path root, String name, byte[] bytes) throws IOException {
            Path file = root.resolve(name.replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
    }
}
