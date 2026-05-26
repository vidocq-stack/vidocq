package io.vidocq.grimm.cdi;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiResourceTest {

    @Test
    void getOpenApi_usesFormatQueryParameterOverAcceptHeader() {
        // Spec §2.3: format query parameter overrides Accept negotiation.
        OpenApiResource resource = new OpenApiResource(sampleModelSupplier());

        var response = resource.getOpenApi("json", "application/yaml");

        assertEquals("application/json", response.mediaType());
        assertNotNull(response.body());
        assertTrue(response.body().contains("\"openapi\""));
    }

    @Test
    void getOpenApi_defaultsToYamlWhenNoFormatAndNoAccept() {
        // Spec §2.2: YAML is the default representation.
        OpenApiResource resource = new OpenApiResource(sampleModelSupplier());

        var response = resource.getOpenApi(null, null);

        assertEquals("application/yaml", response.mediaType());
        assertNotNull(response.body());
        assertTrue(response.body().contains("openapi:"));
    }

    @Test
    void getOpenApi_usesAcceptHeaderWhenFormatParameterIsMissing() {
        // Spec §2.2: Accept header drives representation when format is absent.
        OpenApiResource resource = new OpenApiResource(sampleModelSupplier());

        var response = resource.getOpenApi(null, "application/json");

        assertEquals("application/json", response.mediaType());
    }

    private Supplier<OpenAPI> sampleModelSupplier() {
        return () -> {
            Info info = OASFactory.createObject(Info.class)
                .title("M2 API")
                .version("1.0.0");
            return OASFactory.createObject(OpenAPI.class)
                .openapi("3.1.0")
                .info(info);
        };
    }
}


