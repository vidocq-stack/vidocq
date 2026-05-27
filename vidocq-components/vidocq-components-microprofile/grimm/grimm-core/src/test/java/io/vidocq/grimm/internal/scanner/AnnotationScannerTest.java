package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
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
        // Spec §3.5: class/method @Server are contextual; they are not aggregated globally.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ServerClass.class));

        assertNotNull(model);
        assertTrue(model.getServers() == null || model.getServers().isEmpty());
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
        // Spec §3.5: method-level tags are declared globally; servers remain contextual.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(MethodLevelClass.class));

        assertNotNull(model.getTags());
        assertTrue(model.getTags().stream().anyMatch(tag -> "method-tag".equals(tag.getName())));
        assertTrue(model.getServers() == null || model.getServers().isEmpty());
    }

    @Test
    void scanClasses_deduplicatesTagsAndServers() {
        // Spec §3.5: same semantic tag entry should not be duplicated.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DuplicateAnnotationsClass.class));

        assertNotNull(model.getTags());
        long petsTagCount = model.getTags().stream().filter(tag -> "pets".equals(tag.getName())).count();
        assertEquals(1, petsTagCount);

        assertTrue(model.getServers() == null || model.getServers().isEmpty());
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

    @Test
    void scanClasses_mapsInfoLicenseAndContactFromOpenApiDefinition() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DefinitionWithLicenseAndContact.class));

        assertNotNull(model.getInfo());
        assertNotNull(model.getInfo().getLicense());
        assertEquals("Apache 2.0", model.getInfo().getLicense().getName());
        assertEquals("Apache-2.0", model.getInfo().getLicense().getIdentifier());
        assertNotNull(model.getInfo().getContact());
        assertEquals("support@example.com", model.getInfo().getContact().getEmail());
    }

    @Test
    void scanClasses_mapsOpenApiDefinitionComponentsHeadersAndServerVariables() {
        // Spec §3.4: @OpenAPIDefinition.components and server variables should be mapped.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DefinitionWithComponentsAndServerVariables.class));

        assertNotNull(model.getServers());
        var server = model.getServers().stream()
                .filter(s -> "https://{username}.gigantic-server.com:{port}/{basePath}".equals(s.getUrl()))
                .findFirst()
                .orElseThrow();
        assertNotNull(server.getVariables());
        assertEquals(4, server.getVariables().size());
        assertEquals("v2", server.getVariables().get("basePath").getDefaultValue());

        assertNotNull(model.getComponents());
        assertNotNull(model.getComponents().getHeaders());
        assertEquals("Maximum rate", model.getComponents().getHeaders().get("Max-Rate").getDescription());
    }

    @Test
    void scanClasses_mapsOpenApiDefinitionComponentSchemaWithoutImplementation() {
        // Spec §3.4: @OpenAPIDefinition.components.schemas with only name/type/format must
        // populate the component schema keywords.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DefinitionWithNamedScalarSchema.class));

        assertNotNull(model.getComponents());
        assertNotNull(model.getComponents().getSchemas());
        var id = model.getComponents().getSchemas().get("id");
        assertNotNull(id);
        assertEquals("int32", id.getFormat());
        assertNotNull(id.getType());
        assertTrue(id.getType().contains(org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.INTEGER));
    }

    @Test
    void scanClasses_mapsOpenApiDefinitionExtensions() {
        // Spec §3.4: extensions from document-level annotations are serialized as x-* fields.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DefinitionWithExtensions.class));

        assertEquals("root", model.getExtensions().get("x-openapi-definition"));
        assertNotNull(model.getInfo());
        assertEquals("info", model.getInfo().getExtensions().get("x-info"));
        assertNotNull(model.getInfo().getContact());
        assertEquals("contact", model.getInfo().getContact().getExtensions().get("x-contact"));
        assertNotNull(model.getInfo().getLicense());
        assertEquals("license", model.getInfo().getLicense().getExtensions().get("x-license"));
        assertNotNull(model.getExternalDocs());
        assertEquals("docs", model.getExternalDocs().getExtensions().get("x-external-docs"));
        var server = model.getServers().get(0);
        assertEquals("server", server.getExtensions().get("x-server"));
        assertEquals("server-var", server.getVariables().get("env").getExtensions().get("x-server-variable"));
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

    @OpenAPIDefinition(
        info = @Info(
            title = "Info API",
            version = "1.0.0",
            license = @License(name = "Apache 2.0", identifier = "Apache-2.0"),
            contact = @org.eclipse.microprofile.openapi.annotations.info.Contact(email = "support@example.com")
        )
    )
    static class DefinitionWithLicenseAndContact {
    }

    @OpenAPIDefinition(
        info = @Info(title = "Filter API", version = "1.0.0"),
        servers = @Server(
            url = "https://{username}.gigantic-server.com:{port}/{basePath}",
            description = "The production API server",
            variables = {
                @ServerVariable(name = "username", defaultValue = "user1", enumeration = {"user1", "user2"}, description = "Reviews of the app by users"),
                @ServerVariable(name = "port", defaultValue = "8443", description = "Booking data"),
                @ServerVariable(name = "user", defaultValue = "user", description = "User data"),
                @ServerVariable(name = "basePath", defaultValue = "v2")
            }
        ),
        components = @Components(
            headers = @Header(
                name = "Max-Rate",
                description = "Maximum rate",
                required = true,
                deprecated = true,
                allowEmptyValue = true,
                schema = @Schema(type = org.eclipse.microprofile.openapi.annotations.enums.SchemaType.INTEGER)
            )
        )
    )
    static class DefinitionWithComponentsAndServerVariables {
    }

    @OpenAPIDefinition(
        info = @Info(title = "Schema API", version = "1.0.0"),
        components = @Components(
            schemas = @Schema(
                name = "id",
                type = org.eclipse.microprofile.openapi.annotations.enums.SchemaType.INTEGER,
                format = "int32"
            )
        )
    )
    static class DefinitionWithNamedScalarSchema {
    }

    @OpenAPIDefinition(
        info = @Info(
            title = "Extensions API",
            version = "1.0.0",
            contact = @org.eclipse.microprofile.openapi.annotations.info.Contact(
                name = "support",
                extensions = @Extension(name = "x-contact", value = "contact")
            ),
            license = @License(
                name = "Apache-2.0",
                extensions = @Extension(name = "x-license", value = "license")
            ),
            extensions = @Extension(name = "x-info", value = "info")
        ),
        externalDocs = @ExternalDocumentation(
            url = "https://example.com/docs",
            extensions = @Extension(name = "x-external-docs", value = "docs")
        ),
        servers = @Server(
            url = "https://{env}.example.com",
            extensions = @Extension(name = "x-server", value = "server"),
            variables = @ServerVariable(
                name = "env",
                defaultValue = "dev",
                extensions = @Extension(name = "x-server-variable", value = "server-var")
            )
        ),
        extensions = @Extension(name = "x-openapi-definition", value = "root")
    )
    static class DefinitionWithExtensions {
    }

    @Test
    void scanClasses_wiresGeneratedSchemasIntoComponents() {
        // Spec §3.10 + §3.11: POJO request body and return types are interned in components/schemas
        // and referenced by media-type schemas via $ref.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI openAPI = scanner.scanClasses(List.of(PetResource.class));

        assertNotNull(openAPI.getComponents());
        var schemas = openAPI.getComponents().getSchemas();
        assertTrue(schemas.containsKey("Pet"), "components/schemas should contain Pet");

        var get = openAPI.getPaths().getPathItem("/pets").getGET();
        var okMt = get.getResponses().getAPIResponse("200")
                .getContent().getMediaType("application/json");
        assertNotNull(okMt.getSchema());
        // Return type is List<Pet> → array of $ref Pet
        assertEquals("#/components/schemas/Pet", okMt.getSchema().getItems().getRef());

        var post = openAPI.getPaths().getPathItem("/pets").getPOST();
        var bodyMt = post.getRequestBody().getContent().getMediaType("application/json");
        assertEquals("#/components/schemas/Pet", bodyMt.getSchema().getRef());
    }

    @Path("/pets")
    @Produces("application/json")
    @Consumes("application/json")
    static class PetResource {
        @GET
        public List<Pet> list() { return List.of(); }
        @POST
        public Pet create(Pet pet) { return pet; }
    }

    static final class Pet {
        public String name;
        public int age;
    }
}
