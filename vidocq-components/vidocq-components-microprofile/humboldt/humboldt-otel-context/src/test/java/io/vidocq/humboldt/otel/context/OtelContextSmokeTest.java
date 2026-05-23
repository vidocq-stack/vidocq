package io.vidocq.humboldt.otel.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OtelContextSmokeTest {

    @Test
    void exposesContextTypes() throws Exception {
        assertNotNull(Class.forName("io.opentelemetry.context.Context"));
        assertNotNull(Class.forName("io.opentelemetry.context.ContextStorageProvider"));
    }
}

