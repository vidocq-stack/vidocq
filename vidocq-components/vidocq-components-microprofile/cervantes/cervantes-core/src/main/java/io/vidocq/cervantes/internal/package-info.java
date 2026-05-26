/**
 * Moteur de validation JWT de Cervantes — pur Java 25, sans CDI ni JAX-RS.
 *
 * <p>Décodage ({@link io.vidocq.cervantes.internal.JwtParser}), vérification de signature
 * RSA/ECDSA via la JCA ({@link io.vidocq.cervantes.internal.JwtSignatureVerifier},
 * {@link io.vidocq.cervantes.internal.EcdsaSignatures}), validation des claims
 * ({@link io.vidocq.cervantes.internal.JwtClaimsValidator}), chargement de clé PEM
 * ({@link io.vidocq.cervantes.internal.PemKeys}) et orchestration
 * ({@link io.vidocq.cervantes.internal.DefaultJwtValidator}). Package interne : non exporté
 * sans qualification (consommé par les intégrations CDI et JAX-RS à partir de M3/M4).</p>
 */
package io.vidocq.cervantes.internal;
