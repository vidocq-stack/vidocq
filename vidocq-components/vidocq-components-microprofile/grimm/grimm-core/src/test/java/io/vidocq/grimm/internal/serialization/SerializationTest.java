package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializationTest {

    @Test
    void jsonSerializer_serializesCoreDocumentShape() {
        // Spec §2.2 / §3.1: OpenAPI document serialized in JSON format.
        OpenAPI openAPI = createSampleModel();

        String json = new JsonSerializer().serialize(openAPI);

        assertTrue(json.contains("\"openapi\":\"3.1.0\""));
        assertTrue(json.contains("\"title\":\"Pet Store\""));
        assertTrue(json.contains("\"paths\":{\"/pets\":"));
        assertTrue(json.contains("\"get\":{"));
        assertTrue(json.contains("\"responses\":{\"200\":"));
        assertTrue(json.contains("\"description\":\"A list of pets\""));
        assertTrue(json.contains("\"externalDocs\":{"));
        assertTrue(json.contains("\"url\":\"https://example.com/docs\""));
        assertTrue(json.contains("\"servers\":[{"));
        assertTrue(json.contains("\"url\":\"https://api.example.com\""));
        assertTrue(json.contains("\"tags\":[{"));
        assertTrue(json.contains("\"name\":\"pets\""));
    }

    @Test
    void yamlSerializer_serializesCoreDocumentShape() {
        // Spec §2.2: YAML is a supported output format.
        OpenAPI openAPI = createSampleModel();

        String yaml = new YamlSerializer().serialize(openAPI);

        assertTrue(yaml.contains("openapi: \"3.1.0\""));
        assertTrue(yaml.contains("title: \"Pet Store\""));
        assertTrue(yaml.contains("paths:"));
        assertTrue(yaml.contains("/pets:"));
        assertTrue(yaml.contains("get:"));
        assertTrue(yaml.contains("200:"));
        assertTrue(yaml.contains("description: \"A list of pets\""));
        assertTrue(yaml.contains("externalDocs:"));
        assertTrue(yaml.contains("url: \"https://example.com/docs\""));
        assertTrue(yaml.contains("servers:"));
        assertTrue(yaml.contains("-"));
        assertTrue(yaml.contains("tags:"));
    }

    @Test
    void serializers_flattenExtensionsToTopLevel() {
        // Spec §4.2: vendor extensions are serialized as x-* fields.
        OpenAPI openAPI = createSampleModel();
        openAPI.getInfo().addExtension("x-internal-id", 42);

        String json = new JsonSerializer().serialize(openAPI);
        String yaml = new YamlSerializer().serialize(openAPI);

        assertTrue(json.contains("\"x-internal-id\":42"));
        assertFalse(json.contains("\"extensions\":"));
        assertTrue(yaml.contains("x-internal-id: 42"));
    }

    @Test
    void jsonRoundTrip_restoresOpenApiCoreFields() {
        OpenAPI source = createSampleModel();
        source.getInfo().addExtension("x-internal-id", 42);

        String json = new JsonSerializer().serialize(source);
        OpenAPI restored = new JsonDeserializer().deserialize(json);

        assertTrue(restored != null);
        assertTrue(restored.getInfo() != null);
        assertTrue(restored.getPaths() != null);
        assertTrue(restored.getPaths().getPathItem("/pets") != null);
        assertTrue(restored.getPaths().getPathItem("/pets").getGET() != null);
        assertTrue(restored.getPaths().getPathItem("/pets").getGET().getResponses() != null);
        assertTrue(restored.getPaths().getPathItem("/pets").getGET().getResponses().getAPIResponse("200") != null);
        assertTrue("3.1.0".equals(restored.getOpenapi()));
        assertTrue("Pet Store".equals(restored.getInfo().getTitle()));
        assertTrue("listPets".equals(restored.getPaths().getPathItem("/pets").getGET().getOperationId()));
        assertTrue("A list of pets".equals(restored.getPaths().getPathItem("/pets").getGET().getResponses().getAPIResponse("200").getDescription()));
        assertTrue(restored.getInfo().getExtension("x-internal-id") instanceof Number);
        assertTrue(((Number) restored.getInfo().getExtension("x-internal-id")).intValue() == 42);
        assertTrue(restored.getExternalDocs() != null);
        assertTrue("https://example.com/docs".equals(restored.getExternalDocs().getUrl()));
        assertTrue(restored.getTags() != null && restored.getTags().size() == 1);
        assertTrue("pets".equals(restored.getTags().getFirst().getName()));
        assertTrue(restored.getServers() != null && restored.getServers().size() == 1);
        assertTrue("https://api.example.com".equals(restored.getServers().getFirst().getUrl()));
        assertTrue(restored.getSecurity() != null && restored.getSecurity().size() == 1);
        assertTrue(restored.getSecurity().getFirst().getScheme("oauth2") != null);
    }

    @Test
    void yamlRoundTrip_restoresOpenApiCoreFields() {
        OpenAPI source = createSampleModel();
        source.getInfo().addExtension("x-internal-id", 42);

        String yaml = new YamlSerializer().serialize(source);
        OpenAPI restored = new YamlDeserializer().deserialize(yaml);

        assertTrue(restored != null);
        assertTrue(restored.getInfo() != null);
        assertTrue(restored.getPaths() != null);
        assertTrue(restored.getPaths().getPathItem("/pets") != null);
        assertTrue(restored.getPaths().getPathItem("/pets").getGET() != null);
        assertTrue(restored.getPaths().getPathItem("/pets").getGET().getResponses() != null);
        assertTrue(restored.getPaths().getPathItem("/pets").getGET().getResponses().getAPIResponse("200") != null);
        assertTrue("3.1.0".equals(restored.getOpenapi()));
        assertTrue("Pet Store".equals(restored.getInfo().getTitle()));
        assertTrue("listPets".equals(restored.getPaths().getPathItem("/pets").getGET().getOperationId()));
        assertTrue("A list of pets".equals(restored.getPaths().getPathItem("/pets").getGET().getResponses().getAPIResponse("200").getDescription()));
        assertTrue(restored.getInfo().getExtension("x-internal-id") instanceof Number);
        assertTrue(((Number) restored.getInfo().getExtension("x-internal-id")).intValue() == 42);
        assertTrue(restored.getExternalDocs() != null);
        assertTrue("https://example.com/docs".equals(restored.getExternalDocs().getUrl()));
        assertTrue(restored.getTags() != null && restored.getTags().size() == 1);
        assertTrue("pets".equals(restored.getTags().getFirst().getName()));
        assertTrue(restored.getServers() != null && restored.getServers().size() == 1);
        assertTrue("https://api.example.com".equals(restored.getServers().getFirst().getUrl()));
        assertTrue(restored.getSecurity() != null && restored.getSecurity().size() == 1);
        assertTrue(restored.getSecurity().getFirst().getScheme("oauth2") != null);
    }

    private OpenAPI createSampleModel() {
        APIResponse response = OASFactory.createObject(APIResponse.class)
                .description("A list of pets");

        APIResponses responses = OASFactory.createObject(APIResponses.class)
                .addAPIResponse("200", response);

        Operation operation = OASFactory.createObject(Operation.class)
                .operationId("listPets")
                .responses(responses);

        PathItem pathItem = OASFactory.createObject(PathItem.class)
                .GET(operation);

        Paths paths = OASFactory.createObject(Paths.class)
                .addPathItem("/pets", pathItem);

        Info info = OASFactory.createObject(Info.class)
                .title("Pet Store")
                .version("1.0.0");

        Tag petsTag = OASFactory.createObject(Tag.class)
                .name("pets")
                .description("Pet operations");

        Server primaryServer = OASFactory.createObject(Server.class)
                .url("https://api.example.com")
                .description("Primary server");

        org.eclipse.microprofile.openapi.models.ExternalDocumentation externalDocs =
                OASFactory.createObject(org.eclipse.microprofile.openapi.models.ExternalDocumentation.class)
                        .url("https://example.com/docs")
                        .description("Reference docs");

        SecurityRequirement securityRequirement = OASFactory.createObject(SecurityRequirement.class)
                .addScheme("oauth2", java.util.List.of("read", "write"));

        return OASFactory.createObject(OpenAPI.class)
                .openapi("3.1.0")
                .info(info)
                .externalDocs(externalDocs)
                .addTag(petsTag)
                .addServer(primaryServer)
                .addSecurityRequirement(securityRequirement)
                .paths(paths);
    }
}



