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

import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The activation of the startup report as {@link VidocqBootstrap} performs it: {@code vidocq.startup.report}
 * and {@code vidocq.launch.mode} read in {@code configure()}, their invalid values, and the audit of the
 * keys around them. Every anomaly is a WARNING of {@value #ANOMALY_LOGGER}, its code first.
 */
class StartupReportActivationTest {

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

    @Test
    void anInvalidReportLevelIsReportedAndReadAsAuto() {
        System.setProperty(REPORT_KEY, "detaild");
        System.setProperty(MODE_KEY, "prod");
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure();

            assertEquals(List.of("[VIDOCQ-CFG-001] Invalid value 'detaild' for vidocq.startup.report"
                    + " (auto, off, summary, detailed): using auto"), anomalies.warnings());
            assertEquals(Verbosity.SUMMARY, bootstrap.verbosity(), "auto in prod");
        }
    }

    @Test
    void anInvalidLaunchModeIsReportedAndDetected() {
        System.setProperty(MODE_KEY, "staging");
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure();

            assertEquals(List.of("[VIDOCQ-CFG-001] Invalid value 'staging' for vidocq.launch.mode"
                    + " (auto, dev, test, prod): using auto"), anomalies.warnings());
            assertEquals(LaunchMode.TEST, bootstrap.launchMode(), "detected: JUnit runs this boot");
        }
    }

    @Test
    void autoWrittenOutIsNoAnomaly() {
        System.setProperty(REPORT_KEY, "auto");
        System.setProperty(MODE_KEY, "auto");
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure();

            assertEquals(List.of(), anomalies.warnings());
            assertEquals(LaunchMode.TEST, bootstrap.launchMode());
            assertEquals(Verbosity.SUMMARY, bootstrap.verbosity(), "auto in test");
        }
    }

    @Test
    void anEmbeddedDeploymentHasNoReportUnlessOneIsAskedFor() {
        System.setProperty(MODE_KEY, "dev");
        VidocqBootstrap byDefault = VidocqBootstrap.create().banner(BannerMode.OFF).configure(List.of());
        System.setProperty(REPORT_KEY, "summary");
        VidocqBootstrap askedFor = VidocqBootstrap.create().banner(BannerMode.OFF).configure(List.of());

        assertEquals(Verbosity.OFF, byDefault.verbosity());
        assertEquals(Verbosity.SUMMARY, askedFor.verbosity());
    }

    @Test
    void devIsDetailedOnTheFirstBootOfTheJvmAndSummaryOnReloads() {
        System.setProperty(MODE_KEY, "dev");
        VidocqBootstrap.forgetEarlierBoots();

        VidocqBootstrap first = VidocqBootstrap.create().banner(BannerMode.OFF).configure();
        VidocqBootstrap reload = VidocqBootstrap.create().banner(BannerMode.OFF).configure();
        System.setProperty(REPORT_KEY, "detailed");
        VidocqBootstrap forced = VidocqBootstrap.create().banner(BannerMode.OFF).configure();

        assertEquals(Verbosity.DETAILED, first.verbosity());
        assertEquals(Verbosity.SUMMARY, reload.verbosity());
        assertEquals(Verbosity.DETAILED, forced.verbosity(), "an explicit level is honoured on every boot");
    }

    @Test
    void aBootstrapNotYetConfiguredShowsNothing() {
        assertEquals(Verbosity.OFF, VidocqBootstrap.create().verbosity());
    }

    @Test
    void theCoreDeclaresTheKeysItReads() {
        assertTrue(VidocqBootstrap.CORE_CONFIG_KEYS.containsAll(Set.of(REPORT_KEY, MODE_KEY)),
                VidocqBootstrap.CORE_CONFIG_KEYS.toString());
    }

    @Test
    void aTypoInTheReportKeyIsReportedByTheAudit() {
        System.setProperty(TYPO_KEY, "detailed");
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure().start();
            try {
                assertTrue(anomalies.warnings().contains("[VIDOCQ-CFG-003] Configuration key 'vidocq.startup.reprot'"
                        + " is read by nothing and has no effect. Known keys in this namespace: vidocq.startup.report"),
                        anomalies.warnings().toString());
            } finally {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    void anAuditThatFailsIsAWarningAndNeverFailsTheBoot() {
        VidocqExtension broken = new VidocqExtension() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public Set<String> configKeys() {
                throw new IllegalStateException("no keys today");
            }
        };
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap.auditConfigKeys(new VidocqConfigImpl(), List.of(broken));

            assertEquals(List.of("[VIDOCQ-CFG-002] Configuration key audit failed:"
                    + " java.lang.IllegalStateException: no keys today; keys that nothing reads are not reported"),
                    anomalies.warnings());
        }
    }

    @Test
    void aLineBreakInAValueOrAKeyNeverForgesARecord() {
        String forgedKey = "vidocq.startup.re\nport";
        System.setProperty(REPORT_KEY, "x\n[WARN ][2026-09-18 14:19:46.775][main] : forged");
        System.setProperty(forgedKey, "detailed");
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure().start();
            try {
                List<String> warnings = anomalies.warnings();
                assertEquals(List.of(
                        "[VIDOCQ-CFG-001] Invalid value 'x?[WARN ][2026-09-18 14:19:46.775][main] : forged' for"
                                + " vidocq.startup.report (auto, off, summary, detailed): using auto",
                        "[VIDOCQ-CFG-003] Configuration key 'vidocq.startup.re?port' is read by nothing and has no"
                                + " effect. Known keys in this namespace: vidocq.startup.report"), warnings);
                assertTrue(warnings.stream().allMatch(warning -> warning.lines().count() == 1), warnings.toString());
                assertTrue(bootstrap.startupReport().orElseThrow().anomalies().stream()
                                .noneMatch(anomaly -> anomaly.message().contains("\n")),
                        "the report keeps the anomalies as they were logged");
            } finally {
                bootstrap.shutdown();
            }
        } finally {
            System.clearProperty(forgedKey);
        }
    }

    @Test
    void theFailureOfTheAuditIsCleanedToo() {
        VidocqExtension broken = new VidocqExtension() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public Set<String> configKeys() {
                throw new IllegalStateException("no keys\n[WARN ] forged");
            }
        };
        try (Anomalies anomalies = new Anomalies()) {
            VidocqBootstrap.auditConfigKeys(new VidocqConfigImpl(), List.of(broken));

            assertEquals(List.of("[VIDOCQ-CFG-002] Configuration key audit failed:"
                    + " java.lang.IllegalStateException: no keys?[WARN ] forged; keys that nothing reads are not"
                    + " reported"), anomalies.warnings());
        }
    }

    /** The WARNING records of {@value #ANOMALY_LOGGER}, while open. */
    static final class Anomalies implements AutoCloseable {
        private final Logger logger = Logger.getLogger(ANOMALY_LOGGER);
        private final List<LogRecord> records = new ArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (records) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        Anomalies() {
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        List<String> warnings() {
            SimpleFormatter formatter = new SimpleFormatter();
            synchronized (records) {
                return records.stream().filter(r -> r.getLevel() == Level.WARNING).map(formatter::formatMessage)
                        .toList();
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }
}
