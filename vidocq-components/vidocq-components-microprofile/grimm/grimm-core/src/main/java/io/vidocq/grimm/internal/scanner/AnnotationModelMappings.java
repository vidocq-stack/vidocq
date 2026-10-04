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
package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.serialization.JsonDeserializer;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;

/**
 * Annotation-to-model mappings shared by {@link AnnotationScanner}, {@link JaxRsResourceScanner} and
 * {@code SchemaGenerator}, so that they produce the same model and cannot drift apart. Public for
 * {@code SchemaGenerator} only: the package is not exported.
 */
public final class AnnotationModelMappings {

    private AnnotationModelMappings() {
    }

    /** Maps {@code @ExternalDocumentation} to the model; the caller guards against an empty URL. */
    public static org.eclipse.microprofile.openapi.models.ExternalDocumentation toModelExternalDocs(
            ExternalDocumentation annotation) {
        var externalDocs = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.ExternalDocumentation.class);
        externalDocs.setUrl(annotation.url());
        if (!annotation.description().isEmpty()) {
            externalDocs.setDescription(annotation.description());
        }
        applyExtensions(externalDocs, annotation.extensions());
        return externalDocs;
    }

    static org.eclipse.microprofile.openapi.models.examples.Example toModelExample(ExampleObject exampleAnnotation) {
        var example = OASFactory.createObject(org.eclipse.microprofile.openapi.models.examples.Example.class);
        if (!exampleAnnotation.summary().isEmpty()) {
            example.setSummary(exampleAnnotation.summary());
        }
        if (!exampleAnnotation.description().isEmpty()) {
            example.setDescription(exampleAnnotation.description());
        }
        if (!exampleAnnotation.value().isEmpty()) {
            example.setValue(exampleAnnotation.value());
        }
        if (!exampleAnnotation.externalValue().isEmpty()) {
            example.setExternalValue(exampleAnnotation.externalValue());
        }
        if (!exampleAnnotation.ref().isEmpty()) {
            example.setRef(exampleAnnotation.ref());
        }
        applyExtensions(example, exampleAnnotation.extensions());
        return example;
    }

    static void applyHeaderExamples(org.eclipse.microprofile.openapi.models.headers.Header header,
                                     Header headerAnnotation) {
        if (!headerAnnotation.example().isEmpty()) {
            header.setExample(headerAnnotation.example());
        }
        for (ExampleObject exampleAnnotation : headerAnnotation.examples()) {
            if (!exampleAnnotation.name().isEmpty()) {
                header.addExample(exampleAnnotation.name(), toModelExample(exampleAnnotation));
            }
        }
    }

    /**
     * Adds each named {@code @Extension}; with {@code parseValue = true} the value is read as JSON
     * ({@link #parseJsonValue}). A JSON {@code null} adds nothing: the model keeps no null extension.
     */
    public static void applyExtensions(org.eclipse.microprofile.openapi.models.Extensible<?> extensible,
                                       Extension[] extensions) {
        for (Extension extension : extensions) {
            if (extension.name().isEmpty()) {
                continue;
            }
            extensible.addExtension(extension.name(), parseExtensionValue(extension));
        }
    }

    static Object parseExtensionValue(Extension extension) {
        return extension.parseValue() ? parseJsonValue(extension.value()) : extension.value();
    }

    /**
     * Reads an annotation value written as JSON — an extension value with {@code parseValue = true},
     * a {@code constValue} — with grimm's JSON reader, the one of static files: an object, an array,
     * a string, a number ({@code Long}, or {@code Double} with a fraction or an exponent),
     * {@code true}/{@code false} or {@code null}, surrounding whitespace allowed. A value that is not
     * JSON stays the string as written.
     */
    public static Object parseJsonValue(String rawValue) {
        try {
            return JsonDeserializer.parseRaw(rawValue);
        } catch (IllegalArgumentException notJson) {
            return rawValue;
        }
    }
}
