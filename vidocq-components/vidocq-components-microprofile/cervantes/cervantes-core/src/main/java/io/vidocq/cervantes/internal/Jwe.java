package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;

import java.nio.charset.StandardCharsets;

/**
 * Fabrique publique de {@link JweDecryptor} à partir de la configuration MicroProfile JWT
 * ({@code mp.jwt.decrypt.key} / {@code mp.jwt.decrypt.key.location}). Façade stable pour les
 * intégrations : garde {@code JwksSource} (lecture fichier/URL) interne au package core.
 */
public final class Jwe {

    private Jwe() {}

    /** Clé privée de déchiffrement inline (PEM PKCS#8), depuis {@code mp.jwt.decrypt.key}. */
    public static JweDecryptor decryptorFromInlinePem(String pem) throws JwtValidationException {
        return new JweDecryptor(PemKeys.privateKeyFromPem(pem));
    }

    /** Clé privée depuis {@code mp.jwt.decrypt.key.location} (fichier ou URL ; contenu PEM PKCS#8). */
    public static JweDecryptor decryptorFromLocation(String location) throws JwtValidationException {
        byte[] bytes = JwksSource.fromLocation(location).fetch();
        return new JweDecryptor(PemKeys.privateKeyFromPem(new String(bytes, StandardCharsets.UTF_8)));
    }
}
