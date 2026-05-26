/**
 * SPI publique stable de Cervantes (implémentation MicroProfile JWT 2.1).
 *
 * <p>Contrats transport-agnostiques partagés par le core de validation, l'intégration CDI et
 * l'intégration JAX-RS : {@link io.vidocq.cervantes.api.JwtValidator},
 * {@link io.vidocq.cervantes.api.KeyResolver}, {@link io.vidocq.cervantes.api.JwtConfig},
 * {@link io.vidocq.cervantes.api.SignatureAlgorithm} et
 * {@link io.vidocq.cervantes.api.JwtValidationException}. Ce package ré-expose aussi
 * transitivement la spec {@code org.eclipse.microprofile.jwt}.</p>
 */
package io.vidocq.cervantes.api;
