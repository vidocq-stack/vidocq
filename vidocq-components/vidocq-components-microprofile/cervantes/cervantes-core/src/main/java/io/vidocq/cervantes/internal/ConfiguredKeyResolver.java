package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link KeyResolver} single key (M1): {@code mp.jwt.verify.publickey} / {@code.location}
 * pointing to a single public key. {@code kid} is ignored (resolution by {@code kid} via
 * JWKS reaches M2 The match key family 
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
