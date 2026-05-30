package io.vidocq.cervantes.api;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Resolves the public JWT verification key from the token header.
 *
 * <p>Planned implementations: unique key configured (PEM/inline, M1) then JWKS with resolution
 * by {@code kid} and rotation (M2). The {@code kid} can be {@code null} (header without {@code kid},
 * a single key).</p>
 */
@FunctionalInterface
public interface KeyResolver {

    /**
     * @param kid value of the header {@code kid} (may be {@code null})
     * @param algorithm declared in the {@code alg} header
     * @return the corresponding public key, or empty if none is suitable
     * @throws JwtValidationException if resolution fails irrecoverably (e.g. JWKS unattainable)
     */
    Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException;
}
