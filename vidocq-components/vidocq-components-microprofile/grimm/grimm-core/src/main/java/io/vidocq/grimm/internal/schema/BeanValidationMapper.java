package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.models.media.Schema;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

/**
 * Maps Jakarta/Javax Bean Validation constraints to OpenAPI Schema facets.
 */
public final class BeanValidationMapper {

    private static final String JAKARTA = "jakarta.validation.constraints.";
    private static final String JAVAX = "javax.validation.constraints.";

    private BeanValidationMapper() {
    }

    public static void apply(Schema schema, Annotation[] annotations) {
        if (schema == null || annotations == null || annotations.length == 0) {
            return;
        }
        for (Annotation ann : annotations) {
            if (!appliesToDefaultGroup(ann)) {
                continue;
            }
            String name = ann.annotationType().getName();
            if (matches(name, "Size")) {
                applySize(schema, ann);
            } else if (matches(name, "DecimalMin")) {
                applyDecimalMin(schema, ann);
            } else if (matches(name, "DecimalMax")) {
                applyDecimalMax(schema, ann);
            } else if (matches(name, "Min")) {
                schema.setMinimum(BigDecimal.valueOf(longAttr(ann, "value", Long.MIN_VALUE)));
            } else if (matches(name, "Max")) {
                schema.setMaximum(BigDecimal.valueOf(longAttr(ann, "value", Long.MAX_VALUE)));
            } else if (matches(name, "Positive")) {
                schema.setExclusiveMinimum(BigDecimal.ZERO);
            } else if (matches(name, "PositiveOrZero")) {
                schema.setMinimum(BigDecimal.ZERO);
            } else if (matches(name, "Negative")) {
                schema.setExclusiveMaximum(BigDecimal.ZERO);
            } else if (matches(name, "NegativeOrZero")) {
                schema.setMaximum(BigDecimal.ZERO);
            } else if (matches(name, "Pattern")) {
                String regexp = stringAttr(ann, "regexp");
                if (!regexp.isEmpty()) {
                    schema.setPattern(regexp);
                }
            } else if (matches(name, "NotBlank")) {
                setMinLengthAtLeast(schema, 1);
                if (schema.getPattern() == null || schema.getPattern().isBlank()) {
                    schema.setPattern("\\S");
                }
            } else if (matches(name, "NotEmpty")) {
                applyNotEmpty(schema);
            }
        }
    }

    private static boolean appliesToDefaultGroup(Annotation ann) {
        Object groups = invoke(ann, "groups");
        if (!(groups instanceof Object[] values) || values.length == 0) {
            return true;
        }
        for (Object value : values) {
            if (!(value instanceof Class<?> group)) {
                continue;
            }
            String name = group.getName();
            if ("jakarta.validation.groups.Default".equals(name)
                    || "javax.validation.groups.Default".equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasNotNull(Annotation[] annotations) {
        if (annotations == null) {
            return false;
        }
        for (Annotation ann : annotations) {
            String name = ann.annotationType().getName();
            if (matches(name, "NotNull")) {
                return true;
            }
        }
        return false;
    }

    private static void applySize(Schema schema, Annotation ann) {
        int min = intAttr(ann, "min", 0);
        int max = intAttr(ann, "max", Integer.MAX_VALUE);

        if (isArray(schema)) {
            if (min > 0) {
                setMinItemsAtLeast(schema, min);
            }
            if (max != Integer.MAX_VALUE) {
                setMaxItemsAtMost(schema, max);
            }
            return;
        }
        if (isObject(schema)) {
            if (min > 0) {
                setMinPropertiesAtLeast(schema, min);
            }
            if (max != Integer.MAX_VALUE) {
                setMaxPropertiesAtMost(schema, max);
            }
            return;
        }
        if (min > 0) {
            setMinLengthAtLeast(schema, min);
        }
        if (max != Integer.MAX_VALUE) {
            setMaxLengthAtMost(schema, max);
        }
    }

    private static void applyDecimalMin(Schema schema, Annotation ann) {
        String raw = stringAttr(ann, "value");
        if (raw.isEmpty()) {
            return;
        }
        BigDecimal value = new BigDecimal(raw);
        boolean inclusive = booleanAttr(ann, "inclusive", true);
        if (inclusive) {
            schema.setMinimum(value);
        } else {
            schema.setExclusiveMinimum(value);
        }
    }

    private static void applyDecimalMax(Schema schema, Annotation ann) {
        String raw = stringAttr(ann, "value");
        if (raw.isEmpty()) {
            return;
        }
        BigDecimal value = new BigDecimal(raw);
        boolean inclusive = booleanAttr(ann, "inclusive", true);
        if (inclusive) {
            schema.setMaximum(value);
        } else {
            schema.setExclusiveMaximum(value);
        }
    }

    private static void applyNotEmpty(Schema schema) {
        if (isArray(schema)) {
            setMinItemsAtLeast(schema, 1);
        } else if (isObject(schema)) {
            setMinPropertiesAtLeast(schema, 1);
        } else {
            setMinLengthAtLeast(schema, 1);
        }
    }

    private static void setMinLengthAtLeast(Schema schema, int value) {
        Integer current = schema.getMinLength();
        if (current == null || current < value) {
            schema.setMinLength(value);
        }
    }

    private static void setMaxLengthAtMost(Schema schema, int value) {
        Integer current = schema.getMaxLength();
        if (current == null || current > value) {
            schema.setMaxLength(value);
        }
    }

    private static void setMinItemsAtLeast(Schema schema, int value) {
        Integer current = schema.getMinItems();
        if (current == null || current < value) {
            schema.setMinItems(value);
        }
    }

    private static void setMaxItemsAtMost(Schema schema, int value) {
        Integer current = schema.getMaxItems();
        if (current == null || current > value) {
            schema.setMaxItems(value);
        }
    }

    private static void setMinPropertiesAtLeast(Schema schema, int value) {
        Integer current = schema.getMinProperties();
        if (current == null || current < value) {
            schema.setMinProperties(value);
        }
    }

    private static void setMaxPropertiesAtMost(Schema schema, int value) {
        Integer current = schema.getMaxProperties();
        if (current == null || current > value) {
            schema.setMaxProperties(value);
        }
    }

    private static boolean isArray(Schema schema) {
        List<Schema.SchemaType> types = schema.getType();
        return types != null && types.contains(Schema.SchemaType.ARRAY);
    }

    private static boolean isObject(Schema schema) {
        List<Schema.SchemaType> types = schema.getType();
        return types != null && types.contains(Schema.SchemaType.OBJECT);
    }

    private static boolean matches(String fqcn, String simpleName) {
        return (JAKARTA + simpleName).equals(fqcn) || (JAVAX + simpleName).equals(fqcn);
    }

    private static int intAttr(Annotation ann, String attribute, int fallback) {
        Object value = invoke(ann, attribute);
        return value instanceof Integer i ? i : fallback;
    }

    private static long longAttr(Annotation ann, String attribute, long fallback) {
        Object value = invoke(ann, attribute);
        return value instanceof Long l ? l : fallback;
    }

    private static boolean booleanAttr(Annotation ann, String attribute, boolean fallback) {
        Object value = invoke(ann, attribute);
        return value instanceof Boolean b ? b : fallback;
    }

    private static String stringAttr(Annotation ann, String attribute) {
        Object value = invoke(ann, attribute);
        return value instanceof String s ? s : "";
    }

    private static Object invoke(Annotation ann, String methodName) {
        try {
            Method method = ann.annotationType().getMethod(methodName);
            return method.invoke(ann);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}


