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

import io.vidocq.runtime.core.report.Phase;
import io.vidocq.runtime.core.report.Section;
import io.vidocq.runtime.core.report.StartupReport;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup report as {@link VidocqBootstrap} produces it on real boots: one INFO record of
 * {@value #REPORT_LOGGER} just before {@code Vidocq - Started in}, its level per launch, the anomalies it
 * recalls, and the one WARNING a failed boot logs before its exception goes through untouched.
 */
class StartupReportBootTest {

    private static final String REPORT_LOGGER = "io.vidocq.startup";
    private static final String ANOMALY_LOGGER = "io.vidocq.startup.anomaly";
    private static final String REPORT_KEY = "vidocq.startup.report";
    private static final String MODE_KEY = "vidocq.launch.mode";
    private static final String TYPO_KEY = "vidocq.startup.reprot";

    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void startFromAnUnconfiguredJvm() {
        for (String key : List.of(REPORT_KEY, MODE_KEY, TYPO_KEY)) {
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

    // ------------------------------------------------------------------------------------------- report

    @Test
    void aBootLogsOneReportJustBeforeStartedIn() {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");
        try (LogRecords records = new LogRecords("")) {
            VidocqBootstrap bootstrap = bootstrap(new Named("probe", 5)).configure().start();
            try {
                List<LogRecord> all = records.all();
                List<String> reports = records.messages(REPORT_LOGGER, Level.INFO);
                assertEquals(1, reports.size(), reports.toString());
                List<String> lines = reports.getFirst().lines().toList();
                assertEquals(List.of(
                        "Vidocq startup report",
                        "  launch      dev (vidocq.launch.mode) | report summary | details -Dvidocq.startup.report=detailed",
                        "  extensions  probe",
                        "  anomalies   none"), List.of(lines.get(0), lines.get(1), lines.get(3), lines.get(4)));
                assertTrue(lines.get(2).startsWith("  layer       "), lines.toString());

                int report = indexOf(all, "Vidocq startup report");
                int started = indexOf(all, "Vidocq - Started in ");
                assertTrue(report >= 0 && report == started - 1, "the report comes right before Started in");
                assertEquals(all.size() - 1, started, "Started in stays the last line of the boot");
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void anEmbeddedDeploymentLogsNoReportButKeepsOne() {
        System.setProperty(MODE_KEY, "dev");
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure(List.of()).start();
            try {
                assertEquals(List.of(), records.messages(REPORT_LOGGER, Level.INFO));
                StartupReport report = bootstrap.startupReport().orElseThrow();
                assertEquals(Verbosity.OFF, report.verbosity());
                assertEquals(LaunchMode.DEV, report.launchMode());
                assertEquals(List.of("configure", "weaving", "scan", "beforeStart", "build", "extensions", "audit",
                        "report"), report.phases().stream().map(Phase::name).toList());
                assertFalse(report.failed());
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void offLogsNoReportButEveryAnomaly() {
        System.setProperty(REPORT_KEY, "off");
        System.setProperty(TYPO_KEY, "detailed");
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap().configure().start();
            try {
                assertEquals(List.of(), records.messages(REPORT_LOGGER, Level.INFO));
                assertTrue(records.messages(ANOMALY_LOGGER, Level.WARNING).stream()
                        .anyMatch(m -> m.startsWith("[VIDOCQ-CFG-003] Configuration key 'vidocq.startup.reprot'")),
                        records.messages(ANOMALY_LOGGER, Level.WARNING).toString());
                assertEquals(List.of("VIDOCQ-CFG-003"), bootstrap.startupReport().orElseThrow().anomalyCodes());
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void theFirstDevBootOfTheJvmIsDetailedAndItsReloadsAreSummaries() {
        System.setProperty(MODE_KEY, "dev");
        VidocqBootstrap.forgetEarlierBoots();
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap first = bootstrap(new Named("probe", 5)).configure().start();
            first.shutdown();
            VidocqBootstrap reload = bootstrap(new Named("probe", 5)).configure().start();
            reload.shutdown();

            List<String> reports = records.messages(REPORT_LOGGER, Level.INFO);
            assertEquals(2, reports.size(), reports.toString());
            String detailed = reports.get(0);
            assertTrue(detailed.contains("\n  launch      dev (vidocq.launch.mode) | report detailed"
                    + " | override -Dvidocq.launch.mode=prod\n"), detailed);
            assertTrue(detailed.contains("\n  vidocq      "), detailed);
            assertTrue(detailed.contains("\n  phases      configure "), detailed);
            assertTrue(detailed.contains(" | build "), detailed);
            assertTrue(detailed.contains("\nconfiguration\n  sources     "), detailed);
            assertTrue(detailed.contains("\n  audited     "), detailed);
            assertTrue(detailed.contains("vidocq.startup.*"), detailed);
            assertTrue(detailed.contains("\nextensions\n  5  probe  onStart "), detailed);
            assertTrue(detailed.endsWith("\nanomalies     none"), detailed);
            String summary = reports.get(1);
            assertTrue(summary.contains("| report summary |"), summary);
            assertFalse(summary.contains("phases"), summary);
            assertTrue(summary.contains("\n  extensions  probe\n"), summary);
        }
    }

    @Test
    void theReportNamesTheSourcesAndNoValue() {
        System.setProperty(REPORT_KEY, "detailed");
        System.setProperty(TYPO_KEY, "a secret value");
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap().configure().start();
            bootstrap.shutdown();

            String report = records.messages(REPORT_LOGGER, Level.INFO).getFirst();
            assertTrue(report.contains("SystemProperties 400"), report);
            assertFalse(report.contains("a secret value"), report);
            assertTrue(report.contains("\n  VIDOCQ-CFG-003  Configuration key 'vidocq.startup.reprot' is read by"
                    + " nothing and has no effect."), report);
        }
    }

    @Test
    void aBootWhoseReportCannotBeWrittenStillKeepsItsHeader() {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");
        System.setProperty(TYPO_KEY, "detailed");
        // started, the extension can no longer give its name: the report cannot list it
        VidocqExtension unnamed = new Named("unnamed", 5) {
            private boolean started;

            @Override
            public String name() {
                if (started) {
                    throw new NoClassDefFoundError("com/acme/Name");
                }
                return super.name();
            }

            @Override
            public void onStart(ExtensionContext context) {
                started = true;
            }
        };
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap(unnamed).configure().start();
            try {
                List<String> warnings = records.messages(REPORT_LOGGER, Level.WARNING);
                assertEquals(1, warnings.size(), warnings.toString());
                assertTrue(warnings.getFirst().startsWith("Startup report skipped: java.lang.NoClassDefFoundError"),
                        warnings.getFirst());
                assertEquals(List.of(), records.messages(REPORT_LOGGER, Level.INFO));
                StartupReport report = bootstrap.startupReport().orElseThrow();
                assertFalse(report.failed(), "the boot itself went well");
                assertEquals(LaunchMode.DEV, report.launchMode());
                assertEquals("vidocq.launch.mode", report.launchReason());
                assertEquals(Verbosity.SUMMARY, report.verbosity());
                assertEquals(List.of(), report.sections());
                assertEquals(List.of("VIDOCQ-CFG-003"), report.anomalyCodes());
                assertEquals(List.of("configure", "weaving", "scan", "beforeStart", "build", "extensions", "audit",
                        "report"), report.phases().stream().map(Phase::name).toList());
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------------------------------ failure

    @Test
    void aFailingBuildIsReportedOnceAndGoesThroughUntouched() {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "detailed");
        VidocqExtension unsatisfied = new Named("unsatisfied", 10) {
            @Override
            public void beforeStart(VaubanContainerBuilder builder) {
                builder.addBeanClass(UnsatisfiedBean.load());
            }
        };
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap(unsatisfied).configure();
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class, bootstrap::start);

                assertTrue(thrown.getMessage().contains("Unsatisfied"), thrown.toString());
                assertEquals(0, thrown.getSuppressed().length);
                assertTrue(thrown.getStackTrace()[0].getClassName().startsWith("io.vidocq.vauban."),
                        "thrown by Vauban, not made again by Vidocq: " + thrown.getStackTrace()[0]);
                List<String> warnings = records.messages(REPORT_LOGGER, Level.WARNING);
                assertEquals(1, warnings.size(), warnings.toString());
                String warning = warnings.getFirst();
                assertTrue(warning.startsWith("Vidocq startup failed in build after "), warning);
                assertTrue(warning.lines().findFirst().orElseThrow().endsWith(" ms (" + thrown.getClass().getName()
                        + "); anomalies already logged: none"), warning);
                assertTrue(warning.contains("\nVidocq startup report (partial)\n  launch      dev"), "dev: " + warning);
                assertTrue(warning.contains("\n  phases      configure "), warning);
                assertTrue(warning.contains("\nextensions\n  10  unsatisfied  not started"), warning);
                StartupReport partial = bootstrap.startupReport().orElseThrow();
                assertEquals("build", partial.failedPhase());
                assertEquals(List.of("configure", "weaving", "scan", "beforeStart"),
                        partial.phases().stream().map(Phase::name).toList());
                assertEquals(List.of(), records.messages(REPORT_LOGGER, Level.INFO), "no report of a boot that failed");
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void aFailingOnStartIsReportedOnceAndGoesThroughUntouched() {
        System.setProperty(MODE_KEY, "dev");
        System.setProperty(REPORT_KEY, "summary");
        IllegalStateException boom = new IllegalStateException("boom");
        VidocqExtension failing = new Named("second", 20) {
            @Override
            public void onStart(ExtensionContext context) {
                throw boom;
            }
        };
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap(new Named("first", 10), failing, new Named("third", 30)).configure();
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class, bootstrap::start);

                assertSame(boom, thrown);
                assertEquals(0, thrown.getSuppressed().length);
                List<String> warnings = records.messages(REPORT_LOGGER, Level.WARNING);
                assertEquals(1, warnings.size(), warnings.toString());
                assertTrue(warnings.getFirst().startsWith("Vidocq startup failed in onStart second after "),
                        warnings.getFirst());
                assertTrue(warnings.getFirst().contains("\n  extensions  first, second, third\n"), warnings.getFirst());
                StartupReport partial = bootstrap.startupReport().orElseThrow();
                assertEquals("onStart second", partial.failedPhase());
                List<Section.Line> rows = partial.section("extensions").orElseThrow().lines();
                assertTrue(((Section.Cells) rows.get(0)).cells().get(2).startsWith("onStart "), rows.toString());
                assertEquals("onStart failed", ((Section.Cells) rows.get(1)).cells().get(2));
                assertEquals("not started", ((Section.Cells) rows.get(2)).cells().get(2));
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void aFailingConfigureOutsideDevIsOneLine() {
        System.setProperty(MODE_KEY, "prod");
        IllegalStateException boom = new IllegalStateException("boom");
        VidocqExtension broken = new Named("broken", 10) {
            @Override
            public void configure(VidocqConfiguration config) {
                throw boom;
            }
        };
        try (LogRecords records = new LogRecords(REPORT_LOGGER)) {
            VidocqBootstrap bootstrap = bootstrap(broken);

            assertSame(boom, assertThrows(IllegalStateException.class, bootstrap::configure));

            List<String> warnings = records.messages(REPORT_LOGGER, Level.WARNING);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().matches("Vidocq startup failed in configure broken after \\d+ ms"
                    + " \\(java\\.lang\\.IllegalStateException\\); anomalies already logged: none"), warnings.getFirst());
            assertEquals("configure broken", bootstrap.startupReport().orElseThrow().failedPhase());
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static VidocqBootstrap bootstrap(VidocqExtension... extensions) {
        return VidocqBootstrap.create().banner(BannerMode.OFF).extensions(List.of(extensions));
    }

    private static int indexOf(List<LogRecord> records, String prefix) {
        for (int i = 0; i < records.size(); i++) {
            if (LogRecords.message(records.get(i)).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** An extension that only has a name and a priority. */
    private static class Named implements VidocqExtension {
        private final String name;
        private final int priority;

        Named(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Set<String> configKeys() {
            return Set.of();
        }
    }

    /**
     * A bean whose dependency no bean satisfies, so that the container refuses to build:
     * {@code @Dependent public class Unsatisfied { @Inject public Missing missing; }}. Written with the
     * class-file API and defined by a loader of its own, out of the test sources, where the Vauban indexer
     * would list it and every other boot of these tests would fail with it.
     */
    private static final class UnsatisfiedBean extends ClassLoader {
        private static final String PACKAGE = "fixture.report.";
        private final Map<String, byte[]> classes;

        private UnsatisfiedBean(Map<String, byte[]> classes) {
            super(StartupReportBootTest.class.getClassLoader());
            this.classes = classes;
        }

        static Class<?> load() {
            ClassDesc missing = ClassDesc.of(PACKAGE + "Missing");
            ClassDesc unsatisfied = ClassDesc.of(PACKAGE + "Unsatisfied");
            byte[] missingBytes = ClassFile.of().build(missing, type ->
                    type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT));
            byte[] unsatisfiedBytes = ClassFile.of().build(unsatisfied, type -> type
                    .withFlags(ClassFile.ACC_PUBLIC)
                    .with(annotated("jakarta.enterprise.context.Dependent"))
                    .withField("missing", missing, field -> field
                            .withFlags(ClassFile.ACC_PUBLIC)
                            .with(annotated("jakarta.inject.Inject")))
                    .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                            .return_()));
            try {
                return new UnsatisfiedBean(Map.of(PACKAGE + "Missing", missingBytes, PACKAGE + "Unsatisfied",
                        unsatisfiedBytes)).loadClass(PACKAGE + "Unsatisfied");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        private static RuntimeVisibleAnnotationsAttribute annotated(String annotation) {
            return RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of(annotation)));
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        /** Vauban reads the class file of a bean class through its loader. */
        @Override
        public InputStream getResourceAsStream(String name) {
            for (Map.Entry<String, byte[]> type : classes.entrySet()) {
                if (name.equals(type.getKey().replace('.', '/') + ".class")) {
                    return new ByteArrayInputStream(type.getValue());
                }
            }
            return super.getResourceAsStream(name);
        }
    }
}
