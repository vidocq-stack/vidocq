package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Decode a compact {@code header.payload.signature} JWT in {@link ParsedJwt}.
 *
 * <p>Decodage base64url (RFC 7515 §2, optional padding) then read JSON via JSON-P (Champollion).
 * Check the NI signature for claims — this is the role of {@link JwtSignatureVerifier} and
 * {@link JwtClaimsValidator}.</p>
 */
final class JwtParser {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    ParsedJwt parse(String token) throws JwtValidationException {
        if (token == null || token.isBlank()) {
            throw new JwtValidationException("empty token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtValidationException("a JWT must have exactly 3 dot-separated parts, got " + parts.length);
        }
        if (parts[2].isEmpty()) {
            throw new JwtValidationException("unsigned token (empty signature) is not accepted");
        }
        JsonObject header = readJson(parts[0], "header");
        JsonObject claims = readJson(parts[1], "payload");
        byte[] signingInput = (parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature = decode(parts[2], "signature");
        return new ParsedJwt(header, claims, signingInput, signature, token);
    }

    private JsonObject readJson(String segment, String what) throws JwtValidationException {
        byte[] json = decode(segment, what);
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(json))) {
            return reader.readObject();
        } catch (RuntimeException e) {
            throw new JwtValidationException("invalid JSON in JWT " + what, e);
        }
    }

    private byte[] decode(String segment, String what) throws JwtValidationException {
        try {
            return B64URL.decode(segment);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException("invalid base64url in JWT " + what, e);
        }
    }
}
