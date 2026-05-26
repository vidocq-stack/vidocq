package io.vidocq.cervantes.api;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Valide un JWT compact ({@code header.payload.signature}) et en produit un {@link JsonWebToken}.
 *
 * <p>La validation enchaîne : parsing, résolution de clé ({@link KeyResolver}), vérification de
 * signature, puis validation des claims selon une {@link JwtConfig}. Toute défaillance lève une
 * {@link JwtValidationException} — un token valide ne renvoie jamais {@code null}.</p>
 */
public interface JwtValidator {

    /**
     * @param token le JWT compact (sans le préfixe {@code "Bearer "})
     * @return le principal {@link JsonWebToken} si le token est valide
     * @throws JwtValidationException si le token est mal formé, non signé correctement, ou invalide
     */
    JsonWebToken validate(String token) throws JwtValidationException;
}
