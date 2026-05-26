package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * Fabrique publique de {@link JweDecryptor} à partir de la configuration MicroProfile JWT
 * ({@code mp.jwt.decrypt.key} / {@code mp.jwt.decrypt.key.location}). Façade stable pour les
 * intégrations : garde {@code JwksSource} (lecture fichier/URL) interne au package core.
 *
 * <p>Supporte : PEM PKCS#8 ({@code -----BEGIN PRIVATE KEY-----}) et JWK/JWKS JSON (détecté
 * par le premier caractère '{') pour {@code mp.jwt.decrypt.key.location} pointant vers un
 * fichier JWK de clé privée RSA (MP JWT 2.1 spec §"JWE Private Key").</p>
 */
public final class Jwe {

    private Jwe() {}

    /**
     * Clé privée de déchiffrement inline (PEM PKCS#8 ou JWK JSON), depuis {@code mp.jwt.decrypt.key}.
     * MP JWT spec §9.2.4 : la valeur peut être un PEM PKCS#8 ou un JWK JSON.
     *
     * @param value          raw key value (PEM or JWK JSON)
     * @param requiredAlgorithm optional {@code mp.jwt.decrypt.key.algorithm} value to enforce (null = no check)
     */
    public static JweDecryptor decryptorFromInlinePem(String value, String requiredAlgorithm) throws JwtValidationException {
        return new JweDecryptor(privateKeyFromValue(value.trim().getBytes(StandardCharsets.UTF_8)), requiredAlgorithm);
    }

    /** @deprecated Use {@link #decryptorFromInlinePem(String, String)} */
    public static JweDecryptor decryptorFromInlinePem(String value) throws JwtValidationException {
        return decryptorFromInlinePem(value, null);
    }

    /**
     * Clé privée depuis {@code mp.jwt.decrypt.key.location} (fichier classpath, URL, ou chemin FS).
     * Formats supportés : PEM PKCS#8, JWK JSON, JWKS JSON (premier caractère '{').
     *
     * @param location       MP Config location value
     * @param requiredAlgorithm optional {@code mp.jwt.decrypt.key.algorithm} value to enforce (null = no check)
     */
    public static JweDecryptor decryptorFromLocation(String location, String requiredAlgorithm) throws JwtValidationException {
        byte[] bytes = JwksSource.fromLocation(location).fetch();
        return new JweDecryptor(privateKeyFromValue(bytes), requiredAlgorithm);
    }

    /** @deprecated Use {@link #decryptorFromLocation(String, String)} */
    public static JweDecryptor decryptorFromLocation(String location) throws JwtValidationException {
        return decryptorFromLocation(location, null);
    }

    private static PrivateKey privateKeyFromValue(byte[] bytes) throws JwtValidationException {
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.startsWith("{")) {
            // JWK or JWKS JSON — parse private key from JWK document
            return JwkParser.parsePrivateKey(bytes);
        }
        // PEM PKCS#8 — default format
        return PemKeys.privateKeyFromPem(content);
    }
}
