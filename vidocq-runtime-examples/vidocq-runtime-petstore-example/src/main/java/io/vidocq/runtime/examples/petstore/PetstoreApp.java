/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
