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

import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;

/**
 * Annotation-to-model mappings shared by {@link AnnotationScanner} and {@link JaxRsResourceScanner}, so
 * that both scanners produce the same model and cannot drift apart.
 */
final class AnnotationModelMappings {

    private AnnotationModelMappings() {
    }

    /** Maps {@code @ExternalDocumentation} to the model; the caller guards against an empty URL. */
    static org.eclipse.microprofile.openapi.models.ExternalDocumentation toModelExternalDocs(
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

    static void applyExtensions(org.eclipse.microprofile.openapi.models.Extensible<?> extensible,
                                Extension[] extensions) {
        for (Extension extension : extensions) {
            if (extension.name().isEmpty()) {
                continue;
            }
            extensible.addExtension(extension.name(), parseExtensionValue(extension));
        }
    }

    static Object parseExtensionValue(Extension extension) {
        String rawValue = extension.value();
        if (!extension.parseValue()) {
            return rawValue;
        }
        if (rawValue.startsWith("{") && rawValue.endsWith("}")) {
            return parseInlineObject(rawValue);
        }
        if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
            return parseInlineValue(rawValue);
        }
        if ("true".equalsIgnoreCase(rawValue) || "false".equalsIgnoreCase(rawValue)) {
            return Boolean.parseBoolean(rawValue);
        }
        try {
            if (rawValue.contains(".")) {
                return Double.parseDouble(rawValue);
            }
            return Long.parseLong(rawValue);
        } catch (NumberFormatException ignored) {
            // Leave as plain string when parseValue=true but no primitive conversion applies.
            return rawValue;
        }
    }

    private static Object parseInlineObject(String rawValue) {
        String body = rawValue.substring(1, rawValue.length() - 1).trim();
        if (body.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (String entry : splitTopLevel(body, ',')) {
            String[] kv = splitKeyValue(entry);
            if (kv.length != 2) {
                continue;
            }
            String key = stripQuotes(kv[0].trim());
            result.put(key, parseInlineValue(kv[1].trim()));
        }
        return result;
    }

    private static Object parseInlineValue(String value) {
        if (value.startsWith("{") && value.endsWith("}")) {
            return parseInlineObject(value);
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            String body = value.substring(1, value.length() - 1).trim();
            if (body.isEmpty()) {
                return List.of();
            }
            List<Object> items = new ArrayList<>();
            for (String item : splitTopLevel(body, ',')) {
                items.add(parseInlineValue(item.trim()));
            }
            return items;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        String numericCandidate = value;
        if (numericCandidate.endsWith("f") || numericCandidate.endsWith("F")
                || numericCandidate.endsWith("d") || numericCandidate.endsWith("D")
                || numericCandidate.endsWith("l") || numericCandidate.endsWith("L")) {
            numericCandidate = numericCandidate.substring(0, numericCandidate.length() - 1);
        }
        try {
            if (numericCandidate.contains(".")) {
                double parsed = Double.parseDouble(numericCandidate);
                if (parsed == Math.rint(parsed)) {
                    return (long) parsed;
                }
                return parsed;
            }
            return Long.parseLong(numericCandidate);
        } catch (NumberFormatException ignored) {
            return stripQuotes(value);
        }
    }

    private static String[] splitKeyValue(String entry) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < entry.length(); i++) {
            char ch = entry.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            } else if (ch == ':' && depth == 0) {
                return new String[] {entry.substring(0, i), entry.substring(i + 1)};
            }
        }
        return new String[0];
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            } else if (ch == separator && depth == 0) {
                out.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(value.substring(start).trim());
        return out;
    }

    private static String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
