/**
 * SPI publique stable de Cervantes et ré-exposition de la spec MicroProfile JWT 2.1.
 *
 * <p>Exporte {@code io.vidocq.cervantes.api} : la SPI transport-agnostique partagée par le core,
 * l'intégration CDI et l'intégration JAX-RS ({@code JwtValidator}, {@code KeyResolver},
 * {@code JwtConfig}, {@code SignatureAlgorithm}, {@code JwtValidationException}). Ré-expose
 * transitivement la spec {@code org.eclipse.microprofile.jwt}.</p>
 */
module io.vidocq.cervantes.api {
    requires transitive org.eclipse.microprofile.jwt;

    exports io.vidocq.cervantes.api;
}
