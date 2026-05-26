package io.vidocq.grimm.internal.reader;

import io.vidocq.grimm.internal.serialization.JsonDeserializer;
import io.vidocq.grimm.internal.serialization.YamlDeserializer;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Reads static OpenAPI documents from the deployment classpath.
 * <p>
 * Spec §4.2: OpenAPI documents can be located at:
 * {@code META-INF/openapi.yaml}, {@code META-INF/openapi.yml}, or {@code META-INF/openapi.json}
 * (in that priority order).
 */
public final class StaticFileReader {

    private static final String[] SEARCH_PATHS = {
            "META-INF/openapi.yaml",
            "META-INF/openapi.yml",
            "META-INF/openapi.json"
    };

    /**
     * Reads the static OpenAPI document from the deployment classpath.
     * <p>
     * Searches for files in priority order: {@code openapi.yaml} > {@code openapi.yml} > {@code openapi.json}.
     *
     * @return An {@code Optional} containing the deserialized {@link OpenAPI} document,
     *         or {@code Optional.empty()} if no file is found
     * @throws IllegalArgumentException if a file is found but cannot be parsed
     */
    public Optional<OpenAPI> readOpenAPI() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = StaticFileReader.class.getClassLoader();
        }

        for (String resourcePath : SEARCH_PATHS) {
            Optional<String> content = readResource(classLoader, resourcePath);
            if (content.isPresent()) {
                return Optional.of(deserialize(content.get(), resourcePath));
            }
        }

        return Optional.empty();
    }

    private Optional<String> readResource(ClassLoader classLoader, String resourcePath) {
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            return Optional.of(readInputStream(is));
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read resource: " + resourcePath, e);
        }
    }

    private String readInputStream(InputStream is) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
        }
        return content.toString();
    }

    private OpenAPI deserialize(String content, String resourcePath) {
        if (resourcePath.endsWith(".json")) {
            return new JsonDeserializer().deserialize(content);
        } else if (resourcePath.endsWith(".yaml") || resourcePath.endsWith(".yml")) {
            return new YamlDeserializer().deserialize(content);
        }
        throw new IllegalArgumentException("Unsupported file format: " + resourcePath);
    }
}


