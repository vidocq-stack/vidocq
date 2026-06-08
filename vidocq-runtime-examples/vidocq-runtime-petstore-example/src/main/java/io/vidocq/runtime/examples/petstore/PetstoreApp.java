package io.vidocq.runtime.examples.petstore;

import io.vidocq.runtime.core.Vidocq;

/**
 * Entry point: delegates to {@link Vidocq#main(String[])} which discovers and orchestrates every
 * extension on the module path:
 *
 * <ol>
 *   <li>{@code mansart-pool} (priority 200) opens an H2 in-memory pool from the {@code vidocq.pool.*}
 *       properties and publishes it as the {@code @Default DataSource}.</li>
 *   <li>{@code mansart-data} (priority 300) probes that DataSource and wires the {@code @Repository}
 *       interfaces.</li>
 *   <li>{@code cassini} (priority 500) mounts the JAX-RS resources on the Chappe HTTP listener.</li>
 *   <li>{@code chappe-bootstrap} (priority 10000) starts the listener on {@code 0.0.0.0:8080}.</li>
 * </ol>
 *
 * <p>Once running:
 * <pre>
 * curl http://localhost:8080/api/pets                       # initial seed
 * curl 'http://localhost:8080/api/pets?status=available'    # findByStatus
 * curl -X POST -H 'content-type: application/json' \
 *      -d '{"name":"Rex","category":"Dog","status":"available","price":120.0,"tags":["friendly"]}' \
 *      http://localhost:8080/api/pets
 * curl http://localhost:8080/api/categories
 * curl http://localhost:8080/api/tags
 * </pre>
 *
 * <p>A minimal web UI is served at {@code http://localhost:8080/}.
 */
public class PetstoreApp {
    public static void main(String[] args) {
        Vidocq.main(args);
    }
}
