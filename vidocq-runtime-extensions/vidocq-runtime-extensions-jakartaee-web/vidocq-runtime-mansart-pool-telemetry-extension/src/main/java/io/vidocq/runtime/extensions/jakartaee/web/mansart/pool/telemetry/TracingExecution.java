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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs each statement of one connection in a CLIENT span, child of the current context: the request's server span
 * under Humboldt. The span opens and closes within the call, on the caller's (virtual) thread, so nothing is kept in
 * a {@code ThreadLocal}.
 */
final class TracingExecution implements JdbcExecution {

    private final Supplier<Tracer> tracer;
    private final String dataSourceName;
    private final Connection connection;
    private final AtomicReference<DbTarget> target = new AtomicReference<>();

    /** @param connection the pool's connection, whose URL names the database, read once, at the first statement */
    TracingExecution(Supplier<Tracer> tracer, String dataSourceName, Connection connection) {
        this.tracer = tracer;
        this.dataSourceName = dataSourceName;
        this.connection = connection;
    }

    @Override
    public <R> R execute(String sql, boolean prepared, SqlCall<R> call) throws SQLException {
        DbTarget db = target();
        SqlStatements.Parsed parsed = SqlStatements.parse(sql);
        SpanBuilder builder = tracer.get().spanBuilder(SqlStatements.spanName(parsed, db.system()))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system.name", db.system());
        if (db.namespace() != null) {
            builder.setAttribute("db.namespace", db.namespace());
        }
        if (sql != null) {
            builder.setAttribute("db.query.text", prepared ? sql : SqlStatements.sanitize(sql));
        }
        if (!parsed.operation().isEmpty()) {
            builder.setAttribute("db.operation.name", parsed.operation());
        }
        if (!parsed.collection().isEmpty()) {
            builder.setAttribute("db.collection.name", parsed.collection());
        }
        if (db.host() != null) {
            builder.setAttribute("server.address", db.host());
        }
        if (db.port() > 0) {
            builder.setAttribute("server.port", db.port());
        }
        builder.setAttribute("vidocq.datasource.name", dataSourceName);
        Span span = builder.startSpan();
        try (Scope _ = span.makeCurrent()) {
            return call.call();
        } catch (SQLException e) {
            fail(span, e);
            span.setAttribute("db.response.status_code",
                    e.getSQLState() != null ? e.getSQLState() : String.valueOf(e.getErrorCode()));
            throw e;
        } catch (RuntimeException e) {
            fail(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static void fail(Span span, Exception e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        span.setAttribute("error.type", e.getClass().getName());
    }

    /** The database, from the connection's URL, read once; an unreadable URL gives {@code other_sql}. */
    private DbTarget target() {
        DbTarget db = target.get();
        if (db == null) {
            String url;
            try {
                url = connection.getMetaData().getURL();
            } catch (SQLException | RuntimeException _) {
                url = null;
            }
            db = DbTarget.of(url);
            target.set(db);
        }
        return db;
    }
}
