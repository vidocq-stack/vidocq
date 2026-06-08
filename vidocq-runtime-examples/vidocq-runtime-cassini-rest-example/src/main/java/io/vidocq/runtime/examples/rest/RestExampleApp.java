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
package io.vidocq.runtime.examples.rest;

import io.vidocq.runtime.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Entry point of the example todo-list application — static UI served by
 * Chappe + REST endpoints {@code /api/todos} provided by Cassini, with
 * in-memory storage in a Vauban CDI service.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -m io.vidocq.runtime.examples.rest/io.vidocq.runtime.examples.rest.RestExampleApp
 * }</pre>
 *
 * <p>Once started:</p>
 * <ul>
 *   <li>UI → {@code http://localhost:8080/}</li>
 *   <li>API → {@code http://localhost:8080/api/todos}</li>
 * </ul>
 */
public class RestExampleApp {

    public static void main(String[] args) throws IOException {
        LogManager.getLogManager().readConfiguration(
                RestExampleApp.class.getResourceAsStream("/logging.properties"));
        Vidocq.main(args);
    }
}
