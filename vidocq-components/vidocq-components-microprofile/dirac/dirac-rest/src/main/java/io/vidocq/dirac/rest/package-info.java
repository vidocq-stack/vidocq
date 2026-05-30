/**
 * Dirac REST endpoint — exposure of MicroProfile Metrics 5.1.1 §2.3 metrics.
 *
 * <p>{@link io.vidocq.dirac.rest.MetricsResource} : {@code GET /metrics},
 * {@code GET /metrics/{scope}}, {@code GET /metrics/{scope}/{name}}.</p>
 * <p>{@link io.vidocq.dirac.rest.ContentNegotiationFilter} : normalizes the Accept header.</p>
 */
package io.vidocq.dirac.rest;
