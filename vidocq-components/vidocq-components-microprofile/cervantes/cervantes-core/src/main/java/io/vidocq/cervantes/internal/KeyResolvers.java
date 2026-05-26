package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.nio.charset.StandardCharsets;

/**
 * Fabrique publique de {@link KeyResolver} à partir de la configuration MicroProfile JWT
 * ({@code mp.jwt.verify.publickey} / {@code .location}). Façade stable pour les intégrations
 * (CDI, JAX-RS) : garde {@code JwksSource}/{@code JwkParser} internes au package core.
 */
public final class KeyResolvers {

    private KeyResolvers() {}

    /** Clé publique inline (PEM ou base64 X.509), depuis {@code mp.jwt.verify.publickey}. */
    public static KeyResolver fromInlinePem(String pem, SignatureAlgorithm.Family family) throws JwtValidationException {
        return new ConfiguredKeyResolver(PemKeys.fromPem(pem, family));
    }

    /**
     * Depuis {@code mp.jwt.verify.publickey.location} : URL HTTP(S) → JWKS distant ; fichier JSON
     * (commençant par <code>{</code>) → JWKS local ; sinon fichier PEM → clé publique unique.
     */
    public static KeyResolver fromLocation(String location, SignatureAlgorithm.Family family) throws JwtValidationException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return new JwksKeyResolver(JwksSource.fromLocation(location));
        }
        byte[] bytes = JwksSource.fromLocation(location).fetch();
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.startsWith("{")) {
            return new JwksKeyResolver(() -> bytes);
        }
        return new ConfiguredKeyResolver(PemKeys.fromPem(content, family));
    }
}
