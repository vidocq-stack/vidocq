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
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof Map<?, ?> mapValue) {
            return toSerializableMap(mapValue);
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
            return toSerializable(securityRequirement.getSchemes());
        }
        if (value instanceof Callback callback) {
            return mergeWithExtensions(toSerializable(callback.getPathItems()), callback);
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
            if ("extensions".equals(propertyName)) {
                Map<?, ?> extensions = (Map<?, ?>) rawValue;
                for (Map.Entry<?, ?> extension : extensions.entrySet()) {
                    if (extension.getKey() != null && extension.getValue() != null) {
                        result.put(String.valueOf(extension.getKey()), toSerializable(extension.getValue()));
                    }
                }
                continue;
            }

            // $ref is represented as getRef() in the model API.
            if ("ref".equals(propertyName)) {
                propertyName = "$ref";
            }

            result.put(propertyName, toSerializable(rawValue));
        }
        return result;
    }

    private static Map<String, Object> toSerializableMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), toSerializable(entry.getValue()));
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
}

