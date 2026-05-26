package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.OpenApiSerializers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serves the OpenAPI document as JSON or YAML.
 */
@Path("/openapi")
@ApplicationScoped
public class OpenApiResource {

    static final String MEDIA_TYPE_JSON = "application/json";
    static final String MEDIA_TYPE_YAML = "application/yaml";

    private final Supplier<OpenAPI> documentSupplier;

    public OpenApiResource() {
        this(() -> OASFactory.createObject(OpenAPI.class).openapi("3.1.0"));
    }

    OpenApiResource(Supplier<OpenAPI> documentSupplier) {
        this.documentSupplier = Objects.requireNonNull(documentSupplier, "documentSupplier must not be null");
    }

    public RenderedDocument getOpenApi(
        @QueryParam("format") String format,
        @HeaderParam("Accept") String accept
    ) {
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
            if ("json".equals(normalized)) {
                return OutputFormat.JSON;
            }
            if ("yaml".equals(normalized) || "yml".equals(normalized)) {
                return OutputFormat.YAML;
            }
        }

        if (accept != null && accept.toLowerCase(Locale.ROOT).contains(MEDIA_TYPE_JSON)) {
            return OutputFormat.JSON;
        }

        return OutputFormat.YAML;
    }

    private enum OutputFormat {
        JSON,
        YAML
    }

    public record RenderedDocument(String body, String mediaType) {
    }
}


