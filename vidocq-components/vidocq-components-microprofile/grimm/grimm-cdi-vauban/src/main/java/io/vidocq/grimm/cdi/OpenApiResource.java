package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.OpenApiSerializers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * JAX-RS resource serving the assembled OpenAPI document at {@code /openapi}
 * (spec §2.1).
 *
 * <ul>
 *   <li>Content negotiation: {@code application/yaml} (default per spec §2.2),
 *       {@code application/json}.</li>
 *   <li>Spec §2.3 — the {@code ?format=} query parameter overrides the
 *       {@code Accept} header. Accepted values: {@code json}, {@code yaml},
 *       {@code yml}.</li>
 * </ul>
 *
 * <p>Reads from {@link GrimmModelCache}; never recomputes per request.</p>
 */
@Path("/openapi")
@ApplicationScoped
public class OpenApiResource {

    static final String MEDIA_TYPE_JSON = "application/json";
    static final String MEDIA_TYPE_YAML = "application/yaml";

    private final Supplier<OpenAPI> documentSupplier;

    /** CDI constructor — reads the model from the shared {@link GrimmModelCache}. */
    @Inject
    public OpenApiResource(GrimmModelCache cache) {
        this(cache::getDocument);
    }

    /** No-arg fallback for proxy / legacy bootstrap use. */
    public OpenApiResource() {
        this(() -> OASFactory.createObject(OpenAPI.class).openapi("3.1.0"));
    }

    /** Test/internal constructor — accepts any document supplier. */
    OpenApiResource(Supplier<OpenAPI> documentSupplier) {
        this.documentSupplier = Objects.requireNonNull(documentSupplier, "documentSupplier must not be null");
    }

    /**
     * Returns the OpenAPI document in the negotiated format.
     *
     * @param format the {@code ?format=} override (spec §2.3) — {@code json}/{@code yaml}/{@code yml}
     * @param accept the {@code Accept} header
     */
    @GET
    @Produces({MEDIA_TYPE_YAML, MEDIA_TYPE_JSON})
    public Response getOpenApi(
            @QueryParam("format") String format,
            @HeaderParam("Accept") String accept) {
        RenderedDocument rendered = render(format, accept);
        return Response.ok(rendered.body(), rendered.mediaType()).build();
    }

    /**
     * Renders the document; exposed for direct (non-JAX-RS) callers and unit tests.
     */
    public RenderedDocument render(String format, String accept) {
        OutputFormat outputFormat = resolveOutputFormat(format, accept);
        OpenAPI document = documentSupplier.get();
        if (outputFormat == OutputFormat.JSON) {
            return new RenderedDocument(OpenApiSerializers.toJson(document), MEDIA_TYPE_JSON);
        }
        return new RenderedDocument(OpenApiSerializers.toYaml(document), MEDIA_TYPE_YAML);
    }

    private OutputFormat resolveOutputFormat(String format, String accept) {
        if (format != null) {
            String normalized = format.toLowerCase(Locale.ROOT);
            if ("json".equals(normalized)) return OutputFormat.JSON;
            if ("yaml".equals(normalized) || "yml".equals(normalized)) return OutputFormat.YAML;
        }
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains(MEDIA_TYPE_JSON)) {
            return OutputFormat.JSON;
        }
        return OutputFormat.YAML;
    }

    private enum OutputFormat { JSON, YAML }

    public record RenderedDocument(String body, String mediaType) {}
}


