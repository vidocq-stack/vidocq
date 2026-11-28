/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
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

import java.util.List;

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
        List<String> examples,
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
        List<String> requiredProperties,
        List<String> enumeration,
        List<DependentRequired> dependentRequired,
        List<DependentSchema> dependentSchemas,
        Class<?> additionalProperties,
        ExternalDocumentation externalDocs,
        List<Extension> extensions,
        List<Class<?>> allOf,
        List<Class<?>> anyOf,
        List<Class<?>> oneOf,
        Class<?> not,
        String discriminatorProperty,
        List<DiscriminatorMapping> discriminatorMapping,
        Class<?> ifSchema,
        Class<?> thenSchema,
        Class<?> elseSchema,
        Class<?> contains,
        int minContains,
        int maxContains,
        List<Class<?>> prefixItems,
        List<PatternProperty> patternProperties,
        Class<?> propertyNames) {

    /**
     * Whether any attribute differs from its default in the MP OpenAPI 4.2 annotation. Defaults
     * that are not the type's zero value: {@code maxLength} and {@code maxContains}
     * {@code Integer.MAX_VALUE}, {@code minItems} {@code Integer.MAX_VALUE}, {@code maxItems}
     * {@code Integer.MIN_VALUE}; {@code additionalProperties} defaults to {@code Void}.
     */
    boolean hasContent() {
        return type != SchemaType.DEFAULT
                || !format.isEmpty() || !title.isEmpty() || !description.isEmpty() || !comment.isEmpty()
                || !pattern.isEmpty() || !example.isEmpty() || !examples.isEmpty() || !defaultValue.isEmpty()
                || minLength != 0 || maxLength != Integer.MAX_VALUE
                || minProperties != 0 || maxProperties != 0
                || minItems != Integer.MAX_VALUE || maxItems != Integer.MIN_VALUE
                || uniqueItems || !minimum.isEmpty() || !maximum.isEmpty()
                || exclusiveMinimum || exclusiveMaximum || multipleOf != 0d
                || deprecated || readOnly || writeOnly || nullable
                || !contentEncoding.isEmpty() || !contentMediaType.isEmpty() || contentSchema != Void.class
                || !constValue.isEmpty() || !requiredProperties.isEmpty() || !enumeration.isEmpty()
                || !dependentRequired.isEmpty() || !dependentSchemas.isEmpty()
                || additionalProperties != Void.class || !externalDocs.url().isEmpty()
                || !extensions.isEmpty() || !allOf.isEmpty() || !anyOf.isEmpty() || !oneOf.isEmpty()
                || not != Void.class || !discriminatorProperty.isEmpty() || !discriminatorMapping.isEmpty()
                || ifSchema != Void.class || thenSchema != Void.class || elseSchema != Void.class
                || contains != Void.class || minContains != 0 || maxContains != Integer.MAX_VALUE
                || !prefixItems.isEmpty() || !patternProperties.isEmpty() || propertyNames != Void.class;
    }

    @SuppressWarnings("deprecation") // example(): deprecated in MP OpenAPI 4, still mapped (spec)
    static SchemaAttributes of(Schema a) {
        return new SchemaAttributes(a.type(), a.format(), a.title(), a.description(), a.comment(), a.pattern(),
                a.example(), List.of(a.examples()), a.defaultValue(), a.minLength(), a.maxLength(), a.minProperties(),
                a.maxProperties(), a.minItems(), a.maxItems(), a.uniqueItems(), a.minimum(), a.maximum(),
                a.exclusiveMinimum(), a.exclusiveMaximum(), a.multipleOf(), a.deprecated(), a.readOnly(),
                a.writeOnly(), a.nullable(), a.contentEncoding(), a.contentMediaType(), a.contentSchema(),
                a.constValue(), List.of(a.requiredProperties()), List.of(a.enumeration()), List.of(a.dependentRequired()),
                List.of(a.dependentSchemas()), a.additionalProperties(), a.externalDocs(), List.of(a.extensions()), List.of(a.allOf()),
                List.of(a.anyOf()), List.of(a.oneOf()), a.not(), a.discriminatorProperty(), List.of(a.discriminatorMapping()),
                a.ifSchema(), a.thenSchema(), a.elseSchema(), a.contains(), a.minContains(), a.maxContains(),
                List.of(a.prefixItems()), List.of(a.patternProperties()), a.propertyNames());
    }

    @SuppressWarnings("deprecation") // example(): deprecated in MP OpenAPI 4, still mapped (spec)
    static SchemaAttributes of(SchemaProperty a) {
        return new SchemaAttributes(a.type(), a.format(), a.title(), a.description(), a.comment(), a.pattern(),
                a.example(), List.of(a.examples()), a.defaultValue(), a.minLength(), a.maxLength(), a.minProperties(),
                a.maxProperties(), a.minItems(), a.maxItems(), a.uniqueItems(), a.minimum(), a.maximum(),
                a.exclusiveMinimum(), a.exclusiveMaximum(), a.multipleOf(), a.deprecated(), a.readOnly(),
                a.writeOnly(), a.nullable(), a.contentEncoding(), a.contentMediaType(), a.contentSchema(),
                a.constValue(), List.of(a.requiredProperties()), List.of(a.enumeration()), List.of(a.dependentRequired()),
                List.of(a.dependentSchemas()), a.additionalProperties(), a.externalDocs(), List.of(a.extensions()), List.of(a.allOf()),
                List.of(a.anyOf()), List.of(a.oneOf()), a.not(), a.discriminatorProperty(), List.of(a.discriminatorMapping()),
                a.ifSchema(), a.thenSchema(), a.elseSchema(), a.contains(), a.minContains(), a.maxContains(),
                List.of(a.prefixItems()), List.of(a.patternProperties()), a.propertyNames());
    }
}
