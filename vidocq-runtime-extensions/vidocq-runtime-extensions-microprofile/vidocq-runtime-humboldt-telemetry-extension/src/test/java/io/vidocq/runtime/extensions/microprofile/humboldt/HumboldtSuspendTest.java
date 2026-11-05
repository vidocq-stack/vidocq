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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.runtime.spi.Suspendable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CRaC checkpoint refuses a process with an open socket, and the OTLP exporters keep their connections to the
 * collector: the extension lets them go on {@code suspend()} and takes them back on {@code resume()}
 * (BUG-20261105-03).
 */
class HumboldtSuspendTest {

    /** The logger of Humboldt's OTLP sender, which warns when a released exporter drops a batch. */
    private static final Logger SENDER_LOG =
            Logger.getLogger("io.vidocq.humboldt.exporter.otlp.http.internal.OtlpHttpJsonSender");

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final Handler recorder = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(logRecord.getMessage());
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to close
        }
    };
    private AutoConfiguredHumboldt humboldt;

    @BeforeEach
    void recordWarnings() {
        SENDER_LOG.addHandler(recorder);
    }

    @AfterEach
    void stop() {
        SENDER_LOG.removeHandler(recorder);
        if (humboldt != null) {
            humboldt.close();
        }
    }

    /** OTLP traces to a port nothing listens on: each export fails, after its retries. */
    private HumboldtExtension extensionWithAnOtlpExporter() {
        humboldt = HumboldtAutoConfigure.configure(EnvConfig.of(Map.of(
                "OTEL_SDK_DISABLED", "false",
                "OTEL_TRACES_EXPORTER", "otlp",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none",
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9"), Map.of()));
        HumboldtExtension extension = new HumboldtExtension();
        extension.humboldt(humboldt);
        return extension;
    }

    private void exportOneSpan() {
        humboldt.getTracerProvider().get("suspend-test").spanBuilder("GET /parts").startSpan().end();
        humboldt.flush().join(10, TimeUnit.SECONDS);
    }

    private boolean aBatchWasDroppedForTheCheckpoint() {
        return warnings.stream().anyMatch(m -> m.contains("released for a checkpoint"));
    }

    @Test
    void theExtensionIsSuspendable() {
        assertInstanceOf(Suspendable.class, new HumboldtExtension());
    }

    @Test
    void suspendReleasesTheOtlpExportersAndResumeTakesThemBack() {
        HumboldtExtension extension = extensionWithAnOtlpExporter();

        extension.suspend();
        exportOneSpan();
        assertTrue(aBatchWasDroppedForTheCheckpoint(), "a suspended exporter does not connect: " + warnings);

        warnings.clear();
        extension.resume();
        exportOneSpan();
        assertFalse(aBatchWasDroppedForTheCheckpoint(), "a resumed exporter connects again: " + warnings);
    }

    @Test
    void anExtensionThatDidNotStartHasNothingToSuspend() {
        HumboldtExtension extension = new HumboldtExtension();

        assertDoesNotThrow(() -> {
            extension.suspend();
            extension.resume();
        });
    }
}
