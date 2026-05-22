/**
 * Endpoint REST Dirac — exposition des métriques MicroProfile Metrics 5.1.1 §2.3.
 *
 * <p>{@link io.vidocq.dirac.rest.MetricsResource} : {@code GET /metrics},
 * {@code GET /metrics/{scope}}, {@code GET /metrics/{scope}/{name}}.</p>
 * <p>{@link io.vidocq.dirac.rest.ContentNegotiationFilter} : normalise l'en-tête Accept.</p>
 */
package io.vidocq.dirac.rest;
