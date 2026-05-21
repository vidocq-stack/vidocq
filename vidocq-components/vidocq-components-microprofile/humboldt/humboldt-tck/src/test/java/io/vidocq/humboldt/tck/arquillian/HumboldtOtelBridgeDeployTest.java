package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test M7b.4b.3 — reproduit exactement le pattern utilisé par le TCK officiel
 * MP Telemetry (cf. décompilation {@code ExporterSpiTest.createDeployment()}) :
 *
 * <ol>
 *   <li>Inclut dans le war une implémentation de {@link SpanExporter} (in-memory)
 *       et de {@link ConfigurableSpanExporterProvider}</li>
 *   <li>Enregistre le provider via {@code addAsServiceProvider(ConfigurableSpanExporterProvider.class, ...)}</li>
 *   <li>Configure {@code otel.traces.exporter=in-memory} dans
 *       {@code META-INF/microprofile-config.properties}</li>
 *   <li>Le container Arquillian Humboldt doit alors :
 *     <ul>
 *       <li>Parser le microprofile-config.properties</li>
 *       <li>Charger {@link TckInMemorySpanExporterProvider} via le services file</li>
 *       <li>Instancier l'exporter, le wrapper dans {@code OtelSpanExporterBridge}</li>
 *       <li>L'attacher au pipeline Humboldt (via le hook M7b.3)</li>
 *     </ul>
 *   </li>
 *   <li>Les spans produits par {@link GlobalOpenTelemetry#get()} doivent donc
 *       atterrir dans {@link TckInMemorySpanExporter#SPANS}</li>
 * </ol>
 */
public class HumboldtOtelBridgeDeployTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-otel-bridge.jar")
                .addClasses(HumboldtOtelBridgeDeployTest.class,
                        TckInMemorySpanExporter.class,
                        TckInMemorySpanExporterProvider.class)
                .addAsServiceProvider(ConfigurableSpanExporterProvider.class,
                        TckInMemorySpanExporterProvider.class)
                .addAsResource(new StringAsset(
                                "otel.traces.exporter=in-memory\n" +
                                        "otel.service.name=humboldt-tck-bridge-test\n"),
                        "META-INF/microprofile-config.properties");
    }

    @Test
    public void spans_from_global_open_telemetry_arrive_in_tck_in_memory_exporter() {
        TckInMemorySpanExporter.SPANS.clear();
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("io.vidocq.tck.bridge.assert");
        Span s = tracer.spanBuilder("hello-bridge").startSpan();
        s.end();

        List<SpanData> captured = TckInMemorySpanExporter.SPANS;
        assertEquals(captured.size(), 1, "Le span doit atterrir dans l'exporter TCK via le bridge");
        SpanData first = captured.getFirst();
        assertNotNull(first);
        assertEquals(first.getName(), "hello-bridge");
        // service.name vient bien de microprofile-config.properties
        assertEquals(first.getResource().getAttribute(
                io.opentelemetry.api.common.AttributeKey.stringKey("service.name")),
                "humboldt-tck-bridge-test");
    }

    /** Mime exactly the TCK pattern : implements io.opentelemetry.sdk.trace.export.SpanExporter. */
    public static class TckInMemorySpanExporter implements SpanExporter {
        static final List<SpanData> SPANS = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            SPANS.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

        @Override
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    /** Mime exactly the TCK pattern : implements ConfigurableSpanExporterProvider. */
    public static class TckInMemorySpanExporterProvider implements ConfigurableSpanExporterProvider {
        @Override
        public SpanExporter createExporter(ConfigProperties config) {
            return new TckInMemorySpanExporter();
        }

        @Override
        public String getName() { return "in-memory"; }
    }
}
