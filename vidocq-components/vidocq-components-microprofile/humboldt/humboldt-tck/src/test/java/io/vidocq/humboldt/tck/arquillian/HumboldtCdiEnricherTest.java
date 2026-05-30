package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test M7b.4b.4 — verifies {@code @Inject} injection on the test class
 * through {@link HumboldtCdiEnricher}:
 * <ul>
 *   <li>{@code @Inject OpenTelemetry} → resolved through {@code GlobalOpenTelemetry.get()}</li>
 *   <li>{@code @Inject MyCdiService} → resolved through the Vauban BeanManager</li>
 * </ul>
 *
 * <p>This is equivalent to what the official TCK's {@code OpenTelemetryBeanTest}
 * does — without this enricher, the fields would remain {@code null}.</p>
 */
public class HumboldtCdiEnricherTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-cdi-enricher.jar")
                .addClasses(HumboldtCdiEnricherTest.class, MyCdiService.class);
    }

    @Inject
    private OpenTelemetry openTelemetry;

    @Inject
    private MyCdiService cdiService;

    @Test
    public void open_telemetry_is_injected() {
        assertNotNull(openTelemetry,
                "@Inject OpenTelemetry must be resolved by HumboldtCdiEnricher");

        Tracer t = openTelemetry.getTracer("io.vidocq.tck.enricher");
        var span = t.spanBuilder("enriched").startSpan();
        try {
            assertEquals(span.getSpanContext().getTraceId().length(), 32);
        } finally {
            span.end();
        }
    }

    @Test
    public void cdi_bean_is_injected() {
        assertNotNull(cdiService,
                "@Inject MyCdiService must be resolved via Vauban BeanManager");
        assertEquals(cdiService.greet(), "hello from CDI");
    }

    @ApplicationScoped
    public static class MyCdiService {
        public String greet() {
            return "hello from CDI";
        }
    }
}
