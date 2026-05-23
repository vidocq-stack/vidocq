package io.vidocq.humboldt.otel.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OtelApiSmokeTest {

    @Test
    void exposesCoreApiTypes() throws Exception {
        assertNotNull(Class.forName("io.opentelemetry.api.OpenTelemetry"));
        assertNotNull(Class.forName("io.opentelemetry.api.trace.Span"));
    }
}

