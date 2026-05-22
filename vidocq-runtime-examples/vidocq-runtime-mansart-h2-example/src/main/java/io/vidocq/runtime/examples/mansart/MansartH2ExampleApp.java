package io.vidocq.runtime.examples.mansart;

import io.vidocq.runtime.core.Vidocq;

/**
 * Entry point: delegates to {@link Vidocq#main(String[])} which discovers and orchestrates every
 * extension on the module path:
 *
 * <ol>
 *   <li>{@code mansart-pool} (priority 200) opens an H2 in-memory pool from the {@code vidocq.pool.*}
 *       properties and publishes it as the {@code @Default DataSource}.</li>
 *   <li>{@code mansart-data} (priority 300) probes that DataSource and logs the discovered
 *       {@code @Repository} interfaces.</li>
 *   <li>{@code cassini} (priority 500) wires the JAX-RS resources into the Chappe HTTP listener.</li>
 *   <li>{@code chappe-bootstrap} (priority 10000) starts the listener on {@code 0.0.0.0:8080}.</li>
 * </ol>
 *
 * <p>Once running:
 * <pre>
 * curl http://localhost:8080/api/products              # initial seed: 3 coffees
 * curl http://localhost:8080/api/products/count        # → 3
 * curl -X POST -H 'content-type: application/json' \
 *      -d '{"name":"Mocha","price":4.5}' \
 *      http://localhost:8080/api/products
 * curl 'http://localhost:8080/api/products?name=%25at%25'   # findByNameLike
 * </pre>
 */
public class MansartH2ExampleApp {
    public static void main(String[] args) {
        Vidocq.main(args);
    }
}
