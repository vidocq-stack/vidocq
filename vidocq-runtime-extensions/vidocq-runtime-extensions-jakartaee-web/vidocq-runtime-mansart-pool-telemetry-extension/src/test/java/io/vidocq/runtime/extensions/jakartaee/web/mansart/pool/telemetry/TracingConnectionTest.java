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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement run inside a request is a CLIENT span, child of the request's span, named and described per the
 * OpenTelemetry database conventions (BUG-20261030-25). Runs on a real H2 database through the decorator the pool
 * applies, with Humboldt's SDK and an in-memory exporter.
 */
class TracingConnectionTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private Tracer tracer;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = provider.get("test");
        Connection raw = DriverManager.getConnection("jdbc:h2:mem:library;DB_CLOSE_DELAY=-1", "sa", "");
        connection = new TracingJdbcConnectionDecorator(() -> provider.get(TracingJdbcConnectionDecorator.SCOPE))
                .decorate("default", raw);
        try (Statement ddl = raw.createStatement()) {
            ddl.execute("create table if not exists books (id int primary key, title varchar(100))");
            ddl.execute("delete from books");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
        provider.close();
    }

    private List<SpanData> databaseSpans() {
        return exporter.getFinishedSpans().stream().filter(s -> s.kind() == SpanKind.CLIENT).toList();
    }

    @Test
    void aPreparedQueryInsideARequestIsAChildClientSpan() throws SQLException {
        Span request = tracer.spanBuilder("GET /books/{id}").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope _ = request.makeCurrent();
             PreparedStatement select = connection.prepareStatement("select title from books where id = ?")) {
            select.setInt(1, 7);
            try (ResultSet rs = select.executeQuery()) {
                assertFalse(rs.next());
            }
        } finally {
            request.end();
        }

        SpanData db = databaseSpans().getFirst();
        assertEquals(request.getSpanContext().getSpanId(), db.parentSpanContext().getSpanId());
        assertEquals(request.getSpanContext().getTraceId(), db.spanContext().getTraceId());
        assertEquals("SELECT books", db.name());
        assertEquals("h2", db.attributes().get(AttributeKey.stringKey("db.system.name")));
        assertEquals("library", db.attributes().get(AttributeKey.stringKey("db.namespace")));
        assertEquals("SELECT", db.attributes().get(AttributeKey.stringKey("db.operation.name")));
        assertEquals("books", db.attributes().get(AttributeKey.stringKey("db.collection.name")));
        assertEquals("select title from books where id = ?",
                db.attributes().get(AttributeKey.stringKey("db.query.text")));
    }

    @Test
    void aPlainStatementKeepsItsLiteralsOutOfTheSpan() throws SQLException {
        try (Statement insert = connection.createStatement()) {
            assertEquals(1, insert.executeUpdate("insert into books values (1, 'Les Misérables')"));
        }

        SpanData db = databaseSpans().getFirst();
        assertEquals("INSERT books", db.name());
        assertEquals("insert into books values (?, ?)", db.attributes().get(AttributeKey.stringKey("db.query.text")));
    }

    @Test
    void aFailingStatementRecordsTheErrorAndRethrowsIt() throws SQLException {
        try (Statement broken = connection.createStatement()) {
            assertThrows(SQLException.class, () -> broken.executeQuery("select * from no_such_table"));
        }

        SpanData db = databaseSpans().getFirst();
        assertEquals(StatusCode.ERROR, db.status().code());
        assertTrue(db.events().stream().anyMatch(e -> e.name().equals("exception")));
        assertFalse(db.attributes().get(AttributeKey.stringKey("db.response.status_code")).isBlank());
    }

    @Test
    void aBatchIsOneSpan() throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("insert into books values (?, ?)")) {
            for (int i = 1; i <= 3; i++) {
                insert.setInt(1, i);
                insert.setString(2, "t" + i);
                insert.addBatch();
            }
            assertEquals(3, insert.executeBatch().length);
        }

        assertEquals(1, databaseSpans().size());
        assertEquals("INSERT books", databaseSpans().getFirst().name());
    }

    @Test
    void theWrapperStaysTransparent() throws SQLException {
        try (Statement statement = connection.createStatement();
             CallableStatement call = connection.prepareCall("call 1")) {
            assertSame(connection, statement.getConnection());
            assertSame(connection, call.getConnection());
            assertTrue(connection.isWrapperFor(org.h2.jdbc.JdbcConnection.class));
            assertTrue(connection.unwrap(org.h2.jdbc.JdbcConnection.class) instanceof org.h2.jdbc.JdbcConnection);
            assertTrue(call.execute());
        }
        assertEquals(1, databaseSpans().size());
    }
}
