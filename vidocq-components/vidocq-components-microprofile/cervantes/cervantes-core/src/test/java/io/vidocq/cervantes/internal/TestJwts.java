package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Test tools: generation of key pairs and JWT forge signed, without any JWT library
 * third party (java.security + JSON-P Champollion). Re-used by validation tests.
 *
 * <p>For ECDSA, {@code Signature.sign()} produces DER; transcoded to JOSE {@code R‖S}
 * via {@link EcdsaSignatures#derToJose} to produce a JWS compliant RFC 7518 §3.4.</p>
 */
final class TestJwts {

    static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private TestJwts() {}

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static KeyPair ecKeyPair(String curve) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(curve)); // e.g. "secp256r1" for ES256
        return g.generateKeyPair();
    }

    /** Forge un JWT compact signé. */
    static String sign(JsonObject header, JsonObject claims, PrivateKey key, SignatureAlgorithm alg) throws Exception {
        String h = B64URL.encodeToString(toJsonBytes(header));
        String p = B64URL.encodeToString(toJsonBytes(claims));
        byte[] signingInput = (h + '.' + p).getBytes(StandardCharsets.US_ASCII);

        Signature signer = Signature.getInstance(alg.jcaName());
        signer.initSign(key);
        signer.update(signingInput);
        byte[] raw = signer.sign();
        byte[] jose = alg.family() == SignatureAlgorithm.Family.EC
                ? EcdsaSignatures.derToJose(raw, alg.ecCoordinateOctets())
                : raw;
        return h + '.' + p + '.' + B64URL.encodeToString(jose);
    }

    /** En-tête JOSE minimal {@code {"alg":..., "typ":"JWT"}} (+ kid optionnel). */
    static JsonObject header(SignatureAlgorithm alg, String kid) {
        var b = Json.createObjectBuilder().add("alg", alg.name()).add("typ", "JWT");
        if (kid != null) b.add("kid", kid);
        return b.build();
    }

    private static byte[] toJsonBytes(JsonObject o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter w = Json.createWriter(out)) {
            w.writeObject(o);
        }
        return out.toByteArray();
    }
}
