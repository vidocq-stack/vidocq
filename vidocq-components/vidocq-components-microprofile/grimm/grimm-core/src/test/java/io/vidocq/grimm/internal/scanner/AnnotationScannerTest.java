package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(model.getTags().stream()
            .anyMatch(t -> "pets".equals(t.getName())));
    }

    @Test
    void scanClasses_scansServerAnnotations() {
        // Spec §3.5: @Server annotations should be added to the model
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ServerClass.class));

        assertNotNull(model);
        assertTrue(model.getServers() != null && model.getServers().size() > 0);
        assertTrue(model.getServers().stream()
            .anyMatch(s -> "https://api.example.com".equals(s.getUrl())));
    }

    @Test
    void scanClasses_scansMultipleClasses() {
        // Multiple classes should all be processed
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(
            DocumentLevelClass.class,
            TaggedClass.class
        ));

        assertNotNull(model);
        assertNotNull(model.getInfo());
        assertEquals("My API", model.getInfo().getTitle());
        assertTrue(model.getTags() != null && model.getTags().size() > 0);
    }

    // Test classes with annotations

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
}

