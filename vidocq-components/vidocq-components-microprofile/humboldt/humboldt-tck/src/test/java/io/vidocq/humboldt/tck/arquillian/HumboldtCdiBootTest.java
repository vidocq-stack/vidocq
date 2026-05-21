package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test M7b.4b.2 — vérifie qu'au {@code deploy(Archive)} le container Arquillian
 * Humboldt :
 * <ol>
 *   <li>Extrait les classes du war ShrinkWrap (ici {@link Greeter})</li>
 *   <li>Démarre Vauban CDI avec ces classes</li>
 *   <li>Démarre {@code AutoConfiguredHumboldt} avec exporter in-memory</li>
 *   <li>Enregistre cet OpenTelemetry comme {@link GlobalOpenTelemetry}</li>
 * </ol>
 *
 * <p>La résolution CDI directe ({@link VaubanContainer#current()}) est utilisée
 * en attendant le {@code TestEnricher} qui apportera {@code @Inject} en M7b.4b.4.</p>
 */
public class HumboldtCdiBootTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-cdi-boot.jar")
                .addClasses(HumboldtCdiBootTest.class, Greeter.class);
    }

    @Test
    public void vauban_container_is_booted_with_archive_beans() {
        VaubanContainer container = VaubanContainer.current();
        assertNotNull(container, "VaubanContainer.current() doit être disponible après deploy");

        Greeter g = container.select(Greeter.class);
        assertNotNull(g, "Le bean Greeter doit être résolu par Vauban");
        assertEquals(g.hello(), "bonjour");
    }

    @Test
    public void humboldt_is_booted_and_set_as_global_open_telemetry() {
        OpenTelemetry global = GlobalOpenTelemetry.get();
        assertNotNull(global, "GlobalOpenTelemetry doit être set par le container deploy");

        Tracer t = global.getTracer("io.vidocq.humboldt.tck.bootcheck");
        var span = t.spanBuilder("boot-check-span").startSpan();
        try {
            assertEquals(span.getSpanContext().getTraceId().length(), 32,
                    "Span Humboldt doit produire un traceId hex 32 chars");
        } finally {
            span.end();
        }
    }

    @ApplicationScoped
    public static class Greeter {
        public String hello() {
            return "bonjour";
        }
    }
}
