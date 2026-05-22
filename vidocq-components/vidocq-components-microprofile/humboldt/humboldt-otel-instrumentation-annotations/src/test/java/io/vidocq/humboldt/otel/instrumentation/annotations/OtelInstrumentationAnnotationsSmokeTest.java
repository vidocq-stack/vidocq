package io.vidocq.humboldt.otel.instrumentation.annotations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OtelInstrumentationAnnotationsSmokeTest {

    @Test
    void exposesWithSpanAnnotation() throws Exception {
        assertNotNull(Class.forName("io.opentelemetry.instrumentation.annotations.WithSpan"));
    }
}

