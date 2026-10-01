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
 * Runs the dev service providers for {@code vidocq:dev}, {@code vidocq:run}, {@code vidocq:test} and a test run:
 * finds them with {@code ServiceLoader}, starts and stops them, and writes their state. Runs in the Maven JVM or the
 * test JVM, never on an application's module path (Vidocq/vidocq#177).
 */
module io.vidocq.runtime.devservices.host {
    requires transitive io.vidocq.runtime.devservices.spi;
    requires io.vidocq.runtime.core;

    exports io.vidocq.runtime.devservices.host;

    uses io.vidocq.runtime.devservices.spi.DevService;
}
