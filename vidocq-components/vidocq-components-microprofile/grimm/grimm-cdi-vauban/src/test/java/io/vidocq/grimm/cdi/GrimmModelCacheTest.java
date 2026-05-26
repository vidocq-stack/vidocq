package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.config.GrimmConfig;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimmModelCacheTest {

    @Test
    void getDocument_returnsDefaultSkeletonWhenUninitialized() {
        GrimmModelCache cache = new GrimmModelCache();
        OpenAPI doc = cache.getDocument();
        assertNotNull(doc);
        assertEquals("3.1.0", doc.getOpenapi());
    }

    @Test
    void initialize_buildsDocumentFromAnnotatedTypes() {
        GrimmModelCache cache = new GrimmModelCache();
        cache.initialize(GrimmConfig.defaults(), List.of(PetsResource.class));

        OpenAPI doc = cache.getDocument();
        assertNotNull(doc.getPaths());
        assertTrue(doc.getPaths().hasPathItem("/pets"));
        assertNotNull(doc.getPaths().getPathItem("/pets").getGET());
    }

    @Path("/pets")
    @Produces("application/json")
    static class PetsResource {
        @GET
        public String list() {
            return "[]";
        }
    }
}

