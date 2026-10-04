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
package io.vidocq.cervantes.internal;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link JsonWebToken} supported by JSON payload validated from a JWT.
 *
 * <p>Only the three abstract methods of spec are implemented
 * ({@link #getName()}, {@link #getClaimNames()}, {@link #getClaim(String)}); the accessors
 * typed ({@code getIssuer()}, {@code getGroups()}, {@code getExpirationTime()},...) are the
 * default methods of the interface, which delegate to {@code getClaim} — hence the contract of
 * conversion respected here: {@code Long} for temporal claims, {@code Set<String>} for
 * {@code aud}/{@code groups}, {@code String} for chain claims.</p>
 */
public final class DefaultJsonWebToken implements JsonWebToken {

    private final JsonObject payload;
    private final String rawToken;

    public DefaultJsonWebToken(JsonObject payload, String rawToken) {
        this.payload = payload;
        this.rawToken = rawToken;
    }

    /** Sentinel payload for the anonymous token — distinguishable from a real empty payload. */
    private static final JsonObject ANONYMOUS_PAYLOAD = JsonValue.EMPTY_JSON_OBJECT;

    /**
     * Anonymous principal (no claim, {@code getName() == null}, {@code getClaimNames() == null},
     * {@code getRawToken() == null}) — used by CDI integration when no JWT is
     * present on the current request.
     *
     * <p>MP JWT spec § "EmptyToken": the endpoint can receive an anonymous principal; methods
     * All claims must return {@code null} (not an empty collection).</p>
     */
    public static DefaultJsonWebToken anonymous() {
        return new DefaultJsonWebToken(ANONYMOUS_PAYLOAD, null);
    }

    private boolean isAnonymous() {
        return payload == ANONYMOUS_PAYLOAD && rawToken == null;
    }

    @Override
    public String getName() {
        if (isAnonymous()) return null;
        String upn = getClaim("upn");
        if (upn != null) return upn;
        String preferred = getClaim("preferred_username");
        if (preferred != null) return preferred;
        return getClaim(Claims.sub.name());
    }

    @Override
    public Set<String> getClaimNames() {
        if (isAnonymous()) return null;
        return Set.copyOf(payload.keySet());
    }

    /**
     * Gross (unconverted) JSON value of the claim — {@code @Claim} type injection support
     * {@code jakarta.json} ({@code JsonValue}, {@code JsonString}, {@code JsonNumber},
     * {@code JsonObject}, {@code JsonArray}) and rebuilding a {@code Set<String>} from
     * any array claim (beyond {@code groups}/{@code aud}).
     *
     * @return the payload {@link JsonValue}, or {@link JsonValue#NULL} if the claim is absent.
     */
    public JsonValue rawClaim(String claimName) {
        JsonValue v = payload.get(claimName);
        return v == null ? JsonValue.NULL : v;
    }

    @Override
    public String getRawToken() {
        return rawToken; // null for anonymous
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
        if (isAnonymous()) {
            return null;
        }
        if (Claims.raw_token.name().equals(claimName)) {
            return (T) rawToken;
        }
        JsonValue v = payload.get(claimName);
        if (v == null || v.getValueType() == JsonValue.ValueType.NULL) {
            return null;
        }
        Object result = switch (claimName) {
            case "exp", "iat", "nbf", "auth_time", "updated_at" -> asLong(v);
            case "groups", "aud" -> asStringSet(v);
            default -> asJava(v);
        };
        return (T) result;
    }

    private static Long asLong(JsonValue v) {
        return (v instanceof JsonNumber n) ? n.longValue() : null;
    }

    private static Set<String> asStringSet(JsonValue v) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof JsonString s) {
            out.add(s.getString());
        } else if (v.getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue item : v.asJsonArray()) {
                if (item instanceof JsonString s) out.add(s.getString());
            }
        }
        return out;
    }

    private static Object asJava(JsonValue v) {
        return switch (v.getValueType()) {
            case STRING -> ((JsonString) v).getString();
            case NUMBER -> {
                JsonNumber n = (JsonNumber) v;
                yield n.isIntegral() ? (Object) n.longValue() : (Object) n.doubleValue();
            }
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            //objects and tables: exposed as is (JsonObject/JsonArray), in accordance with JWT MP usage
            default -> v;
        };
    }
}
