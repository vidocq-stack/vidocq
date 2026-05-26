package io.vidocq.grimm.internal.reader;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StaticFileReader}.
 *
 * Spec §4.2: Static OpenAPI files can be located at META-INF/openapi.{yaml|yml|json}.
 */
class StaticFileReaderTest {

    @Test
    void readOpenAPI_parsesYamlFileFromClasspath() {
        // Spec §4.2: StaticFileReader should find and parse openapi.yaml from classpath
        StaticFileReader reader = new StaticFileReader();
        Optional<OpenAPI> result = reader.readOpenAPI();

        assertTrue(result.isPresent(), "Should find openapi.yaml on the test classpath");
        OpenAPI doc = result.get();
        assertEquals("3.1.0", doc.getOpenapi());
        assertNotNull(doc.getInfo());
        assertEquals("Test API", doc.getInfo().getTitle());
        assertEquals("1.0.0", doc.getInfo().getVersion());
        assertNotNull(doc.getPaths());
        assertNotNull(doc.getPaths().getPathItem("/test"));
    }

    @Test
    void readOpenAPI_parsesPathAndOperations() {
        // Spec §4.2 + §3.6: Verify that paths and operations are properly deserialized
        StaticFileReader reader = new StaticFileReader();
        Optional<OpenAPI> result = reader.readOpenAPI();

        assertTrue(result.isPresent());
        OpenAPI doc = result.get();
        assertNotNull(doc.getPaths().getPathItem("/test").getGET());
        assertEquals("getTest", doc.getPaths().getPathItem("/test").getGET().getOperationId());
    }

    @Test
    void readOpenAPI_prioritizesYamlOverJsonWhenBothExist() {
        // Spec §4.2: Priority order is openapi.yaml > openapi.yml > openapi.json.
        String yaml = "openapi: \"3.1.0\"\ninfo:\n  title: \"From YAML\"\n  version: \"1.0.0\"\n";
        String json = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"From JSON\",\"version\":\"1.0.0\"}}";

        Optional<OpenAPI> result = withContextClassLoader(
            new MapBackedClassLoader(Map.of(
                "META-INF/openapi.yaml", yaml,
                "META-INF/openapi.json", json
            )),
            () -> new StaticFileReader().readOpenAPI()
        );

        assertTrue(result.isPresent(), "StaticFileReader should find a static file");
        assertEquals("From YAML", result.get().getInfo().getTitle());
    }

    @Test
    void readOpenAPI_returnsEmptyWhenNoFileIsFound() {
        Optional<OpenAPI> result = withContextClassLoader(
            new MapBackedClassLoader(Map.of()),
            () -> new StaticFileReader().readOpenAPI()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void readOpenAPI_throwsOnMalformedStaticFile() {
        String malformedYaml = "openapi \"3.1.0\"\ninfo:\n  title: \"Broken\"\n";

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> withContextClassLoader(
                new MapBackedClassLoader(Map.of("META-INF/openapi.yaml", malformedYaml)),
                () -> new StaticFileReader().readOpenAPI()
            )
        );

        assertTrue(exception.getMessage() != null && !exception.getMessage().isBlank());
    }

    @Test
    void readOpenAPI_parsesYamlWithCommentsBlockScalarAndInlineListMap() {
        String yaml = """
                # header comment
                openapi: 3.1.0
                info:
                  title: Callback Example
                  version: 1.0.0
                paths:
                  /streams:
                    post:
                      description: subscribes a client
                      parameters:
                        - name: callbackUrl
                          in: query
                          required: true
                          description: |
                            line 1
                            line 2
                      responses:
                        '201':
                          description: created
                          content:
                            application/json:
                              schema:
                                description: subscription information
                      callbacks:
                        onData:
                          '{$request.query.callbackUrl}/data':
                            post:
                              description: callback post operation
                """;

        Optional<OpenAPI> result = withContextClassLoader(
                new MapBackedClassLoader(Map.of("META-INF/openapi.yaml", yaml)),
                () -> new StaticFileReader().readOpenAPI()
        );

        assertTrue(result.isPresent());
        OpenAPI doc = result.get();
        assertNotNull(doc.getPaths());
        assertNotNull(doc.getPaths().getPathItem("/streams"));
        assertNotNull(doc.getPaths().getPathItem("/streams").getPOST());
        var response201 = doc.getPaths().getPathItem("/streams").getPOST().getResponses().getAPIResponse("201");
        assertNotNull(response201);
        assertEquals("created", response201.getDescription());
        assertNotNull(response201.getContent());
        assertNotNull(response201.getContent().getMediaType("application/json"));
        assertEquals("subscription information", response201.getContent().getMediaType("application/json").getSchema().getDescription());

        var callbackPath = doc.getPaths().getPathItem("/streams").getPOST().getCallbacks().get("onData")
                .getPathItem("{$request.query.callbackUrl}/data");
        assertNotNull(callbackPath);
        assertNotNull(callbackPath.getPOST());
        assertEquals("callback post operation", callbackPath.getPOST().getDescription());
    }

    private static <T> T withContextClassLoader(ClassLoader classLoader, Supplier<T> supplier) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try {
            return supplier.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static final class MapBackedClassLoader extends ClassLoader {
        private final Map<String, String> resources;

        private MapBackedClassLoader(Map<String, String> resources) {
            this.resources = resources;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            String content = resources.get(name);
            if (content == null) {
                return null;
            }
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}


