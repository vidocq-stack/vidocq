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
package io.vidocq.runtime.examples.mansart;

import io.vidocq.runtime.core.Vidocq;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.VidocqDataSources;

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
 * curl http://localhost:8080/api/products # initial seed: 3 coffees
 * curl http://localhost:8080/api/products/count # → 3
 * curl -X POST -H 'content-type: application/json' \
 *      -d '{"name":"Mocha","price":4.5}' \
 *      http://localhost:8080/api/products
 * curl 'http://localhost:8080/api/products?name=%25at%25' # findByNameLike
 * </pre>
 *
 * <p>The {@code @VidocqDataSources("audit")} declaration makes the build generate a
 * {@code @Named("audit")} {@link javax.sql.DataSource} holder, so the app can {@code @Inject}
 * a second, distinct datasource and target it with {@code @Repository(dataStore = "audit")}.
 */
@VidocqDataSources("audit")
public class MansartH2ExampleApp {
    static void main(String[] args) {
        Vidocq.main(args);
    }
}
