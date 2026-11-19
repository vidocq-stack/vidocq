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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.JdbcConnectionDecorator;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * The pool's {@link JdbcConnectionDecorator} that traces JDBC: each connection the application gets from a Vidocq
 * DataSource is wrapped, and each statement it runs becomes a CLIENT span named and described per the OpenTelemetry
 * database conventions (BUG-20261030-25). The tracer is read from {@link GlobalOpenTelemetry} at each statement,
 * which Humboldt sets up when the application starts.
 */
public final class TracingJdbcConnectionDecorator implements JdbcConnectionDecorator {

    /** The instrumentation scope of the spans. */
    static final String SCOPE = "io.vidocq.runtime.mansart.pool.jdbc";

    private final Supplier<Tracer> tracer;

    /** Used by {@link java.util.ServiceLoader}, through the module's {@code provides}. */
    public TracingJdbcConnectionDecorator() {
        this(() -> GlobalOpenTelemetry.get().getTracer(SCOPE));
    }

    TracingJdbcConnectionDecorator(Supplier<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public Connection decorate(String dataSourceName, Connection connection) {
        return new DelegatingConnection(connection,
                new TracingExecution(tracer, dataSourceName, connection));
    }
}
