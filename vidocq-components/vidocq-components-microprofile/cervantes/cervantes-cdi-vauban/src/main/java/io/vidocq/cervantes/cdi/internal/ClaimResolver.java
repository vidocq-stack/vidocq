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
package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.cdi.JsonWebTokenContext;
import io.vidocq.cervantes.internal.DefaultJsonWebToken;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Provider;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code @Claim} injection site resolution (MicroProfile JWT 2.1 §"Injection of JSON Web
 * Token claims") since the {@link JsonWebToken} of the current query.
 *
 * <p>Shared by the {@code SyntheticBeanCreator} of the Build Compatible Extension. The name of the claim
 * comes from {@link Claim#standard()} (if different from {@link Claims#UNKNOWN}) otherwise from
 * {@link Claim#value()}.</p>
 *
 * <p>Types supported at the injection site:</p>
 * <ul>
 *   <li><b>bruts</b> : {@code String}, {@code Long}/{@code long}, {@code Integer}/{@code int},
 *       {@code Boolean}/{@code boolean}, {@code Double}/{@code double}, {@code Set<String>} ;</li>
 *   <li><b>jakarta.json</b> : {@code JsonValue}, {@code JsonString}, {@code JsonNumber},
 *       {@code JsonObject}, {@code JsonArray} (valeur JSON brute du claim) ;</li>
 *   <li><b>conteneurs</b> : {@code Optional<T>} (eager), {@code ClaimValue<T>},
 * {@code Provider<T>}, {@code Supplier<T>} (lazy — reread current token at each access,
 * only correct way for beans wider than {@code @RequestScoped}).</li>
 * </ul>
 */
public final class ClaimResolver {

    private ClaimResolver() {
    }

    /**
     * Resolves the value of the {@code @Claim} injection site.
     *
     * @param injectionPoint le point d'injection courant fourni par CDI
     * @param context the validated {@link JsonWebToken} query context
     * @return the converted value according to the type reported at the injection site
     */
    public static Object resolve(InjectionPoint injectionPoint, JsonWebTokenContext context) {
        Claim claim = injectionPoint.getAnnotated().getAnnotation(Claim.class);
        if (claim == null) {
            throw new IllegalArgumentException("@Claim metadata is required on injection point");
        }
        String name = claimName(claim);
        Type type = injectionPoint.getType();

        //Lazy containers — read the current token at each access (spec: ClaimValue/Provider
        //are the way to inject a claim into a wider bean than @RequestScoped).
        if (isParameterized(type, ClaimValue.class)) {
            Type inner = typeArgument(type);
            return new ClaimValueImpl<>(name, () -> resolveValue(inner, name, currentToken(context)));
        }
        if (isParameterized(type, Provider.class)) {
            Type inner = typeArgument(type);
            return (Provider<Object>) () -> resolveValue(inner, name, currentToken(context));
        }
        if (isParameterized(type, Supplier.class)) {
            Type inner = typeArgument(type);
            return (Supplier<Object>) () -> resolveValue(inner, name, currentToken(context));
        }
        return resolveValue(type, name, currentToken(context));
    }

    /** Nom du claim : {@code standard()} prioritaire sur {@code value()} (spec §"@Claim"). */
    static String claimName(Claim claim) {
        Claims standard = claim.standard();
        if (standard != null && standard != Claims.UNKNOWN) {
            return standard.name();
        }
        return claim.value();
    }

    private static JsonWebToken currentToken(JsonWebTokenContext context) {
        return context.current().orElseGet(DefaultJsonWebToken::anonymous);
    }

    private static Object resolveValue(Type type, String name, JsonWebToken token) {
        if (isParameterized(type, Optional.class)) {
            return Optional.ofNullable(resolveValue(typeArgument(type), name, token));
        }
        return resolveScalar(rawClass(type), name, token);
    }

    private static Object resolveScalar(Class<?> raw, String name, JsonWebToken token) {
        if (JsonValue.class.isAssignableFrom(raw)) {
            return jsonValue(token, name, raw);
        }
        if (raw == String.class) {
            Object c = token.getClaim(name);
            return c == null ? null : (c instanceof String s ? s : c.toString());
        }
        if (raw == Long.class || raw == long.class) {
            return asLong(token.getClaim(name));
        }
        if (raw == Integer.class || raw == int.class) {
            Long l = asLong(token.getClaim(name));
            return l == null ? null : l.intValue();
        }
        if (raw == Boolean.class || raw == boolean.class) {
            return token.getClaim(name) instanceof Boolean b ? b : null;
        }
        if (raw == Double.class || raw == double.class) {
            return token.getClaim(name) instanceof Number n ? n.doubleValue() : null;
        }
        if (raw == Set.class) {
            return stringSet(token, name);
        }
        //Fold: the value that getClaim can produce (Set<String> for groups/aud, JsonValue for
        //an object/table, etc.) — covers standard claims not listed above.
        return token.getClaim(name);
    }

    private static Long asLong(Object c) {
        if (c instanceof Long l) {
            return l;
        }
        if (c instanceof Number n) {
            return n.longValue();
        }
        if (c instanceof JsonNumber jn) {
            return jn.longValue();
        }
        return null;
    }

    private static JsonValue jsonValue(JsonWebToken token, String name, Class<?> raw) {
        JsonValue v = rawJson(token, name);
        if (v == null || v.getValueType() == JsonValue.ValueType.NULL) {
            //Claim absent: JsonValue → JsonValue.NULL; typed subtypes → null.
            return raw == JsonValue.class ? JsonValue.NULL : null;
        }
        if (raw == JsonValue.class) {
            return v;
        }
        if (raw == JsonObject.class) {
            return v.getValueType() == JsonValue.ValueType.OBJECT ? v.asJsonObject() : null;
        }
        if (raw == JsonArray.class) {
            if (v.getValueType() == JsonValue.ValueType.ARRAY) return v.asJsonArray();
            //MP JWT spec: 'aud' can be a single string — wrap in an array for JsonArray injection
            return Json.createArrayBuilder().add(v).build();
        }
        if (raw == JsonString.class) {
            return v instanceof JsonString ? v : null;
        }
        if (raw == JsonNumber.class) {
            return v instanceof JsonNumber ? v : null;
        }
        return v;
    }

    private static JsonValue rawJson(JsonWebToken token, String name) {
        //raw token is not in the JWT payload — it is the raw string of the token itself.
        // getClaim(Claims.raw_token.name()) returns the raw String; wrap it as JsonString.
        if (Claims.raw_token.name().equals(name)) {
            String raw = token.getRawToken();
            return raw != null ? Json.createValue(raw) : JsonValue.NULL;
        }
        if (token instanceof DefaultJsonWebToken d) {
            return d.rawClaim(name);
        }
        Object c = token.getClaim(name);
        return c instanceof JsonValue jv ? jv : JsonValue.NULL;
    }

    private static Set<String> stringSet(JsonWebToken token, String name) {
        Object c = token.getClaim(name);
        if (c instanceof Set<?> set) {
            Set<String> out = new LinkedHashSet<>();
            for (Object o : set) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        JsonValue v = rawJson(token, name);
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof JsonString js) {
            out.add(js.getString());
        } else if (v.getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue item : v.asJsonArray()) {
                out.add(item instanceof JsonString js ? js.getString() : item.toString());
            }
        }
        return out;
    }

    private static boolean isParameterized(Type type, Class<?> raw) {
        return type instanceof ParameterizedType pt && pt.getRawType() == raw;
    }

    private static Type typeArgument(Type type) {
        return ((ParameterizedType) type).getActualTypeArguments()[0];
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        throw new IllegalArgumentException("Unsupported @Claim injection type: " + type.getTypeName());
    }
}
