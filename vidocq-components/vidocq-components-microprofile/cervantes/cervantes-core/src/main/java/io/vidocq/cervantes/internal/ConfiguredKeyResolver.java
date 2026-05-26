package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link KeyResolver} à clé unique (M1) : {@code mp.jwt.verify.publickey} / {@code .location}
 * pointant une seule clé publique. Le {@code kid} est ignoré (la résolution par {@code kid} via
 * JWKS arrive au jalon M2). L'adéquation famille de clé ↔ algorithme est vérifiée par
 * {@link JwtSignatureVerifier}.
 */
public final class ConfiguredKeyResolver implements KeyResolver {

    private final PublicKey key;

    public ConfiguredKeyResolver(PublicKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException {
        return Optional.of(key);
    }
}
