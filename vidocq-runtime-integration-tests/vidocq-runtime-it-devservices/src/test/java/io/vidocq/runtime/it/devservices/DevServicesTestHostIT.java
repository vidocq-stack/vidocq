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
package io.vidocq.runtime.it.devservices;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The JUnit host (spec §7): {@code vidocq-runtime-devservices-junit}'s {@code DevServicesSessionListener}
 * starts one PostgreSQL container for this module's whole test run, before any test class runs, and
 * injects its JDBC coordinates as system properties. This proves two things end-to-end, with real Docker:
 *
 * <ol>
 *   <li>{@code select 1} works on {@code vidocq.pool.url} through {@code java.sql} directly — no Mansart
 *       pool, exactly as a test that only wants a database uses it;</li>
 *   <li>the application boot sees the {@code devservices} startup report section, host {@code test}.</li>
 * </ol>
 *
 * <p>The report is read through the dev console snapshot, copying
 * {@code vidocq-runtime-mansart-h2-example}'s {@code DevConsoleSnapshotTest} approach: {@code
 * ExtensionContext.startupReport()} is only reachable from inside {@code vidocq-runtime-core}'s own
 * package, not from a test in a different module.
 *
 * <p>{@code @Order(1)} ({@code junit-platform.properties} makes it effective): this class must finish
 * reading the shared {@code target/vidocq-dev-services.json} before {@link DevServicesRunGoalIT}'s own
 * {@code mvn vidocq:run} subprocess overwrites it with its own host's run.
 */
@Order(1)
class DevServicesTestHostIT {

    private static final String CONSOLE_LOGGER = "io.vidocq.devconsole";
    private static final Pattern URL_RECORD = Pattern.compile("Vidocq dev console: (http://127\\.0\\.0\\.1:(\\d+)/)");

    /** Kept strongly: JUL holds its loggers weakly, and the handler would go with a collected one. */
    private static Logger consoleLogger;
    private static final List<LogRecord> RECORDS = new ArrayList<>();
    private static final Handler CAPTURE = new Handler() {
        @Override
        public void publish(LogRecord record) {
            synchronized (RECORDS) {
                RECORDS.add(record);
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    private static VidocqBootstrap bootstrap;
    /** The startup report's text, as the snapshot's {@code startup.text} field carries it. */
    private static String startupReportText;

    @BeforeAll
    static void bootAndReadTheStartupReport() throws Exception {
        consoleLogger = Logger.getLogger(CONSOLE_LOGGER);
        CAPTURE.setLevel(Level.ALL);
        consoleLogger.addHandler(CAPTURE);
        bootstrap = VidocqBootstrap.create().configure().start();
        String body = get(consoleUrl() + "api/snapshot");
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Map<?, ?> snapshot = (Map<?, ?>) jsonb.fromJson(body, Object.class);
            startupReportText = (String) ((Map<?, ?>) snapshot.get("startup")).get("text");
        }
    }

    @AfterAll
    static void shutDown() {
        try {
            if (bootstrap != null) {
                bootstrap.shutdown();
            }
        } finally {
            if (consoleLogger != null) {
                consoleLogger.removeHandler(CAPTURE);
            }
        }
    }

    @Test
    void select1WorksOnTheDatasourceTheListenerInjected() throws Exception {
        String url = System.getProperty("vidocq.pool.url");
        assertNotNull(url, "vidocq.pool.url: the JUnit host injects it before any test runs");
        try (Connection connection = DriverManager.getConnection(url,
                System.getProperty("vidocq.pool.username"), System.getProperty("vidocq.pool.password"));
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void theStartupReportHasTheDevservicesSectionForTheTestHost() {
        assertTrue(startupReportText.contains("postgres (postgres:16-alpine at localhost:"),
                "the devservices section summary: " + startupReportText);
        assertTrue(startupReportText.contains("— test"),
                "the JUnit host's name ('test') in the summary: " + startupReportText);
    }

    /** The URL the console logged. */
    private static String consoleUrl() {
        SimpleFormatter formatter = new SimpleFormatter();
        List<String> messages;
        synchronized (RECORDS) {
            messages = RECORDS.stream().filter(r -> r.getLevel() == Level.INFO).map(formatter::formatMessage).toList();
        }
        for (String message : messages) {
            Matcher record = URL_RECORD.matcher(message);
            if (record.matches()) {
                return record.group(1);
            }
        }
        return fail("no dev console URL on " + CONSOLE_LOGGER + ": " + messages);
    }

    /** The body of a {@code GET} that must answer 200. */
    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(10_000);
        try {
            assertEquals(200, connection.getResponseCode(), url);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
