/**
 * Stable public SPI of Cervantes and re-exposure of the spec MicroProfile JWT 2.1.
 *
 * <p>Export {@code io.vidocq.cervantes.api}: the transport-agnostic SPI shared by the core,
 * CDI integration and JAX-RS integration ({@code JwtValidator}, {@code KeyResolver},
 * {@code JwtConfig}, {@code SignatureAlgorithm}, {@code JwtValidationException}). Reexposed
 * transitivement la spec {@code org.eclipse.microprofile.jwt}.</p>
 */
module io.vidocq.cervantes.api {
    requires transitive org.eclipse.microprofile.jwt;

    exports io.vidocq.cervantes.api;
}
