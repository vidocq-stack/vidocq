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
 * Test M7b.4b.4 — vérifie l'injection {@code @Inject} sur la classe de test
 * via {@link HumboldtCdiEnricher} :
 * <ul>
 *   <li>{@code @Inject OpenTelemetry} → résolu via {@code GlobalOpenTelemetry.get()}</li>
 *   <li>{@code @Inject MyCdiService} → résolu via le BeanManager Vauban</li>
 * </ul>
 *
 * <p>C'est l'équivalent de ce que fait {@code OpenTelemetryBeanTest} du TCK
 * officiel — sans cet enricher, les champs resteraient {@code null}.</p>
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
                "@Inject OpenTelemetry doit être résolu par HumboldtCdiEnricher");

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
                "@Inject MyCdiService doit être résolu via le BeanManager Vauban");
        assertEquals(cdiService.greet(), "salut depuis CDI");
    }

    @ApplicationScoped
    public static class MyCdiService {
        public String greet() {
            return "salut depuis CDI";
        }
    }
}
