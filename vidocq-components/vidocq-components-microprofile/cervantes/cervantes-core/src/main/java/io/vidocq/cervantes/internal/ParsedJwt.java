package io.vidocq.cervantes.internal;

import jakarta.json.JsonObject;

/**
 * Résultat du décodage d'un JWT compact, avant vérification de signature.
 *
 * @param header       en-tête JOSE décodé ({@code alg}, {@code kid}, {@code typ}, …)
 * @param claims       payload décodé (les claims)
 * @param signingInput octets {@code base64url(header).base64url(payload)} sur lesquels porte la signature
 * @param signature    signature décodée (base64url), forme brute (JOSE {@code R‖S} pour ECDSA)
 * @param rawToken     le JWT compact d'origine
 */
record ParsedJwt(JsonObject header, JsonObject claims, byte[] signingInput, byte[] signature, String rawToken) {
}
