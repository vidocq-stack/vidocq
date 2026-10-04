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
package io.vidocq.humboldt.sdk.log;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.vidocq.humboldt.sdk.log.bridge.HumboldtJulHandler;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumboldtJulHandlerTest {

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    @Test
    void publish_passes_the_thrown_exception_to_setException() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            LogRecord record = new LogRecord(Level.SEVERE, "payment {0} failed");
            record.setLoggerName("my.app");
            record.setParameters(new Object[] {"p-42"});
            record.setThrown(new IllegalStateException("boom"));
            new HumboldtJulHandler(p).publish(record);
        }

        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals("payment p-42 failed", r.body());
        assertEquals(Severity.ERROR, r.severity());
        assertEquals("my.app", r.scope().name());
        Attributes attrs = r.attributes();
        assertEquals("java.lang.IllegalStateException", attrs.get(EXCEPTION_TYPE));
        assertEquals("boom", attrs.get(EXCEPTION_MESSAGE));
        String stacktrace = attrs.get(EXCEPTION_STACKTRACE);
        assertNotNull(stacktrace, "exception.stacktrace must be set");
        assertTrue(stacktrace.startsWith("java.lang.IllegalStateException: boom"), stacktrace);
    }

    @Test
    void publish_without_a_thrown_exception_adds_no_exception_attribute() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            LogRecord record = new LogRecord(Level.INFO, "all good");
            record.setLoggerName("my.app");
            new HumboldtJulHandler(p).publish(record);
        }

        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals("all good", r.body());
        assertNull(r.attributes().get(EXCEPTION_TYPE));
        assertTrue(r.attributes().isEmpty(), "no attribute for a record without a thrown exception");
    }
}
