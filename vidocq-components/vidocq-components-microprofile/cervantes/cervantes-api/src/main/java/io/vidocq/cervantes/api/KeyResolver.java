package io.vidocq.cervantes.api;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Résout la clé publique de vérification d'un JWT à partir de l'en-tête du token.
 *
 * <p>Implémentations prévues : clé unique configurée (PEM/inline, M1) puis JWKS avec résolution
 * par {@code kid} et rotation (M2). Le {@code kid} peut être {@code null} (en-tête sans {@code kid},
 * cas d'une clé unique).</p>
 */
@FunctionalInterface
public interface KeyResolver {

    /**
     * @param kid       valeur de l'en-tête {@code kid} (peut être {@code null})
     * @param algorithm algorithme déclaré dans l'en-tête {@code alg}
     * @return la clé publique correspondante, ou vide si aucune ne convient
     * @throws JwtValidationException si la résolution échoue de façon irrécupérable (ex. JWKS injoignable)
     */
    Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException;
}
