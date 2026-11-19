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
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
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
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code @Claim} injection conversions (MicroProfile JWT 2.2 §5.2 "Injection of JsonWebToken claims"): raw
 * Java types, JSON-P types, {@code Optional}, and the lazy {@code ClaimValue}/{@code Provider}/{@code Supplier}
 * containers, against the token of the current request.
 */
class ClaimResolverTest {

    private static final String RAW = "raw.jwt.token";
    private static final String NAME = "alice";
    private static final String COUNT = "count";
    private static final String ROLES = "roles";

    /** Injection sites: each field is read for its generic type and its {@code @Claim}. */
    @SuppressWarnings("unused")
    static final class Sites {
        @Claim("upn") String upn;
        @Claim(standard = Claims.iat) Long iat;
        @Claim("iat") long iatPrimitive;
        @Claim("count") Integer count;
        @Claim("count") int countPrimitive;
        @Claim("active") Boolean active;
        @Claim("active") boolean activePrimitive;
        @Claim("ratio") Double ratio;
        @Claim("count") double countAsDouble;
        @Claim("groups") Set<String> groups;
        @Claim("aud") Set<String> audience;
        @Claim("roles") Set<String> roles;
        @Claim("upn") Set<String> upnAsSet;
        @Claim("address") Object address;
        @Claim("count") String countAsString;
        @Claim("upn") Long upnAsLong;
        @Claim("upn") Boolean upnAsBoolean;
        @Claim("upn") Double upnAsDouble;
        @Claim("upn") Optional<String> optionalUpn;
        @Claim("missing") Optional<String> optionalMissing;
        @Claim("upn") ClaimValue<String> claimValueUpn;
        @Claim("upn") Provider<String> providerUpn;
        @Claim("upn") Supplier<String> supplierUpn;
        @Claim("upn") JsonValue upnJson;
        @Claim("missing") JsonValue missingJson;
        @Claim("missing") JsonString missingJsonString;
        @Claim("upn") JsonString upnJsonString;
        @Claim("count") JsonString countAsJsonString;
        @Claim("count") JsonNumber countJson;
        @Claim("upn") JsonNumber upnAsJsonNumber;
        @Claim("address") JsonObject addressJson;
        @Claim("upn") JsonObject upnAsJsonObject;
        @Claim("roles") JsonArray rolesJson;
        @Claim("upn") JsonArray upnAsJsonArray;
        @Claim(standard = Claims.raw_token) JsonString rawTokenJson;
        @Claim("upn") List<String> upnAsList;
        @Claim("upn") Optional<String>[] unsupportedArray;
        String notAClaim;
    }

    private static final JsonObject ADDRESS = Json.createObjectBuilder().add("city", "Paris").build();

    private static JsonWebTokenContext context(JsonWebToken token) {
        JsonWebTokenContext context = new JsonWebTokenContext();
        context.setToken(token);
        return context;
    }

    private static JsonWebTokenContext defaultTokenContext() {
        JsonObject payload = Json.createObjectBuilder()
                .add("upn", NAME).add("iat", 1_700_000_000L).add(COUNT, 3).add("active", true).add("ratio", 0.5)
                .add("groups", Json.createArrayBuilder().add("admin"))
                .add("aud", "api")
                .add(ROLES, Json.createArrayBuilder().add("r1").add(7))
                .add("address", ADDRESS)
                .build();
        return context(new DefaultJsonWebToken(payload, RAW));
    }

    private static Object resolve(String site, JsonWebTokenContext context) {
        return ClaimResolver.resolve(injectionPoint(site), context);
    }

    private static Object resolve(String site) {
        return resolve(site, defaultTokenContext());
    }

    @Test
    void rawJavaTypes() {
        assertEquals(NAME, resolve("upn"));
        assertEquals(1_700_000_000L, resolve("iat"));
        assertEquals(1_700_000_000L, resolve("iatPrimitive"));
        assertEquals(3, resolve(COUNT));
        assertEquals(3, resolve("countPrimitive"));
        assertEquals(Boolean.TRUE, resolve("active"));
        assertEquals(Boolean.TRUE, resolve("activePrimitive"));
        assertEquals(0.5, resolve("ratio"));
        assertEquals(3.0, resolve("countAsDouble"));
        assertEquals("3", resolve("countAsString"));
        assertEquals(ADDRESS, resolve("address"));
    }

    @Test
    void mismatchedRawTypes_areNull() {
        assertNull(resolve("upnAsLong"));
        assertNull(resolve("upnAsBoolean"));
        assertNull(resolve("upnAsDouble"));
        assertNull(resolve(COUNT, context(DefaultJsonWebToken.anonymous())));
    }

    @Test
    void stringSets() {
        assertEquals(Set.of("admin"), resolve("groups"));
        assertEquals(Set.of("api"), resolve("audience"));
        assertEquals(Set.of("r1", "7"), resolve(ROLES));
        assertEquals(Set.of(NAME), resolve("upnAsSet"));
        assertEquals(Set.of(), resolve("upnAsSet", context(DefaultJsonWebToken.anonymous())));
    }

    @Test
    void optionals() {
        assertEquals(Optional.of(NAME), resolve("optionalUpn"));
        assertEquals(Optional.empty(), resolve("optionalMissing"));
    }

    @Test
    void lazyContainers_readTheCurrentTokenAtEachAccess() {
        JsonWebTokenContext context = defaultTokenContext();
        ClaimValue<?> claimValue = assertInstanceOf(ClaimValue.class, resolve("claimValueUpn", context));
        Provider<?> provider = assertInstanceOf(Provider.class, resolve("providerUpn", context));
        Supplier<?> supplier = assertInstanceOf(Supplier.class, resolve("supplierUpn", context));
        assertEquals(NAME, claimValue.getValue());
        assertEquals(NAME, provider.get());
        assertEquals(NAME, supplier.get());

        context.setToken(new DefaultJsonWebToken(Json.createObjectBuilder().add("upn", "bob").build(), RAW));
        assertEquals("bob", claimValue.getValue());
        assertEquals("bob", provider.get());
        assertEquals("bob", supplier.get());

        context.clear();
        assertNull(supplier.get());
    }

    @Test
    void jsonTypes() {
        assertEquals(Json.createValue(NAME), resolve("upnJson"));
        assertSame(JsonValue.NULL, resolve("missingJson"));
        assertNull(resolve("missingJsonString"));
        assertEquals(Json.createValue(NAME), resolve("upnJsonString"));
        assertNull(resolve("countAsJsonString"));
        assertEquals(3, ((JsonNumber) resolve("countJson")).intValue());
        assertNull(resolve("upnAsJsonNumber"));
        assertEquals(ADDRESS, resolve("addressJson"));
        assertNull(resolve("upnAsJsonObject"));
        assertEquals(2, ((JsonArray) resolve("rolesJson")).size());
        assertEquals(Json.createArrayBuilder().add(NAME).build(), resolve("upnAsJsonArray"));
        assertEquals(Json.createValue(RAW), resolve("rawTokenJson"));
        assertNull(resolve("rawTokenJson", context(DefaultJsonWebToken.anonymous())));
    }

    @Test
    void foreignJsonWebTokenImplementations_areReadThroughGetClaim() {
        JsonWebToken foreign = new MapToken(Map.of("upn", Json.createValue(NAME), ROLES, Set.of("x")));
        JsonWebTokenContext context = context(foreign);
        assertEquals(Json.createValue(NAME), resolve("upnJson", context));
        assertSame(JsonValue.NULL, resolve("missingJson", context));
        assertEquals(Set.of("x"), resolve(ROLES, context));
    }

    @Test
    void parameterizedTypesOtherThanContainers_useTheirRawClass() {
        assertEquals(NAME, resolve("upnAsList"));
    }

    @Test
    void unsupportedTypesAndMissingMetadata_areRejected() {
        InjectionPoint array = injectionPoint("unsupportedArray");
        InjectionPoint noClaim = injectionPoint("notAClaim");
        JsonWebTokenContext context = defaultTokenContext();
        assertThrows(IllegalArgumentException.class, () -> ClaimResolver.resolve(array, context));
        assertThrows(IllegalArgumentException.class, () -> ClaimResolver.resolve(noClaim, context));
    }

    @Test
    void standardTakesPrecedenceOverValue() throws Exception {
        Claim iat = Sites.class.getDeclaredField("iat").getAnnotation(Claim.class);
        Claim upn = Sites.class.getDeclaredField("upn").getAnnotation(Claim.class);
        assertEquals("iat", ClaimResolver.claimName(iat));
        assertEquals("upn", ClaimResolver.claimName(upn));
        assertEquals(Claims.UNKNOWN, upn.standard());
    }

    // --- doubles -----------------------------------------------------------------------------

    /** A {@link JsonWebToken} that is not Cervantes' own: claims straight from a map. */
    private record MapToken(Map<String, Object> claims) implements JsonWebToken {
        @Override
        public String getName() {
            return null;
        }

        @Override
        public Set<String> getClaimNames() {
            return claims.keySet();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getClaim(String claimName) {
            return (T) claims.get(claimName);
        }
    }

    private static InjectionPoint injectionPoint(String fieldName) {
        Field field;
        try {
            field = Sites.class.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
        return new FieldInjectionPoint(field);
    }

    private record FieldInjectionPoint(Field field) implements InjectionPoint {
        @Override
        public Type getType() {
            return field.getGenericType();
        }

        @Override
        public Set<Annotation> getQualifiers() {
            return Set.of();
        }

        @Override
        public Bean<?> getBean() {
            return null;
        }

        @Override
        public Member getMember() {
            return field;
        }

        @Override
        public Annotated getAnnotated() {
            return new Annotated() {
                @Override
                public Type getBaseType() {
                    return field.getGenericType();
                }

                @Override
                public Set<Type> getTypeClosure() {
                    return Set.of(field.getGenericType());
                }

                @Override
                public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
                    return field.getAnnotation(annotationType);
                }

                @Override
                public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
                    return Set.of(field.getAnnotationsByType(annotationType));
                }

                @Override
                public Set<Annotation> getAnnotations() {
                    return Set.of(field.getAnnotations());
                }

                @Override
                public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
                    return field.isAnnotationPresent(annotationType);
                }
            };
        }

        @Override
        public boolean isDelegate() {
            return false;
        }

        @Override
        public boolean isTransient() {
            return false;
        }
    }
}
