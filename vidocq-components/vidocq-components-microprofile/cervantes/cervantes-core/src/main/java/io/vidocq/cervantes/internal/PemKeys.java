package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Charge une clé publique depuis un PEM {@code SubjectPublicKeyInfo} ({@code -----BEGIN PUBLIC KEY-----})
 * via la JCA — sans dépendance crypto tierce. Couvre RSA et EC (X.509 {@link X509EncodedKeySpec}).
 */
public final class PemKeys {

    private PemKeys() {}

    public static PublicKey fromPem(String pem, SignatureAlgorithm.Family family) throws JwtValidationException {
        String base64 = pem
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            String algorithm = switch (family) {
                case RSA -> "RSA";
                case EC -> "EC";
            };
            return KeyFactory.getInstance(algorithm).generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new JwtValidationException("invalid PEM public key", e);
        }
    }

    /**
     * Charge une clé privée RSA depuis un PEM PKCS#8 ({@code -----BEGIN PRIVATE KEY-----}) — clé de
     * déchiffrement JWE ({@code mp.jwt.decrypt.key} / {@code .location}).
     */
    public static PrivateKey privateKeyFromPem(String pem) throws JwtValidationException {
        String base64 = pem
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new JwtValidationException("invalid PEM private key", e);
        }
    }
}
