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
package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.media.DependentRequired;
import org.eclipse.microprofile.openapi.annotations.media.DependentSchema;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.PatternProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;

/**
 * The attributes {@code @Schema} and {@code @SchemaProperty} share, read once from either
 * annotation so that {@link SchemaGenerator} maps them with a single piece of code. The two
 * annotation types have no common interface; this record is the adapter. Attributes handled
 * by the caller ({@code name}, {@code implementation}, {@code ref}, {@code hidden}, present on
 * both annotations, and {@code required}, {@code properties}, which only {@code @Schema} has)
 * are not in the record.
 */
record SchemaAttributes(
        SchemaType type,
        String format,
        String title,
        String description,
        String comment,
        String pattern,
        String example,
        String[] examples,
        String defaultValue,
        int minLength,
        int maxLength,
        int minProperties,
        int maxProperties,
        int minItems,
        int maxItems,
        boolean uniqueItems,
        String minimum,
        String maximum,
        boolean exclusiveMinimum,
        boolean exclusiveMaximum,
        double multipleOf,
        boolean deprecated,
        boolean readOnly,
        boolean writeOnly,
        boolean nullable,
        String contentEncoding,
        String contentMediaType,
        Class<?> contentSchema,
        String constValue,
        String[] requiredProperties,
        String[] enumeration,
        DependentRequired[] dependentRequired,
        DependentSchema[] dependentSchemas,
        Class<?> additionalProperties,
        ExternalDocumentation externalDocs,
        Extension[] extensions,
        Class<?>[] allOf,
        Class<?>[] anyOf,
        Class<?>[] oneOf,
        Class<?> not,
        String discriminatorProperty,
        DiscriminatorMapping[] discriminatorMapping,
        Class<?> ifSchema,
        Class<?> thenSchema,
        Class<?> elseSchema,
        Class<?> contains,
        int minContains,
        int maxContains,
        Class<?>[] prefixItems,
        PatternProperty[] patternProperties,
        Class<?> propertyNames) {

    static SchemaAttributes of(Schema a) {
        return new SchemaAttributes(a.type(), a.format(), a.title(), a.description(), a.comment(), a.pattern(),
                a.example(), a.examples(), a.defaultValue(), a.minLength(), a.maxLength(), a.minProperties(),
                a.maxProperties(), a.minItems(), a.maxItems(), a.uniqueItems(), a.minimum(), a.maximum(),
                a.exclusiveMinimum(), a.exclusiveMaximum(), a.multipleOf(), a.deprecated(), a.readOnly(),
                a.writeOnly(), a.nullable(), a.contentEncoding(), a.contentMediaType(), a.contentSchema(),
                a.constValue(), a.requiredProperties(), a.enumeration(), a.dependentRequired(),
                a.dependentSchemas(), a.additionalProperties(), a.externalDocs(), a.extensions(), a.allOf(),
                a.anyOf(), a.oneOf(), a.not(), a.discriminatorProperty(), a.discriminatorMapping(),
                a.ifSchema(), a.thenSchema(), a.elseSchema(), a.contains(), a.minContains(), a.maxContains(),
                a.prefixItems(), a.patternProperties(), a.propertyNames());
    }

    static SchemaAttributes of(SchemaProperty a) {
        return new SchemaAttributes(a.type(), a.format(), a.title(), a.description(), a.comment(), a.pattern(),
                a.example(), a.examples(), a.defaultValue(), a.minLength(), a.maxLength(), a.minProperties(),
                a.maxProperties(), a.minItems(), a.maxItems(), a.uniqueItems(), a.minimum(), a.maximum(),
                a.exclusiveMinimum(), a.exclusiveMaximum(), a.multipleOf(), a.deprecated(), a.readOnly(),
                a.writeOnly(), a.nullable(), a.contentEncoding(), a.contentMediaType(), a.contentSchema(),
                a.constValue(), a.requiredProperties(), a.enumeration(), a.dependentRequired(),
                a.dependentSchemas(), a.additionalProperties(), a.externalDocs(), a.extensions(), a.allOf(),
                a.anyOf(), a.oneOf(), a.not(), a.discriminatorProperty(), a.discriminatorMapping(),
                a.ifSchema(), a.thenSchema(), a.elseSchema(), a.contains(), a.minContains(), a.maxContains(),
                a.prefixItems(), a.patternProperties(), a.propertyNames());
    }
}
