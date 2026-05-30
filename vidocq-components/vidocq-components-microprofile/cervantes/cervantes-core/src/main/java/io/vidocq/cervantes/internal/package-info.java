/**
 * Cervantes JWT validation engine — pure Java 25, without CDI or JAX-RS.
 *
 * <p>Decoding ({@link io.vidocq.cervantes.internal.JwtParser}), signature check
 * RSA/ECDSA via la JCA ({@link io.vidocq.cervantes.internal.JwtSignatureVerifier},
 * {@link io.vidocq.cervantes.internal.EcdsaSignatures}), claim validation
 * ({@link io.vidocq.cervantes.internal.JwtClaimsValidator}), PEM key loading
 * ({@link io.vidocq.cervantes.internal.PemKeys}) et orchestration
 * ({@link io.vidocq.cervantes.internal.DefaultJwtValidator}). Internal package: not exported
 * without qualification (consumed by CDI and JAX-RS integrations from M3/M4).</p>
 */
package io.vidocq.cervantes.internal;
