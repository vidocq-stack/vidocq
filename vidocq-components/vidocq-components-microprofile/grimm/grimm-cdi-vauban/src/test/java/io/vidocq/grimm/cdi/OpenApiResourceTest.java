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

        var response = resource.render("json", "application/yaml");

        assertEquals("application/json", response.mediaType());
        assertNotNull(response.body());
        assertTrue(response.body().contains("\"openapi\""));
    }

    @Test
    void getOpenApi_defaultsToYamlWhenNoFormatAndNoAccept() {
        // Spec §2.2: YAML is the default representation.
        OpenApiResource resource = new OpenApiResource(sampleModelSupplier());

        var response = resource.render(null, null);

        assertEquals("application/yaml", response.mediaType());
        assertNotNull(response.body());
        assertTrue(response.body().contains("openapi:"));
    }

    @Test
    void getOpenApi_usesAcceptHeaderWhenFormatParameterIsMissing() {
        // Spec §2.2: Accept header drives representation when format is absent.
        OpenApiResource resource = new OpenApiResource(sampleModelSupplier());

        var response = resource.render(null, "application/json");

        assertEquals("application/json", response.mediaType());
    }

    @Test
    void getOpenApi_returnsJaxRsResponseWithExpectedMediaType() {
        // Keep this test runtime-provider free: validate endpoint metadata by reflection.
        assertNotNull(OpenApiResource.class.getAnnotation(jakarta.ws.rs.Path.class));
        try {
            var method = OpenApiResource.class.getMethod("getOpenApi", String.class, String.class);
            assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
            var produces = method.getAnnotation(jakarta.ws.rs.Produces.class);
            assertNotNull(produces);
            assertTrue(java.util.Arrays.asList(produces.value()).contains("application/json"));
            assertTrue(java.util.Arrays.asList(produces.value()).contains("application/yaml"));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
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


