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
package io.vidocq.ravel.spi;

/**
 * Static metadata for the Ravel implementation — used by {@code ConfigSource}
 * for tracing and by benchmarks.
 *
 * <p>The SPI content will be expanded over versions:
 * configuration sources, third-party converters, observability hooks. This class
 * is intentionally kept minimal at this stage.</p>
 */
public final class Ravel {

    /** Logical name of the implementation, exposed via {@code Config.getConfigSources()}. */
    public static final String IMPLEMENTATION_NAME = "ravel";

    /** Version of the Ravel implementation. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    /** Version of the MicroProfile Config spec implemented. */
    public static final String SPEC_VERSION = "3.1";

    private Ravel() {
        // utility class
    }
}
