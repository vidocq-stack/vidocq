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
package io.vidocq.humboldt.exporter.otlp.http;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonLogEncoder;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpJsonLogEncoderTest {

    @Test
    void encodes_the_event_name_as_the_otlp_eventName_field() {
        String json = OtlpJsonLogEncoder.encode(List.of(record("checkout.completed", Attributes.empty())));

        assertTrue(json.contains("\"eventName\":\"checkout.completed\""), json);
    }

    @Test
    void omits_eventName_for_a_plain_log_record() {
        String json = OtlpJsonLogEncoder.encode(List.of(record("", Attributes.empty())));

        assertFalse(json.contains("eventName"), json);
    }

    static LogRecordData record(String eventName, Attributes attributes) {
        return new LogRecordData(
                Resource.empty(), InstrumentationScope.of("io.vidocq.test"),
                1_000L, 2_000L, SpanContext.getInvalid(),
                Severity.INFO, "INFO", "done", attributes, eventName);
    }
}
