package fr.vidocq.vidocq.ext.rest.cassini.internal;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Coercion d'une chaîne (ou d'une liste de chaînes) vers le type Java cible,
 * selon JAX-RS 4.0 §3.2.
 *
 * <p>Ordre de résolution pour une valeur simple :</p>
 * <ol>
 *   <li>{@link String} — passage direct</li>
 *   <li>primitive / wrapper — {@code Integer.parseInt}, etc.</li>
 *   <li>{@link Enum} — {@link Enum#valueOf}</li>
 *   <li>méthode statique publique {@code valueOf(String)}</li>
 *   <li>méthode statique publique {@code fromString(String)}</li>
 *   <li>constructeur public à un argument {@link String}</li>
 * </ol>
 *
 * <p>Les types collection {@link List}, {@link Set}, {@link SortedSet}
 * paramétrés sont dépliés sur leur type d'élément.</p>
 */
public final class ParamValueConverter {

    private ParamValueConverter() {}

    /** Convertit une liste de valeurs brutes vers le type cible (potentiellement collection). */
    public static Object coerce(Class<?> raw, Class<?> elementType, List<String> values) {
        if (isListLike(raw)) {
            List<Object> items = new ArrayList<>(values.size());
            for (String v : values) items.add(coerceSingle(elementType, v));
            if (Set.class == raw) return new LinkedHashSet<>(items);
            if (SortedSet.class == raw) return new TreeSet<>(items);
            return items;
        }
        if (values.isEmpty()) return defaultForType(raw);
        return coerceSingle(raw, values.get(0));
    }

    public static boolean isListLike(Class<?> raw) {
        return raw == List.class || raw == Set.class || raw == SortedSet.class
                || raw == Collection.class;
    }

    public static Object coerceSingle(Class<?> type, String raw) {
        if (raw == null) return defaultForType(type);
        if (type == String.class || type == CharSequence.class) return raw;

        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == byte.class    || type == Byte.class)    return Byte.parseByte(raw);
        if (type == short.class   || type == Short.class)   return Short.parseShort(raw);
        if (type == int.class     || type == Integer.class) return Integer.parseInt(raw);
        if (type == long.class    || type == Long.class)    return Long.parseLong(raw);
        if (type == float.class   || type == Float.class)   return Float.parseFloat(raw);
        if (type == double.class  || type == Double.class)  return Double.parseDouble(raw);
        if (type == char.class    || type == Character.class) {
            if (raw.isEmpty()) throw badRequest("Empty value for char");
            return raw.charAt(0);
        }

        if (type.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum<?> e = Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), raw);
            return e;
        }

        try {
            Method valueOf = type.getDeclaredMethod("valueOf", String.class);
            if (Modifier.isStatic(valueOf.getModifiers())) return valueOf.invoke(null, raw);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw badRequest("valueOf(" + raw + ") failed for " + type.getName() + ": " + e.getMessage());
        }
        try {
            Method fromString = type.getDeclaredMethod("fromString", String.class);
            if (Modifier.isStatic(fromString.getModifiers())) return fromString.invoke(null, raw);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw badRequest("fromString(" + raw + ") failed for " + type.getName() + ": " + e.getMessage());
        }
        try {
            Constructor<?> c = type.getDeclaredConstructor(String.class);
            return c.newInstance(raw);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            throw badRequest("Constructor(String) failed for " + type.getName() + ": " + e.getMessage());
        }
        throw badRequest("No converter for type " + type.getName());
    }

    public static Object defaultForType(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class)    return '\0';
        return 0;
    }

    private static WebApplicationException badRequest(String msg) {
        return new WebApplicationException(msg, Response.Status.BAD_REQUEST);
    }
}
