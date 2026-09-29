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

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This application in a dev launch, with the dev console on the module path as an ordinary dependency: the console
 * prints one URL, and the snapshot it serves there shows both Mansart pools and the JVM, and never a pool password.
 *
 * <p>The application boots in this JVM, on the module path, as the child JVM of {@code mvn vidocq:dev} starts it. The
 * surefire configuration of this module sets:
 * <ul>
 *   <li>{@code vidocq.launch.mode=dev}, the one launch in which the console's {@code auto} turns it on;</li>
 *   <li>{@code vidocq.devconsole.port=0}, so that the console takes a free port, and the application's own listener
 *       on {@code 127.0.0.1:0}, never 8080;</li>
 *   <li>a password for each pool, {@code @Default} and {@code audit}, as {@code -D} arguments on the command line,
 *       where a dev service puts the password it injects: the JVM's input arguments and its system properties then
 *       hold both, and the console must show neither.</li>
 * </ul>
 * The console's URL is read from its record on the {@code io.vidocq.devconsole} logger, the one a developer clicks.
 */
class DevConsoleSnapshotTest {

    /** The console's logger: its URL record. */
    private static final String CONSOLE_LOGGER = "io.vidocq.devconsole";
    /** The console's URL record, the URL last. */
    private static final Pattern URL_RECORD = Pattern.compile("Vidocq dev console: (http://127\\.0\\.0\\.1:(\\d+)/)");
    /** The pool passwords the surefire configuration passes on the command line. */
    private static final List<String> PASSWORD_KEYS = List.of("vidocq.pool.password", "vidocq.pool.audit.password");

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
    /** The raw body of {@code GET /api/snapshot}, read once the boot is over. */
    private static String body;
    /** The same body, parsed: maps, lists, strings, numbers and booleans. */
    private static Map<?, ?> snapshot;

    @BeforeAll
    static void bootInDevModeAndReadTheSnapshot() throws Exception {
        consoleLogger = Logger.getLogger(CONSOLE_LOGGER);
        CAPTURE.setLevel(Level.ALL);
        consoleLogger.addHandler(CAPTURE);
        bootstrap = VidocqBootstrap.create().configure().start();
        body = get(consoleUrl() + "api/snapshot", "application/json");
        try (Jsonb jsonb = JsonbBuilder.create()) {
            snapshot = (Map<?, ?>) jsonb.fromJson(body, Object.class);
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
    void printsOneClickableUrl() {
        List<String> urls = consoleMessages().stream().filter(m -> m.startsWith("Vidocq dev console: http")).toList();
        assertEquals(1, urls.size(), "one URL record: " + urls);
        Matcher record = URL_RECORD.matcher(urls.getFirst());
        assertTrue(record.matches(), "the URL last, nothing after its final slash: " + urls.getFirst());
        assertTrue(Integer.parseInt(record.group(2)) > 0, "the port the console bound, not the 0 it asked for");
    }

    @Test
    void servesThePageFromTheModulePath() throws IOException {
        // the page is a resource of the console's jar, outside any package: readable on the module path
        assertTrue(get(consoleUrl(), "text/html").contains("console.js"), "the page loads its module");
        assertTrue(get(consoleUrl() + "console.js", "text/javascript").contains("api/snapshot"),
                "the module polls the snapshot");
    }

    @Test
    void theSnapshotIsTheReportOfThisDevBoot() {
        assertEquals("ready", snapshot.get("state"), "the report of the boot is written once start() returns");
        Map<?, ?> startup = (Map<?, ?>) snapshot.get("startup");
        assertEquals("dev", startup.get("launchMode"));
        assertEquals(consoleUrl(), ((Map<?, ?>) snapshot.get("console")).get("url"));
    }

    @Test
    void showsTheTwoPoolsLive() {
        Map<?, ?> panel = panel("mansart-pool");
        List<?> groups = (List<?>) ((Map<?, ?>) panel.get("sample")).get("groups");
        assertEquals(List.of("@Default", "audit"), groups.stream().map(g -> ((Map<?, ?>) g).get("name")).toList(),
                "one group per pool, the unnamed one first");
        // the live pools of vidocq.properties: vidocq.pool.maxSize=8, vidocq.pool.audit.maxSize=4
        assertEquals(8, max(value((Map<?, ?>) groups.get(0), "active")));
        assertEquals(4, max(value((Map<?, ?>) groups.get(1), "active")));
    }

    @Test
    void showsTheHeapLive() {
        Map<?, ?> sample = (Map<?, ?>) panel("jvm").get("sample");
        Map<?, ?> heap = value(sample, "heap.used");
        assertEquals("gauge", heap.get("kind"));
        assertTrue(((Number) heap.get("value")).longValue() > 0, "some heap is in use: " + heap);
    }

    @Test
    void neverShowsAPoolPassword() {
        List<String> passwords = PASSWORD_KEYS.stream().map(DevConsoleSnapshotTest::password).toList();
        // both pools read their password: the console says so, and only so
        List<?> lines = (List<?>) panel("mansart-pool").get("lines");
        assertTrue(lines.contains(List.of("@Default password", "configured")), "lines: " + lines);
        assertTrue(lines.contains(List.of("audit password", "configured")), "lines: " + lines);
        // the detailed report is part of the document searched below
        assertTrue(((String) ((Map<?, ?>) snapshot.get("startup")).get("text")).contains("mansart-pool"),
                "the full report is in the snapshot");

        List<String> strings = new ArrayList<>();
        strings(snapshot, strings);
        for (String password : passwords) {
            assertFalse(body.contains(password), "the snapshot holds a pool password");
            for (String string : strings) {
                assertFalse(string.contains(password), "a string of the snapshot holds a pool password: " + string);
            }
        }
    }

    @Test
    void migratesFromTheConsoleAndRefusesToCleanByDefault() throws Exception {
        List<?> actions = (List<?>) panel("migration").get("actions");
        assertEquals(List.of("migrate", "clean-and-migrate"),
                actions.stream().map(a -> ((Map<?, ?>) a).get("id")).toList());
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        String migrated = postAction("migration/migrate", token, "{\"datasource\":\"default\"}");
        String refused = postAction("migration/clean-and-migrate", token, "{\"datasource\":\"default\"}");

        assertTrue(migrated.contains("default: 0 migrations applied, schema at version 1"), migrated);
        assertTrue(refused.contains("default: clean refused, nothing dropped; set vidocq.migration.cleanDisabled=false"
                + " to allow it"), refused);
        String after = get(consoleUrl() + "api/snapshot", "application/json");
        Map<?, ?> panel;
        try (Jsonb jsonb = JsonbBuilder.create()) {
            panel = panelOf((Map<?, ?>) jsonb.fromJson(after, Object.class), "migration");
        }
        Map<?, ?> group = (Map<?, ?>) ((List<?>) ((Map<?, ?>) panel.get("sample")).get("groups")).getFirst();
        assertEquals("default", group.get("name"));
        assertEquals("migrate: 0 applied", value(group, "last-run").get("value"));
        assertEquals("table", value(group, "applied").get("kind"), "listed after the action: " + group);
        for (String password : PASSWORD_KEYS.stream().map(DevConsoleSnapshotTest::password).toList()) {
            assertFalse(after.contains(password), "the snapshot after an action holds a pool password");
            assertFalse(migrated.contains(password) || refused.contains(password), "an action's answer does");
        }
    }

    /**
     * Runs ProductRepository's methods from the Mansart Data panel against the in-memory H2 database, as a developer
     * does from its page: a read, a save rolled back that leaves no row, a save committed that leaves one (then
     * deleted, so that the other tests see the seeded rows), and a JDQL UPDATE rolled back.
     */
    @Test
    void runsProductRepositoryMethodsFromTheConsole() throws Exception {
        List<?> actions = (List<?>) panel("mansart-data").get("actions");
        Map<?, ?> findById = actions.stream().map(a -> (Map<?, ?>) a)
                .filter(a -> "m.product-repository.find-by-id".equals(a.get("id"))).findFirst()
                .orElseGet(() -> fail("no findById action in " + actions));
        assertEquals("ProductRepository", findById.get("group"));
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        Map<?, ?> espresso = runProducts(token, "find-by-id", "{\"id\":1}", null);
        assertEquals("1 row", espresso.get("result"));
        assertEquals("Espresso", json((String) espresso.get("body")).get("name"));

        long before = countProducts(token);
        assertEquals("1 row · rolled back", runProducts(token, "save",
                "{\"entity\":{\"name\":\"Mocha\",\"price\":4.5}}", "rollback").get("result"));
        assertEquals(before, countProducts(token), "a save rolled back leaves no row");

        Map<?, ?> saved = runProducts(token, "save", "{\"entity\":{\"name\":\"Mocha\",\"price\":4.5}}", "commit");
        assertEquals("1 row · committed", saved.get("result"));
        assertEquals(before + 1, countProducts(token), "a save committed leaves one");
        long id = ((Number) json((String) saved.get("body")).get("id")).longValue();
        assertEquals("done · committed",
                runProducts(token, "delete-by-id", "{\"id\":" + id + "}", "commit").get("result"));
        assertEquals(before, countProducts(token));

        assertEquals("1 · rolled back", runProducts(token, "reprice",
                "{\"name\":\"Espresso\",\"price\":9.99}", "rollback").get("result"));
        assertEquals(2.5, ((Number) json((String) runProducts(token, "find-by-id", "{\"id\":1}", null)
                .get("body")).get("price")).doubleValue(), "the UPDATE was rolled back");
    }

    /**
     * Runs JDQL from the Mansart Data panel's JDQL tab against the in-memory H2 database, as a developer does from its
     * page: a query with a parameter given as a JSON number, then an UPDATE of every product rolled back, which leaves
     * every price as it was.
     */
    @Test
    void runsJdqlFromTheConsole() throws Exception {
        List<?> actions = (List<?>) panel("mansart-data").get("actions");
        Map<?, ?> query = actions.stream().map(a -> (Map<?, ?>) a)
                .filter(a -> "jdql.query".equals(a.get("id"))).findFirst()
                .orElseGet(() -> fail("no JDQL query action in " + actions));
        assertEquals("JDQL", query.get("group"));
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        Map<?, ?> dear = runJdql(token, "query",
                "{\"query\":\"FROM Product WHERE price > :min ORDER BY name\",\"params\":{\"min\":3}}", null);
        assertTrue(((String) dear.get("result")).matches("2 rows in \\d+ ms"), "result: " + dear);
        assertEquals(List.of("Cappuccino", "Latte"),
                rows((String) dear.get("body")).stream().map(r -> ((Map<?, ?>) r).get("name")).toList());

        long products = Long.parseLong((String) runJdql(token, "query",
                "{\"query\":\"SELECT COUNT(this) FROM Product\"}", null).get("result"));
        assertEquals(products + (products == 1 ? " row" : " rows") + " · rolled back", runJdql(token, "write",
                "{\"query\":\"UPDATE Product SET price = price * 2\"}", "rollback").get("result"));
        Map<?, ?> espresso = runJdql(token, "query", "{\"query\":\"FROM Product WHERE name = 'Espresso'\"}", null);
        assertEquals(2.5, ((Number) ((Map<?, ?>) rows((String) espresso.get("body")).getFirst()).get("price"))
                .doubleValue(), "the UPDATE was rolled back");
    }

    /**
     * Exports the three seeded products as CSV from the JDQL tab, then imports that CSV again with its ids emptied:
     * rolled back it leaves the table as it was, committed it adds one product per line — each with a new id — which
     * the test deletes again, so that the table ends as it began.
     */
    @Test
    void exportsAndImportsCsvFromTheConsole() throws Exception {
        List<?> actions = (List<?>) panel("mansart-data").get("actions");
        List<?> ids = actions.stream().map(a -> ((Map<?, ?>) a).get("id")).toList();
        assertTrue(ids.containsAll(List.of("jdql.export", "jdql.import")), "the CSV actions: " + ids);
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        Map<?, ?> export = runCsv(token, "export", Map.of("query", "FROM Product WHERE id <= 3 ORDER BY id"), null);
        assertEquals("text/csv", export.get("contentType"));
        String csv = (String) export.get("body");
        assertEquals("id,name,price\r\n1,Espresso,2.5\r\n2,Cappuccino,3.5\r\n3,Latte,4.0\r\n", csv);
        assertTrue(((String) export.get("result")).matches("3 rows · \\d+ B in \\d+ ms"), "result: " + export);

        String emptied = csv.replaceAll("(?m)^\\d+,", ",");
        long before = countProducts(token);
        long maxId = Long.parseLong((String) runJdql(token, "query", "{\"query\":\"SELECT MAX(id) FROM Product\"}",
                null).get("result"));

        assertEquals("3 rows saved · rolled back", runCsv(token, "import",
                Map.of("entity", "Product", "csv", emptied), "rollback").get("result"));
        assertEquals(before, countProducts(token), "an import rolled back leaves no row");

        assertEquals("3 rows saved · committed", runCsv(token, "import",
                Map.of("entity", "Product", "csv", emptied), "commit").get("result"));
        assertEquals(before + 3, countProducts(token), "an import committed adds a row per line, its id generated");

        assertEquals("3 rows · committed", runJdql(token, "write",
                "{\"query\":\"DELETE FROM Product WHERE id > :max\",\"params\":{\"max\":" + maxId + "}}", "commit")
                .get("result"));
        assertEquals(before, countProducts(token));
    }

    /**
     * Runs {@code jdql.<action>}, {@code export} with its {@code statement} or {@code import} with its {@code file},
     * both written as the page writes them; its answer, which must be no error.
     */
    private static Map<?, ?> runCsv(String token, String action, Map<String, String> argument, String transaction)
            throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        try (Jsonb jsonb = JsonbBuilder.create()) {
            body.put("export".equals(action) ? "statement" : "file", jsonb.toJson(argument));
            if (transaction != null) {
                body.put("transaction", transaction);
            }
            Map<?, ?> answer = json(postAction("mansart-data/jdql." + action, token, jsonb.toJson(body)));
            assertNotEquals(Boolean.TRUE, answer.get("error"), action + ": " + answer);
            return answer;
        }
    }

    /** Runs {@code jdql.<action>} with this statement; its answer, which must be no error. */
    private static Map<?, ?> runJdql(String token, String action, String statement, String transaction)
            throws Exception {
        String body = "{\"statement\":\"" + statement.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (transaction == null ? "" : ",\"transaction\":\"" + transaction + "\"") + "}";
        Map<?, ?> answer = json(postAction("mansart-data/jdql." + action, token, body));
        assertNotEquals(Boolean.TRUE, answer.get("error"), action + ": " + answer);
        return answer;
    }

    private static List<?> rows(String text) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return (List<?>) jsonb.fromJson(text, Object.class);
        }
    }

    /** Runs {@code m.product-repository.<method>} with these arguments; its answer, which must be no error. */
    private static Map<?, ?> runProducts(String token, String method, String arguments, String transaction)
            throws Exception {
        String body = "{\"arguments\":\"" + arguments.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (transaction == null ? "" : ",\"transaction\":\"" + transaction + "\"") + "}";
        Map<?, ?> answer = json(postAction("mansart-data/m.product-repository." + method, token, body));
        assertNotEquals(Boolean.TRUE, answer.get("error"), method + ": " + answer);
        return answer;
    }

    /** {@code ProductRepository.count()}, from the console. */
    private static long countProducts(String token) throws Exception {
        return Long.parseLong((String) runProducts(token, "count", "{}", null).get("result"));
    }

    private static Map<?, ?> json(String text) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return (Map<?, ?>) jsonb.fromJson(text, Object.class);
        }
    }

    /**
     * Sends the console an action request as its own page does: JSON, its own {@code Origin}, the token of the boot.
     * {@code Origin} is a restricted header of {@link HttpURLConnection}: the surefire configuration lets it through.
     *
     * @return the body of the answer, which must be 200
     */
    private static String postAction(String action, String token, String body) throws IOException {
        String console = consoleUrl();
        HttpURLConnection connection = (HttpURLConnection) URI.create(console + "api/action/" + action).toURL()
                .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Origin", console.substring(0, console.length() - 1));
        connection.setRequestProperty("X-Vidocq-Console-Token", token);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, connection.getResponseCode(), action);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The URL the console logged. */
    private static String consoleUrl() {
        for (String message : consoleMessages()) {
            Matcher record = URL_RECORD.matcher(message);
            if (record.matches()) {
                return record.group(1);
            }
        }
        return fail("no dev console URL on " + CONSOLE_LOGGER + ": " + consoleMessages());
    }

    private static List<String> consoleMessages() {
        SimpleFormatter formatter = new SimpleFormatter();
        synchronized (RECORDS) {
            return RECORDS.stream().filter(r -> r.getLevel() == Level.INFO).map(formatter::formatMessage).toList();
        }
    }

    /** A password the surefire configuration set, so that its absence below means something. */
    private static String password(String key) {
        String password = System.getProperty(key);
        assertNotNull(password, key + " is set on the command line by the surefire configuration");
        assertTrue(password.length() >= 8, key + " is long enough to be found only where it leaks");
        return password;
    }

    private static Map<?, ?> panel(String id) {
        return panelOf(snapshot, id);
    }

    private static Map<?, ?> panelOf(Map<?, ?> snapshot, String id) {
        for (Object panel : (List<?>) snapshot.get("panels")) {
            if (id.equals(((Map<?, ?>) panel).get("id"))) {
                return (Map<?, ?>) panel;
            }
        }
        return fail("no panel '" + id + "' in " + body);
    }

    /** The value {@code key} of a sample or of one of its groups. */
    private static Map<?, ?> value(Map<?, ?> sampleOrGroup, String key) {
        for (Object value : (List<?>) sampleOrGroup.get("values")) {
            if (key.equals(((Map<?, ?>) value).get("key"))) {
                return (Map<?, ?>) value;
            }
        }
        return fail("no value '" + key + "' in " + sampleOrGroup);
    }

    /** The maximum of a gauge, whichever {@link Number} JSON-B read it as. */
    private static long max(Map<?, ?> gauge) {
        return ((Number) gauge.get("max")).longValue();
    }

    /** Every string of a parsed document, the member names included. */
    private static void strings(Object node, List<String> out) {
        switch (node) {
            case Map<?, ?> map -> map.forEach((name, member) -> {
                out.add(String.valueOf(name));
                strings(member, out);
            });
            case List<?> list -> list.forEach(element -> strings(element, out));
            case String string -> out.add(string);
            case null, default -> {
                // numbers, booleans and nulls hold no text
            }
        }
    }

    /** The body of a {@code GET} that must answer 200 with {@code contentType}. */
    private static String get(String url, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(10_000);
        try {
            assertEquals(200, connection.getResponseCode(), url);
            assertTrue(connection.getContentType().startsWith(contentType), url + ": " + connection.getContentType());
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
