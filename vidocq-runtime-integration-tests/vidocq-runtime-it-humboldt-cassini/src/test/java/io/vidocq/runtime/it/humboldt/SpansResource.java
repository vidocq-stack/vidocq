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
 * Endpoint helper de test — expose les spans capturés par
 * {@code AutoConfiguredHumboldt.inMemorySpanExporter()} côté serveur.
 *
 * <p>Le test JUnit tourne {@code @RunAsClient} (hors container CDI), donc il
 * ne peut pas faire {@code @Inject AutoConfiguredHumboldt} directement. Cet
 * endpoint sert d'intermédiaire — le test l'appelle via HTTP après chaque
 * scénario pour récupérer les spans à assertion.</p>
 *
 * <p><b>Implémentation</b> : lookup statique via {@link GlobalOpenTelemetry#get()}
 * plutôt que {@code @Inject AutoConfiguredHumboldt}. Raison : le Deployment
 * Arquillian Vidocq utilise un bean archive distinct du runtime principal —
 * {@code HumboldtHolder} publié par {@code HumboldtExtension.beforeStart()}
 * n'est pas visible depuis le BeanManager du Deployment isolé. Le lookup
 * statique via {@code GlobalOpenTelemetry} (singleton process-wide) contourne
 * ce scoping et fonctionne dans tous les ClassLoaders. La validation
 * {@code @Inject AutoConfiguredHumboldt} dans une app vidocq standard
 * (hors Deployment Arquillian isolé) reste possible via {@code HumboldtHolder}
 * — différée en M6d.6.</p>
 *
 * <p>Format JSON minimal pour éviter d'importer un parser.</p>
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
        // HumboldtHolder.INSTANCE est publié par HumboldtExtension.beforeStart()
        // — référence statique process-wide, accessible depuis n'importe quel
        // ClassLoader incluant les Deployment Arquillian isolés.
        // GlobalOpenTelemetry.get() retourne un wrapper ObfuscatedOpenTelemetry,
        // pas l'instance brute, donc pas utilisable pour caster vers
        // AutoConfiguredHumboldt directement.
        return HumboldtHolder.INSTANCE;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
