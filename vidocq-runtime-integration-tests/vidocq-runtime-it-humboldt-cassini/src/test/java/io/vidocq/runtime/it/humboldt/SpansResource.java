package io.vidocq.runtime.it.humboldt;

import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.runtime.ext.humboldt.HumboldtHolder;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Endpoint test helper — exposes spans captured by
 * {@code AutoConfiguredHumboldt.inMemorySpanExporter()} on the server side.
 *
 * <p>The JUnit test runs {@code @RunAsClient} (excluding CDI container), so it
 * can't do {@code @Inject AutoConfiguredHumboldt} directly. This
 * endpoint serves as an intermediary — the test calls it via HTTP after each
 * scenario to retrieve assertion spans.</p>
 *
 * <p><b>Implementation</b>: static lookup via {@link GlobalOpenTelemetry#get()}
 * rather than {@code @Inject AutoConfiguredHumboldt}. Reason: Deployment
 * Arquillian Vidocq uses an archive bean separate from the main runtime —
 * {@code HumboldtHolder} published by {@code HumboldtExtension.beforeStart()}
 * is not visible from the BeanManager of the isolated Deployment. The lookup
 * static via {@code GlobalOpenTelemetry} (singleton process-wide) bypasses
 * this scoping and works in all ClassLoaders. Validation
 * {@code @Inject AutoConfiguredHumboldt} in a standard vidocq app
 * (excluding isolated Arquillian Deployment) remains possible via {@code HumboldtHolder}
 * — deferred to M6d.6.</p>
 *
 * <p>Minimal JSON format to avoid importing a parser.</p>
 */
@Path("/__spans")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class SpansResource {

    @GET
    public Response list() {
        AutoConfiguredHumboldt humboldt = humboldt();
        if (humboldt == null || humboldt.inMemorySpanExporter() == null) {
            return Response.status(503).entity("[\"humboldt-not-configured-in-memory\"]").build();
        }
        List<SpanData> spans = humboldt.inMemorySpanExporter().getFinishedSpans();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) sb.append(',');
            SpanData s = spans.get(i);
            sb.append("{\"name\":\"").append(esc(s.name())).append('"');
            sb.append(",\"kind\":\"").append(s.kind()).append('"');
            sb.append(",\"traceId\":\"").append(s.spanContext().getTraceId()).append('"');
            sb.append(",\"spanId\":\"").append(s.spanContext().getSpanId()).append('"');
            sb.append(",\"parentSpanId\":\"")
                    .append(s.parentSpanContext() != null && s.parentSpanContext().isValid()
                            ? s.parentSpanContext().getSpanId() : "")
                    .append('"');
            sb.append(",\"status\":\"").append(s.status().code()).append('"');
            sb.append(",\"statusMsg\":\"").append(esc(s.status().description())).append('"');
            Long httpStatus = s.attributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code"));
            if (httpStatus != null) {
                sb.append(",\"httpStatus\":").append(httpStatus);
            }
            String urlPath = s.attributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("url.path"));
            if (urlPath != null) {
                sb.append(",\"urlPath\":\"").append(esc(urlPath)).append('"');
            }
            sb.append('}');
        }
        sb.append(']');
        return Response.ok(sb.toString()).build();
    }

    @DELETE
    public Response reset() {
        AutoConfiguredHumboldt humboldt = humboldt();
        if (humboldt != null && humboldt.inMemorySpanExporter() != null) {
            humboldt.inMemorySpanExporter().reset();
        }
        return Response.noContent().build();
    }

    private static AutoConfiguredHumboldt humboldt() {
        // HumboldtHolder.INSTANCE is released by HumboldtExtension.beforeStart()
        // — process-wide static reference, accessible from any
        // ClassLoader including isolated Arquillian Deployments.
        // GlobalOpenTelemetry.get() returns an ObfuscatedOpenTelemetry wrapper,
        // not the raw instance, therefore not usable to cast to
        // AutoConfiguredHumboldt directly.
        return HumboldtHolder.INSTANCE;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
