package io.vidocq.runtime.it.jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/**
 * Test tools: generation of an RSA pair and forging of signed JWT RS256, without any library
 * Third-party JWT — only {@code java.security} and minimal JSON encoding. Modeled on the
 * {@code TestJwts} from cervantes-core.
 */
final class TestJwt {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private TestJwt() {}

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** Base64 encoded X.509 public key (expected value of {@code mp.jwt.verify.publickey}). */
    static String publicKeyBase64(java.security.PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Forge a compact RS256 JWT signed with the minimum MicroProfile JWT claims
     * ({@code iss}, {@code sub}, {@code upn}, {@code groups}, {@code exp}, {@code iat}, {@code jti}).
     *
     * @param groups the groups (roles) of the token, or empty list
     */
    static String signRs256(PrivateKey key, String issuer, String subject, List<String> groups) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = "{"
                + "\"iss\":\"" + issuer + "\","
                + "\"sub\":\"" + subject + "\","
                + "\"upn\":\"" + subject + "\","
                + "\"jti\":\"it-" + now + "\","
                + "\"groups\":[" + groups.stream().map(g -> "\"" + g + "\"").reduce((a, b) -> a + "," + b).orElse("") + "],"
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + 300)
                + "}";

        String h = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String p = B64URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        byte[] signingInput = (h + '.' + p).getBytes(StandardCharsets.US_ASCII);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(signingInput);
        byte[] sig = signer.sign();

        return h + '.' + p + '.' + B64URL.encodeToString(sig);
    }
}
