package io.vidocq.grimm.tck;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNotNull;

/**
 * Smoke test for M10 bootstrap.
 *
 * Validates that:
 * 1) MP OpenAPI API is available;
 * 2) Grimm's OASFactoryResolver wiring is active on classpath.
 */
class GrimmTckSmokeTest {

    @Test
    void microprofileOpenApiApiAndFactoryResolverAreAvailable() {
        assertNotNull(OpenAPIDefinition.class.getAnnotation(java.lang.annotation.Retention.class),
                "@OpenAPIDefinition must be loadable with runtime retention");

        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        assertNotNull(model, "OASFactory must resolve Grimm OASFactoryResolver");
    }
}


