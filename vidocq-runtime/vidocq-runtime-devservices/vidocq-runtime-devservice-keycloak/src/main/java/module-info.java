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

/**
 * The Keycloak dev service. Testcontainers ships no module name of its own: {@code testcontainers} is the name its
 * jar's file gives it, which this module requires knowingly. It runs on the Maven plugin's class path (and the test
 * JVM's), never on an application's module path, where such a name would be fragile (Vidocq/vidocq#177).
 */
@SuppressWarnings("requires-automatic")
module io.vidocq.runtime.devservices.keycloak {
    requires io.vidocq.runtime.devservices.spi;
    requires testcontainers;
    requires com.github.dockerjava.api;

    provides io.vidocq.runtime.devservices.spi.DevService
            with io.vidocq.runtime.devservices.keycloak.KeycloakDevService;
}
