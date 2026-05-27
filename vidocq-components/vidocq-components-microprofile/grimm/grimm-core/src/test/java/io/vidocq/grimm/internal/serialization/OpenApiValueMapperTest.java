package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiValueMapperTest {

    @Test
    void toSerializable_mapsAdditionalPropertiesBooleanToOpenApiKey() {
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class);
        var components = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Components.class);
        Schema schema = OASFactory.createObject(Schema.class);
        schema.addType(Schema.SchemaType.OBJECT);
        schema.setAdditionalPropertiesBoolean(Boolean.TRUE);
        components.addSchema("Payload", schema);
        openAPI.setComponents(components);

        Object value = OpenApiValueMapper.toSerializable(openAPI);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value;
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) root.get("components")).get("schemas")).get("Payload");

        assertNotNull(payload);
        assertEquals(Boolean.TRUE, payload.get("additionalProperties"));
    }
}

