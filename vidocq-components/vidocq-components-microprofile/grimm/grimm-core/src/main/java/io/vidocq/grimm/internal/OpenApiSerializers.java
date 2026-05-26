package io.vidocq.grimm.internal;

import io.vidocq.grimm.internal.serialization.JsonSerializer;
import io.vidocq.grimm.internal.serialization.YamlSerializer;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Objects;

/**
 * Facade exposing OpenAPI serialization to modules that only depend on grimm-core.
 */
public final class OpenApiSerializers {

    private OpenApiSerializers() {
    }

    public static String toJson(OpenAPI openAPI) {
        return new JsonSerializer().serialize(Objects.requireNonNull(openAPI, "openAPI must not be null"));
    }

    public static String toYaml(OpenAPI openAPI) {
        return new YamlSerializer().serialize(Objects.requireNonNull(openAPI, "openAPI must not be null"));
    }
}

