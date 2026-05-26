package io.vidocq.cervantes.internal;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Forge un JWE compact ({@code RSA-OAEP-256} + {@code A256GCM}) enveloppant un JWS — côté émetteur,
 * inverse de {@link JweDecryptor}, pour les tests. {@code javax.crypto} uniquement.
 */
final class TestJwe {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private TestJwe() {}

    /** Chiffre {@code nestedJws} pour le destinataire {@code recipientPublic} (RSA-OAEP-256 / A256GCM). */
    static String encrypt(String nestedJws, PublicKey recipientPublic) throws Exception {
        String protectedHeader = "{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\",\"cty\":\"JWT\"}";
        String b64Header = B64URL.encodeToString(protectedHeader.getBytes(StandardCharsets.US_ASCII));

        // CEK 256 bits, enveloppée RSA-OAEP-256
        byte[] cek = new byte[32];
        RANDOM.nextBytes(cek);
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, recipientPublic,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encryptedKey = rsa.doFinal(cek);

        // Contenu AES-256-GCM, IV 96 bits, AAD = ASCII(base64url(header))
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(b64Header.getBytes(StandardCharsets.US_ASCII));
        byte[] cipherAndTag = aes.doFinal(nestedJws.getBytes(StandardCharsets.UTF_8));

        int tagLen = 16; // 128 bits
        byte[] ciphertext = Arrays.copyOfRange(cipherAndTag, 0, cipherAndTag.length - tagLen);
        byte[] tag = Arrays.copyOfRange(cipherAndTag, cipherAndTag.length - tagLen, cipherAndTag.length);

        return String.join(".",
                b64Header,
                B64URL.encodeToString(encryptedKey),
                B64URL.encodeToString(iv),
                B64URL.encodeToString(ciphertext),
                B64URL.encodeToString(tag));
    }
}
