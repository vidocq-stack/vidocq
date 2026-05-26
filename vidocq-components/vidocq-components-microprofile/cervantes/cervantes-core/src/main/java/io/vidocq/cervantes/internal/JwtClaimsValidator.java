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
 * Valide les claims temporels et d'appariement d'un JWT selon une {@link JwtConfig}.
 *
 * <ul>
 *   <li>{@code exp} : rejeté si {@code exp + skew < now} ; absence rejetée si {@code requireExpiration}.</li>
 *   <li>{@code nbf} : rejeté si {@code nbf - skew > now}.</li>
 *   <li>{@code iss} : si un émetteur est configuré, doit l'égaler exactement.</li>
 *   <li>{@code aud} : si des audiences sont configurées, l'intersection avec {@code aud} doit être non vide.</li>
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

    /** Le claim {@code aud} peut être une chaîne unique ou un tableau de chaînes (RFC 7519 §4.1.3). */
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
