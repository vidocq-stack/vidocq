package io.vidocq.cervantes.api;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Valide un JWT compact ({@code header.payload.signature}) et en produit un {@link JsonWebToken}.
 *
 * <p>Continuous validation: parsing, key resolution ({@link KeyResolver}), verification of
 * signature, then validation of claims according to a {@link JwtConfig}. Any failure shall result in a
 * {@link JwtValidationException} — a valid token never returns ZZPH1ZZ.ZZPH2ZZ
 */
public interface JwtValidator {

    /**
     * @param token compact JWT (without {@code "Bearer "} prefix)
     * @return the {@link JsonWebToken} principal if the token is valid
     * @throws JwtValidationException if token is poorly formed, not signed correctly, or invalid
     */
    JsonWebToken validate(String token) throws JwtValidationException;
}
