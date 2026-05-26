package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse un document JWK Set (RFC 7517) — ou un JWK unique — en clés publiques {@link Jwks},
 * sans dépendance crypto tierce (reconstruction RSA via {@code n}/{@code e} et EC via
 * {@code crv}/{@code x}/{@code y} avec la JCA).
 *
 * <p>Les clés de chiffrement ({@code use:"enc"}) et les {@code kty} non supportés sont ignorés ;
 * une clé individuelle invalide est ignorée plutôt que de faire échouer tout le set.</p>
 */
final class JwkParser {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    private JwkParser() {}

    static Jwks parse(byte[] json) throws JwtValidationException {
        JsonObject root;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(json))) {
            root = reader.readObject();
        } catch (RuntimeException e) {
            throw new JwtValidationException("invalid JWKS JSON", e);
        }

        List<JsonObject> jwks = new ArrayList<>();
        if (root.containsKey("keys") && root.get("keys").getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue v : root.getJsonArray("keys")) {
                if (v.getValueType() == JsonValue.ValueType.OBJECT) jwks.add(v.asJsonObject());
            }
        } else {
            jwks.add(root); // JWK unique
        }

        Map<String, PublicKey> byKid = new LinkedHashMap<>();
        List<PublicKey> all = new ArrayList<>();
        for (JsonObject jwk : jwks) {
            if ("enc".equals(string(jwk, "use"))) continue; // clés de signature uniquement
            PublicKey key = toPublicKey(jwk);
            if (key == null) continue;
            all.add(key);
            String kid = string(jwk, "kid");
            if (kid != null) byKid.put(kid, key);
        }
        return new Jwks(byKid, all);
    }

    private static PublicKey toPublicKey(JsonObject jwk) {
        try {
            String kty = string(jwk, "kty");
            if (kty == null) return null;
            return switch (kty) {
                case "RSA" -> rsaKey(jwk);
                case "EC" -> ecKey(jwk);
                default -> null; // oct (HMAC) et autres non supportés
            };
        } catch (GeneralSecurityException | RuntimeException e) {
            return null; // clé individuelle illisible : on l'ignore
        }
    }

    private static PublicKey rsaKey(JsonObject jwk) throws GeneralSecurityException {
        BigInteger n = uint(jwk, "n");
        BigInteger e = uint(jwk, "e");
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    }

    private static PublicKey ecKey(JsonObject jwk) throws GeneralSecurityException {
        String stdName = switch (string(jwk, "crv")) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            case null, default -> throw new IllegalArgumentException("unsupported EC curve");
        };
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(stdName));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(uint(jwk, "x"), uint(jwk, "y"));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
    }

    /** Entier non signé big-endian depuis un membre base64url du JWK. */
    private static BigInteger uint(JsonObject jwk, String member) {
        String s = string(jwk, member);
        if (s == null) throw new IllegalArgumentException("missing JWK member '" + member + "'");
        return new BigInteger(1, B64URL.decode(s));
    }

    private static String string(JsonObject o, String name) {
        JsonValue v = o.get(name);
        return (v != null && v.getValueType() == JsonValue.ValueType.STRING)
                ? ((jakarta.json.JsonString) v).getString() : null;
    }
}
