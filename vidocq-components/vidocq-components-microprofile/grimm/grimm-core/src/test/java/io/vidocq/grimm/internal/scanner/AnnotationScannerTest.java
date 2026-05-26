package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AnnotationScanner}.
 *
 * Spec §3.3 / §3.4 / §3.5: Annotation scanning tests.
 */
class AnnotationScannerTest {

    @Test
    void scanClasses_scansOpenAPIDefinition() {
        // Spec §3.4: @OpenAPIDefinition should populate document info
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DocumentLevelClass.class));

        assertNotNull(model);
        assertNotNull(model.getInfo());
        assertEquals("My API", model.getInfo().getTitle());
        assertEquals("1.0.0", model.getInfo().getVersion());
    }

    @Test
    void scanClasses_respectsScanConfig() {
        // Spec §4.1: ScanConfig should filter which classes are scanned
        ScanConfig config = new ScanConfig(
            false,
            Set.of("com.example"),
            Set.of(),
            Set.of(),
            Set.of()
        );
        AnnotationScanner scanner = new AnnotationScanner(config);

        // DocumentLevelClass is not in com.example, so it should be skipped
        OpenAPI model = scanner.scanClasses(List.of(DocumentLevelClass.class));

        // Since the class is filtered, info should not be set
        assertTrue(model.getInfo() == null || model.getInfo().getTitle() == null);
    }

    @Test
    void scanClasses_scansTagAnnotations() {
        // Spec §3.5: @Tag annotations should be added to the model
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(TaggedClass.class));

        assertNotNull(model);
        assertTrue(model.getTags() != null && model.getTags().size() > 0);
        assertTrue(model.getTags().stream().anyMatch(t -> "pets".equals(t.getName())));
    }

    @Test
    void scanClasses_scansServerAnnotations() {
        // Spec §3.5: @Server annotations should be added to the model
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ServerClass.class));

        assertNotNull(model);
        assertTrue(model.getServers() != null && model.getServers().size() > 0);
        assertTrue(model.getServers().stream().anyMatch(s -> "https://api.example.com".equals(s.getUrl())));
    }

    @Test
    void scanClasses_scansExternalDocumentationAnnotation() {
        // Spec §3.4: class-level @ExternalDocumentation populates document externalDocs.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ExternalDocsClass.class));

        assertNotNull(model);
        assertNotNull(model.getExternalDocs());
        assertEquals("https://example.com/docs", model.getExternalDocs().getUrl());
        assertEquals("API documentation", model.getExternalDocs().getDescription());
    }

    @Test
    void scanClasses_scansExternalDocumentationFromOpenApiDefinition() {
        // Spec §3.4: @OpenAPIDefinition.externalDocs contributes to top-level externalDocs.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(OpenApiDefinitionExternalDocsClass.class));

        assertNotNull(model);
        assertNotNull(model.getExternalDocs());
        assertEquals("https://example.com/definition-docs", model.getExternalDocs().getUrl());
        assertEquals("Definition docs", model.getExternalDocs().getDescription());
    }

    @Test
    void scanClasses_scansMultipleClasses() {
        // Multiple classes should all be processed
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DocumentLevelClass.class, TaggedClass.class));

        assertNotNull(model);
        assertNotNull(model.getInfo());
        assertEquals("My API", model.getInfo().getTitle());
        assertTrue(model.getTags() != null && model.getTags().size() > 0);
    }

    @Test
    void scanClasses_collectsMethodLevelTagsAndServers() {
        // Spec §3.5: method-level annotations contribute to top-level document tags/servers.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(MethodLevelClass.class));

        assertNotNull(model.getTags());
        assertTrue(model.getTags().stream().anyMatch(tag -> "method-tag".equals(tag.getName())));
        assertNotNull(model.getServers());
        assertTrue(model.getServers().stream().anyMatch(server -> "https://method.example.com".equals(server.getUrl())));
    }

    @Test
    void scanClasses_deduplicatesTagsAndServers() {
        // Spec §3.5: same semantic entry should not be duplicated when discovered multiple times.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DuplicateAnnotationsClass.class));

        assertNotNull(model.getTags());
        long petsTagCount = model.getTags().stream().filter(tag -> "pets".equals(tag.getName())).count();
        assertEquals(1, petsTagCount);

        assertNotNull(model.getServers());
        long serverCount = model.getServers().stream()
            .filter(server -> "https://api.example.com".equals(server.getUrl()))
            .count();
        assertEquals(1, serverCount);
    }

    @Test
    void scanClasses_mapsOpenApiDefinitionTagsServersAndSecurity() {
        // Spec §3.4: @OpenAPIDefinition contributes tags, servers and security requirements.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DefinitionWithTagsServersSecurity.class));

        assertNotNull(model.getTags());
        assertTrue(model.getTags().stream().anyMatch(tag -> "admin".equals(tag.getName())));
        assertNotNull(model.getServers());
        assertTrue(model.getServers().stream().anyMatch(server -> "https://definition.example.com".equals(server.getUrl())));
        assertNotNull(model.getSecurity());
        assertTrue(model.getSecurity().stream().anyMatch(requirement -> requirement.getScheme("oauth2") != null));
    }

    @OpenAPIDefinition(
        info = @Info(
            title = "My API",
            version = "1.0.0",
            description = "A test API"
        )
    )
    static class DocumentLevelClass {
    }

    @Tag(name = "pets", description = "Pet operations")
    static class TaggedClass {
    }

    @Server(url = "https://api.example.com", description = "Production server")
    static class ServerClass {
    }

    @ExternalDocumentation(url = "https://example.com/docs", description = "API documentation")
    static class ExternalDocsClass {
    }

    @OpenAPIDefinition(
        info = @Info(title = "Definition API", version = "1.0.0"),
        externalDocs = @ExternalDocumentation(
            url = "https://example.com/definition-docs",
            description = "Definition docs"
        )
    )
    static class OpenApiDefinitionExternalDocsClass {
    }

    static class MethodLevelClass {
        @Tag(name = "method-tag", description = "Tag from operation")
        @Server(url = "https://method.example.com", description = "Method server")
        void operation() {
        }
    }

    @Tag(name = "pets", description = "Class tag")
    @Server(url = "https://api.example.com", description = "Class server")
    static class DuplicateAnnotationsClass {
        @Tag(name = "pets", description = "Method tag duplicate")
        @Server(url = "https://api.example.com", description = "Method server duplicate")
        void operation() {
        }
    }

    @OpenAPIDefinition(
        info = @Info(title = "Secured API", version = "1.0.0"),
        tags = @Tag(name = "admin", description = "Administration operations"),
        servers = @Server(url = "https://definition.example.com", description = "Definition server"),
        security = @SecurityRequirement(name = "oauth2", scopes = {"read", "write"})
    )
    static class DefinitionWithTagsServersSecurity {
    }
}
