package io.vidocq.cervantes.internal;

import jakarta.json.JsonObject;

/**
 * Result of the decoding of a compact JWT, before signature verification.
 *
 * @param header header decoded JOSE ({@code alg}, {@code kid}, {@code typ},...)
 * @param claims payload decoded (claims)
 * @param signingInput octets {@code base64url(header).base64url(payload)} sur lesquels porte la signature
 * @param decoded signature signature (base64url), raw form (JOSE {@code R‖S} for ECDSA)
 * @param rawToken     le JWT compact d'origine
 */
record ParsedJwt(JsonObject header, JsonObject claims, byte[] signingInput, byte[] signature, String rawToken) {
}
