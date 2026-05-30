package io.vidocq.cervantes.api;

/**
 * JWT validation failed: invalid format, unsupported algorithm, incorrect signature,
 * Key not found, or invalid claim ({@code iss}/{@code aud}/{@code exp}/{@code nbf}).
 *
 * <p>Volunteerly not detailed on HTTP calling side (the authentication filter translates it into
 * {@code 401}). Message remains accurate for server logs, never returned to client.ZZPH1ZZ
 */
public class JwtValidationException extends Exception {

    public JwtValidationException(String message) {
        super(message);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
