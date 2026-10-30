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
 * Glue between the Mansart Pool and Telemetry (Humboldt) extensions: a {@code JdbcConnectionDecorator} that turns
 * each statement the application runs into an OpenTelemetry CLIENT span (BUG-20261030-25). Nothing to require from
 * the application: the runtime resolves every module of its module path, and the pool finds the decorator through
 * {@code ServiceLoader}.
 */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry {
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
    requires io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires java.sql;

    provides io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.JdbcConnectionDecorator
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry.TracingJdbcConnectionDecorator;
}
