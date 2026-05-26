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
 * {@link JsonWebToken} adossé au payload JSON validé d'un JWT.
 *
 * <p>Seules les trois méthodes abstraites de la spec sont implémentées
 * ({@link #getName()}, {@link #getClaimNames()}, {@link #getClaim(String)}) ; les accesseurs
 * typés ({@code getIssuer()}, {@code getGroups()}, {@code getExpirationTime()}, …) sont les
 * méthodes par défaut de l'interface, qui délèguent à {@code getClaim} — d'où le contrat de
 * conversion respecté ici : {@code Long} pour les claims temporels, {@code Set<String>} pour
 * {@code aud}/{@code groups}, {@code String} pour les claims chaîne.</p>
 */
public final class DefaultJsonWebToken implements JsonWebToken {

    private final JsonObject payload;
    private final String rawToken;

    public DefaultJsonWebToken(JsonObject payload, String rawToken) {
        this.payload = payload;
        this.rawToken = rawToken;
    }

    /**
     * Principal anonyme (aucun claim, {@code getName() == null}, {@code getGroups()} vide) — utilisé
     * par l'intégration CDI quand aucun JWT n'est présent sur la requête courante.
     */
    public static DefaultJsonWebToken anonymous() {
        return new DefaultJsonWebToken(JsonValue.EMPTY_JSON_OBJECT, null);
    }

    @Override
    public String getName() {
        String upn = getClaim("upn");
        if (upn != null) return upn;
        String preferred = getClaim("preferred_username");
        if (preferred != null) return preferred;
        return getClaim(Claims.sub.name());
    }

    @Override
    public Set<String> getClaimNames() {
        return Set.copyOf(payload.keySet());
    }

    /**
     * Valeur JSON brute (non convertie) du claim — support de l'injection {@code @Claim} des types
     * {@code jakarta.json} ({@code JsonValue}, {@code JsonString}, {@code JsonNumber},
     * {@code JsonObject}, {@code JsonArray}) et de la reconstruction d'un {@code Set<String>} depuis
     * un claim tableau quelconque (au-delà de {@code groups}/{@code aud}).
     *
     * @return le {@link JsonValue} du payload, ou {@link JsonValue#NULL} si le claim est absent.
     */
    public JsonValue rawClaim(String claimName) {
        JsonValue v = payload.get(claimName);
        return v == null ? JsonValue.NULL : v;
    }

    @Override
    public String getRawToken() {
        return rawToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
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
            // objets et tableaux : exposés tels quels (JsonObject/JsonArray), conformément à l'usage MP JWT
            default -> v;
        };
    }
}
