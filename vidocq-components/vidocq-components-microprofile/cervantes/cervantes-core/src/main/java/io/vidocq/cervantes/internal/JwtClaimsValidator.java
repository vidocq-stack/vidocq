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

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates the temporal and matching claims of a JWT according to a {@link JwtConfig}.
 *
 * <ul>
 * <li>{@code exp}: rejected if {@code exp + skew < now}; absence rejected if ZZPH2ZZ.ZZPH4ZZ
 * <li>{@code iat}: rejected if {@code iat > exp} (token after expiry).</li>
 * <li>{@code nbf}: rejected if ZZPH1ZZ.ZZPH3ZZ
 * <li>{@code iss}: If a transmitter is configured, must match exactement.ZZPH2ZZ
 * <li>{@code aud}: If audiences are configured, the intersection with {@code aud} must be no vide.ZZPH3ZZ
 * <li>Token age: if {@code mp.jwt.verify.token.age} is configured, {@code now - iat > tokenAge} is rejected.</li>
 * <li> identity: at least one claim among {@code upn}, {@code preferred_username}, {@code sub} must be present.
 * </ul>
 */
final class JwtClaimsValidator {

    void validate(JsonObject claims, JwtConfig config, Clock clock) throws JwtValidationException {
        long now = clock.instant().getEpochSecond();
        long skew = config.clockSkew().getSeconds();

        JsonNumber exp = numberOrNull(claims, "exp");
        if (exp == null) {
            if (config.requireExpiration()) throw new JwtValidationException("missing required 'exp' claim");
        } else if (exp.longValue() + skew < now) {
            throw new JwtValidationException("token has expired");
        }

        JsonNumber iat = numberOrNull(claims, "iat");
        if (iat != null && exp != null && iat.longValue() > exp.longValue()) {
            throw new JwtValidationException("token 'iat' claim is older than 'exp' claim (iat > exp)");
        }

        //MP JWT spec §9.2.1: mp.jwt.verify.token.age — reject if now - iat > tokenAge (in seconds)
        if (iat != null && config.tokenAge().isPresent()) {
            long age = now - iat.longValue();
            if (age > config.tokenAge().get()) {
                throw new JwtValidationException("token age " + age + "s exceeds mp.jwt.verify.token.age=" + config.tokenAge().get() + "s");
            }
        }

        JsonNumber nbf = numberOrNull(claims, "nbf");
        if (nbf != null && nbf.longValue() - skew > now) {
            throw new JwtValidationException("token is not yet valid ('nbf' in the future)");
        }

        if (config.issuer().isPresent()) {
            String iss = stringOrNull(claims, "iss");
            if (!config.issuer().get().equals(iss)) {
                throw new JwtValidationException("issuer mismatch (expected '" + config.issuer().get() + "')");
            }
        }

        if (!config.audiences().isEmpty()) {
            Set<String> aud = audienceOf(claims);
            if (aud.stream().noneMatch(config.audiences()::contains)) {
                throw new JwtValidationException("audience mismatch (none of " + config.audiences() + ")");
            }
        }

        //MP JWT spec §4.1: the main name must be transferable from upn, preferred username, or sub
        String upn = stringOrNull(claims, "upn");
        String preferred = stringOrNull(claims, "preferred_username");
        String sub = stringOrNull(claims, "sub");
        if (upn == null && preferred == null && sub == null) {
            throw new JwtValidationException("token must have at least one of 'upn', 'preferred_username', or 'sub' claims");
        }
    }

    private static JsonNumber numberOrNull(JsonObject claims, String name) throws JwtValidationException {
        JsonValue v = claims.get(name);
        if (v == null || v.getValueType() == JsonValue.ValueType.NULL) return null;
        if (v instanceof JsonNumber n) return n;
        throw new JwtValidationException("claim '" + name + "' must be a number");
    }

    private static String stringOrNull(JsonObject claims, String name) {
        JsonValue v = claims.get(name);
        return (v instanceof JsonString s) ? s.getString() : null;
    }

    /** The {@code aud} claim may be a single string or an array of strings (RFC 7519 §4.1.3). */
    private static Set<String> audienceOf(JsonObject claims) {
        JsonValue v = claims.get("aud");
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof JsonString s) {
            out.add(s.getString());
        } else if (v != null && v.getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue item : v.asJsonArray()) {
                if (item instanceof JsonString s) out.add(s.getString());
            }
        }
        return out;
    }
}
