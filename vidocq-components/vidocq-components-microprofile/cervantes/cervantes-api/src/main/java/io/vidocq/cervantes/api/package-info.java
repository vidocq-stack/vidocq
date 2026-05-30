/**
 * Stable public SPI of Cervantes (microprofile implementation JWT 2.1).
 *
 * <p>Transport-agnostic contracts shared by validation core, CDI integration and
 * JAX-RS integration: {@link io.vidocq.cervantes.api.JwtValidator},
 * {@link io.vidocq.cervantes.api.KeyResolver}, {@link io.vidocq.cervantes.api.JwtConfig},
 * {@link io.vidocq.cervantes.api.SignatureAlgorithm} et
 * {@link io.vidocq.cervantes.api.JwtValidationException}. This package also re-exposed
 * transitivement la spec {@code org.eclipse.microprofile.jwt}.</p>
 */
package io.vidocq.cervantes.api;
