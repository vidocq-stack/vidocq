/*
 * Copyright 2026 Vidocq contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link OpenApiValueMapper}: the model-to-tree step shared by the JSON and YAML serializers
 * (spec §2.2), including extension values that are arbitrary application objects (§5.5 Extensions).
 */
class OpenApiValueMapperBeanTest {

    private static final String REF = "#/components/callbacks/other";

    /** An application value stored as an extension: read through its public getters. */
    public static final class Bean {
        public String getURL() {
            return "https://bean";
        }

        public boolean isActive() {
            return true;
        }

        public String getNothing() {
            return null;
        }

        public String get() {
            return "no property name";
        }

        public boolean is() {
            return false;
        }

        public String getWith(String argument) {
            return argument;
        }

        public void getVoid() {
            // a void method is not a getter
        }

        public static String getStatic() {
            return "static";
        }

        public Map<String, Object> getExtensions() {
            Map<String, Object> extensions = new HashMap<>();
            extensions.put("x-bean", 1);
            extensions.put("x-null", null);
            extensions.put(null, "no key");
            return extensions;
        }
    }

    /** A getter that fails: the serializer reports it instead of writing a partial document. */
    public static final class FailingBean {
        public String getBroken() {
            throw new IllegalStateException("broken");
        }
    }

    @Test
    void applicationBeanIsReadThroughItsGetters() {
        Map<?, ?> tree = (Map<?, ?>) OpenApiValueMapper.toSerializable(new Bean());

        assertEquals(Map.of("url", "https://bean", "active", true, "x-bean", 1), tree);
    }

    @Test
    void failingGetterIsReported() {
        FailingBean bean = new FailingBean();
        assertThrows(IllegalStateException.class, () -> OpenApiValueMapper.toSerializable(bean));
    }

    @Test
    void textValuesAreStrings() {
        assertEquals("c", OpenApiValueMapper.toSerializable('c'));
        assertEquals("built", OpenApiValueMapper.toSerializable(new StringBuilder("built")));
        assertEquals("string", OpenApiValueMapper.toSerializable(Schema.SchemaType.STRING));
    }

    @Test
    void nullKeysValuesAndItemsAreSkipped() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("kept", 1);
        map.put("dropped", null);
        map.put(null, 2);
        map.put("schemaDialect", "https://dialect");

        assertEquals(Map.of("kept", 1, "$schema", "https://dialect"), OpenApiValueMapper.toSerializable(map));
        assertEquals(List.of("a"), OpenApiValueMapper.toSerializable(Arrays.asList("a", null)));
        assertNull(OpenApiValueMapper.toSerializable(null));
    }

    @Test
    void emptySecurityRequirementIsAnEmptyObject() {
        SecurityRequirement requirement = OASFactory.createObject(SecurityRequirement.class);

        assertEquals(Map.of(), OpenApiValueMapper.toSerializable(requirement));
    }

    @Test
    void callbackWritesPathItemsReferenceAndExtensions() {
        Callback callback = OASFactory.createObject(Callback.class)
                .addPathItem("{$request.body#/url}", OASFactory.createObject(PathItem.class).summary("s"));
        callback.setRef(REF);
        callback.addExtension("x-cb", "v");

        Map<?, ?> tree = (Map<?, ?>) OpenApiValueMapper.toSerializable(callback);

        assertEquals(REF, tree.get("$ref"));
        assertEquals("v", tree.get("x-cb"));
        assertEquals(Map.of("summary", "s"), tree.get("{$request.body#/url}"));
    }

    @Test
    void emptyCallbackIsAnEmptyObject() {
        assertEquals(Map.of(), OpenApiValueMapper.toSerializable(OASFactory.createObject(Callback.class)));
    }

    @Test
    void schemaKeywordsUseTheirJsonNames() {
        Schema schema = OASFactory.createObject(Schema.class)
                .addType(Schema.SchemaType.STRING)
                .defaultValue("d")
                .enumeration(List.of("d"))
                .comment("c")
                .constValue("d")
                .ifSchema(OASFactory.createObject(Schema.class).booleanSchema(true))
                .thenSchema(OASFactory.createObject(Schema.class).booleanSchema(false))
                .elseSchema(OASFactory.createObject(Schema.class).booleanSchema(true));

        Map<?, ?> tree = (Map<?, ?>) OpenApiValueMapper.toSerializable(schema);

        assertEquals("string", tree.get("type"));
        assertEquals("d", tree.get("default"));
        assertEquals(List.of("d"), tree.get("enum"));
        assertEquals("c", tree.get("$comment"));
        assertEquals("d", tree.get("const"));
        assertEquals(true, tree.get("if"));
        assertEquals(false, tree.get("then"));
        assertEquals(true, tree.get("else"));
    }
}
