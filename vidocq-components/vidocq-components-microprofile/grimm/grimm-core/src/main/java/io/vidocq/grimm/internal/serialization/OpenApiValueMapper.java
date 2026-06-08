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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.Extensible;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OpenApiValueMapper {

    private OpenApiValueMapper() {
    }

    static Object toSerializable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character characterValue) {
            return String.valueOf(characterValue);
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumLiteral(enumValue);
        }
        if (value instanceof Map<?, ?> mapValue) {
            Object mapped = toSerializableMap(mapValue);
            if (value instanceof Extensible<?> extensible) {
                return mergeWithExtensions(mapped, extensible);
            }
            return mapped;
        }
        if (value instanceof List<?> listValue) {
            return toSerializableList(listValue);
        }

        // OpenAPI map-like objects are serialized as plain maps in the final document.
        if (value instanceof Paths paths) {
            return mergeWithExtensions(toSerializable(paths.getPathItems()), paths);
        }
        if (value instanceof Content content) {
            return toSerializable(content.getMediaTypes());
        }
        if (value instanceof APIResponses apiResponses) {
            return mergeWithExtensions(toSerializable(apiResponses.getAPIResponses()), apiResponses);
        }
        if (value instanceof SecurityRequirement securityRequirement) {
            Map<String, List<String>> schemes = securityRequirement.getSchemes();
            if (schemes == null || schemes.isEmpty()) {
                return Map.of();
            }
            return toSerializable(schemes);
        }
        if (value instanceof org.eclipse.microprofile.openapi.models.media.Schema schema
                && schema.getBooleanSchema() != null) {
            return schema.getBooleanSchema();
        }
        if (value instanceof Callback callback) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            Object pathItems = toSerializable(callback.getPathItems());
            if (pathItems instanceof Map<?, ?> pathItemsMap) {
                for (Map.Entry<?, ?> entry : pathItemsMap.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            if (callback.getRef() != null) {
                result.put("$ref", callback.getRef());
            }
            Map<String, Object> extensions = callback.getExtensions();
            if (extensions != null) {
                for (Map.Entry<String, Object> entry : extensions.entrySet()) {
                    if (entry.getValue() != null) {
                        result.put(entry.getKey(), toSerializable(entry.getValue()));
                    }
                }
            }
            return result;
        }

        return toSerializableBean(value);
    }

    private static Object mergeWithExtensions(Object base, Extensible<?> extensible) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (base instanceof Map<?, ?> baseMap) {
            for (Map.Entry<?, ?> entry : baseMap.entrySet()) {
                if (entry.getKey() != null) {
                    merged.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        Map<String, Object> extensions = extensible.getExtensions();
        if (extensions != null) {
            for (Map.Entry<String, Object> entry : extensions.entrySet()) {
                if (entry.getValue() != null) {
                    merged.put(entry.getKey(), toSerializable(entry.getValue()));
                }
            }
        }
        return merged;
    }

    private static Map<String, Object> toSerializableBean(Object bean) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Method method : bean.getClass().getMethods()) {
            if (!isGetter(method)) {
                continue;
            }
            Object rawValue = invoke(method, bean);
            if (rawValue == null) {
                continue;
            }

            String propertyName = getterNameToProperty(method.getName());
            if (propertyName == null || "class".equals(propertyName)) {
                continue;
            }
            if ("operations".equals(propertyName)) {
                continue;
            }
            if ("all".equals(propertyName) && rawValue instanceof Map<?, ?> allMap) {
                for (Map.Entry<?, ?> entry : allMap.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    String key = String.valueOf(entry.getKey());
                    if ("schemaDialect".equals(key)) {
                        key = "$schema";
                    }
                    result.putIfAbsent(key, toSerializable(entry.getValue()));
                }
                continue;
            }
            if ("extensions".equals(propertyName)) {
                Map<?, ?> extensions = (Map<?, ?>) rawValue;
                for (Map.Entry<?, ?> extension : extensions.entrySet()) {
                    if (extension.getKey() != null && extension.getValue() != null) {
                        result.put(String.valueOf(extension.getKey()), toSerializable(extension.getValue()));
                    }
                }
                continue;
            }

            propertyName = toOpenApiPropertyName(propertyName);
            Object serializableValue = toSerializable(rawValue);
            if ("type".equals(propertyName) && serializableValue instanceof List<?> types && types.size() == 1) {
                serializableValue = types.getFirst();
            }
            result.put(propertyName, serializableValue);
        }
        return result;
    }

    private static Map<String, Object> toSerializableMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            if ("schemaDialect".equals(key)) {
                key = "$schema";
            }
            result.put(key, toSerializable(entry.getValue()));
        }
        return result;
    }

    private static List<Object> toSerializableList(List<?> source) {
        ArrayList<Object> result = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item != null) {
                result.add(toSerializable(item));
            }
        }
        return result;
    }

    private static boolean isGetter(Method method) {
        return Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0
                && method.getReturnType() != Void.TYPE
                && (method.getName().startsWith("get") || method.getName().startsWith("is"));
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to read property from OpenAPI model", e);
        }
    }

    private static String getterNameToProperty(String getterName) {
        if (getterName.startsWith("get") && getterName.length() > 3) {
            return normalizePropertyName(getterName.substring(3));
        }
        if (getterName.startsWith("is") && getterName.length() > 2) {
            return normalizePropertyName(getterName.substring(2));
        }
        return null;
    }

    private static String normalizePropertyName(String raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        if (raw.equals(raw.toUpperCase(Locale.ROOT))) {
            return raw.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String enumLiteral(Enum<?> enumValue) {
        // OpenAPI has a few enum literals that are not plain lower-case.
        if (enumValue instanceof org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type type) {
            return switch (type) {
                case APIKEY -> "apiKey";
                case OPENIDCONNECT -> "openIdConnect";
                case OAUTH2 -> "oauth2";
                case HTTP -> "http";
                case MUTUALTLS -> "mutualTLS";
            };
        }
        if (enumValue instanceof org.eclipse.microprofile.openapi.models.media.Encoding.Style style) {
            return switch (style) {
                case FORM -> "form";
                case SPACE_DELIMITED -> "spaceDelimited";
                case PIPE_DELIMITED -> "pipeDelimited";
                case DEEP_OBJECT -> "deepObject";
            };
        }
        if (enumValue instanceof org.eclipse.microprofile.openapi.models.parameters.Parameter.Style style) {
            return switch (style) {
                case MATRIX -> "matrix";
                case LABEL -> "label";
                case FORM -> "form";
                case SIMPLE -> "simple";
                case SPACEDELIMITED -> "spaceDelimited";
                case PIPEDELIMITED -> "pipeDelimited";
                case DEEPOBJECT -> "deepObject";
            };
        }
        return enumValue.name().toLowerCase(Locale.ROOT);
    }

    private static String toOpenApiPropertyName(String propertyName) {
        return switch (propertyName) {
            // $ref is represented as getRef() in the model API.
            case "ref" -> "$ref";
            // MP model exposes *DefaultValue accessors for JSON keys named "default".
            case "defaultValue" -> "default";
            case "enumeration" -> "enum";
            case "schemaDialect" -> "$schema";
            case "comment" -> "$comment";
            case "additionalPropertiesBoolean" -> "additionalProperties";
            case "additionalPropertiesSchema" -> "additionalProperties";
            case "constValue" -> "const";
            case "ifSchema" -> "if";
            case "thenSchema" -> "then";
            case "elseSchema" -> "else";
            default -> propertyName;
        };
    }
}

