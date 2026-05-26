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
 *   <li>{@code iat} : rejeté si {@code iat > exp} (token dont l'émission est postérieure à l'expiration).</li>
 *   <li>{@code nbf} : rejeté si {@code nbf - skew > now}.</li>
 *   <li>{@code iss} : si un émetteur est configuré, doit l'égaler exactement.</li>
 *   <li>{@code aud} : si des audiences sont configurées, l'intersection avec {@code aud} doit être non vide.</li>
 *   <li>âge du token : si {@code mp.jwt.verify.token.age} est configuré, {@code now - iat > tokenAge} est rejeté.</li>
 *   <li>identité : au moins un claim parmi {@code upn}, {@code preferred_username}, {@code sub} doit être présent.</li>
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

        // MP JWT spec §9.2.1: mp.jwt.verify.token.age — reject if now - iat > tokenAge (in seconds)
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

        // MP JWT spec §4.1: the principal name must be derivable from upn, preferred_username, or sub
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
