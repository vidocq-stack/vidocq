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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.core.config.ConfigKeyAudit;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeEngineExtension;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeServerBootstrap;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console in its lifecycle, on real Chappe servers bound to free loopback ports: when it is on, the URL it
 * prints and when it prints it again, the port it keeps across dev reloads, a taken port, the anomalies of its section,
 * and what it answers on the wire.
 */
class DevConsoleExtensionTest {

    /** What outlives a dev reload, for this test only. */
    private final ConsoleMemory memory = new ConsoleMemory();
    private final List<Boot> boots = new ArrayList<>();
    private LogRecords log;

    /** One boot of the console, with the Chappe extensions around it. */
    private record Boot(DevConsoleExtension console, ChappeServerBootstrap bootstrap, FakeExtensionContext context) {

        void stop() {
            bootstrap.onStop();
            console.onStop();
        }

        /** The console's section of the report, written as the core would, after every {@code onStart}. */
        RecordingSection section() {
            RecordingSection section = new RecordingSection(console.id(), console.title());
            console.contribute(new ConsoleReportContext(context.launchMode()), section);
            return section;
        }
    }

    @BeforeEach
    void captureTheConsoleLog() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
    }

    @AfterEach
    void stopEverything() {
        for (Boot boot : boots) {
            boot.stop();
        }
        log.close();
    }

    /** A context of {@code mode} whose application listener binds a free loopback port, never 8080. */
    private static FakeExtensionContext context(LaunchMode mode, String... keysAndValues) {
        List<String> all = new ArrayList<>(List.of(
                "vidocq.chappe.listener.default.host", "127.0.0.1",
                "vidocq.chappe.listener.default.port", "0"));
        all.addAll(List.of(keysAndValues));
        return FakeExtensionContext.of(mode, all.toArray(String[]::new));
    }

    /** Boots the Chappe engine, the console and the Chappe servers, in their priority order. */
    private Boot boot(FakeExtensionContext context) {
        new ChappeEngineExtension().configure(null);
        Boot boot = new Boot(new DevConsoleExtension(memory), new ChappeServerBootstrap(), context);
        boots.add(boot);
        boot.console().onStart(context);
        boot.bootstrap().onStart(context);
        return boot;
    }

    private Boot reload(Boot previous, FakeExtensionContext context) {
        previous.stop();
        boots.remove(previous);
        return boot(context);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String url(int port) {
        return "http://127.0.0.1:" + port + "/";
    }

    private static List<Object> panelIds(Map<String, Object> snapshot) {
        return ((List<?>) snapshot.get("panels")).stream().<Object>map(panel -> ((Map<?, ?>) panel).get("id"))
                .toList();
    }

    /** The live threads named {@value DevConsoleExtension#TICKER}, whatever group they run in. */
    private static List<Thread> tickers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> DevConsoleExtension.TICKER.equals(thread.getName()) && thread.isAlive())
                .toList();
    }

    @Test
    void theHistoryTicksWhileTheConsoleRunsAndStopsWithIt() throws Exception {
        assertEquals(List.of(), tickers(), "nothing ticks before a console starts");

        Boot boot = boot(context(LaunchMode.DEV));

        assertEquals(1, tickers().size(), "one thread, named so that a thread dump says whose it is");
        assertTrue(tickers().get(0).isDaemon(), "a daemon: the history must never hold a JVM open");

        // It fills on its own, with no page asking for anything: that is the whole point of moving it here.
        Map<String, Object> filled = Json.object(get(url(boot.console().boundPort()) + "api/snapshot").body());
        List<?> panels = (List<?>) filled.get("panels");
        boolean ticked = false;
        for (int attempt = 0; attempt < 40 && !ticked; attempt++) {
            filled = Json.object(get(url(boot.console().boundPort()) + "api/snapshot").body());
            panels = (List<?>) filled.get("panels");
            ticked = panels.stream().anyMatch(panel -> !((List<?>) ((Map<?, ?>) panel).get("history")).isEmpty());
            if (!ticked) {
                Thread.sleep(100);
            }
        }
        assertTrue(ticked, "the jvm panel has points, and nobody asked for them");

        boot.stop();
        boots.remove(boot);

        assertEquals(List.of(), tickers(), "and onStop joins it: a dev reload must not leave one ticking per boot");
    }

    @Test
    void aDevReloadLeavesExactlyOneTicker() throws Exception {
        Boot first = boot(context(LaunchMode.DEV));
        assertEquals(1, tickers().size());

        Boot second = reload(first, context(LaunchMode.DEV));

        assertEquals(1, tickers().size(), "the previous boot's thread was joined before this one started");
        second.stop();
        boots.remove(second);
        assertEquals(List.of(), tickers());
    }

    @Test
    void aConsoleThatIsOffTicksNothing() {
        boot(context(LaunchMode.PROD));

        assertEquals(List.of(), tickers(), "no console, no thread and no history to keep");
    }

    @Test
    void aDevBootListensOnLoopbackAndPrintsItsUrl() throws Exception {
        FakeExtensionContext context = context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY, "0");
        Boot boot = boot(context);

        int port = boot.console().boundPort();
        assertNotEquals(0, port);
        assertEquals(List.of("Vidocq dev console: " + url(port)), log.messages(Level.INFO));

        HttpResponse<String> booting = get(url(port) + "api/snapshot");
        assertEquals(200, booting.statusCode());
        Map<String, Object> first = Json.object(booting.body());
        assertEquals("booting", first.get("state"));
        assertEquals(List.of("cdi", "jvm"), panelIds(first), "the console's own panels, from the first poll");
        Map<?, ?> cdi = (Map<?, ?>) ((List<?>) first.get("panels")).get(0);
        assertEquals("CDI (Vauban)", cdi.get("title"));
        assertEquals("not available: no container", cdi.get("summary"), "the test's context has no container");
        Map<?, ?> jvm = (Map<?, ?>) ((List<?>) first.get("panels")).get(1);
        assertEquals("JVM", jvm.get("title"));
        assertEquals(true, jvm.get("live"));
        assertTrue(((List<?>) ((Map<?, ?>) jvm.get("sample")).get("values")).stream()
                .anyMatch(value -> "heap.used".equals(((Map<?, ?>) value).get("key"))), jvm.toString());

        context.writeReport(FakeReportView.of(boot.console(), new TestPanels.PoolPanel()));
        Map<String, Object> ready = Json.object(get(url(port) + "api/snapshot").body());
        assertEquals("ready", ready.get("state"));
        assertEquals(url(port), ((Map<?, ?>) ready.get("console")).get("url"));
        assertEquals(List.of("acme-pool", "cdi", "jvm"), panelIds(ready),
                "the contributed panels, then the CDI container, then the JVM last");

        ReportSection section = boot.section().toSection();
        assertEquals(url(port), section.summary());
        assertEquals(List.of(), boot.section().anomalies());
    }

    @Test
    void thePageIsServedWithItsHeaders() throws Exception {
        Boot boot = boot(context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY, "0"));

        // a page of the tests' own, next to where the console's page lives
        HttpResponse<String> page = get(url(boot.console().boundPort()) + "probe.html");

        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("dev console test page"), page.body());
        assertEquals("no-cache", page.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("default-src 'self'; frame-ancestors 'none'",
                page.headers().firstValue("Content-Security-Policy").orElse(null));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(null));
    }

    @Test
    void theConsolesPageIsItsIndexWithItsScriptAndStyle() throws Exception {
        Boot boot = boot(context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY, "0"));
        String root = url(boot.console().boundPort());

        HttpResponse<String> index = get(root);
        HttpResponse<String> script = get(root + "console.js");
        HttpResponse<String> style = get(root + "console.css");

        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), index.headers()
                .toString());
        assertTrue(index.body().contains("<meta charset=\"utf-8\">"), index.body());
        assertTrue(index.body().contains("<title>Vidocq dev console</title>"), index.body());
        assertEquals(200, script.statusCode());
        assertTrue(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"),
                "a module script with nosniff needs a JavaScript type: " + script.headers());
        assertEquals(200, style.statusCode());
        assertTrue(style.headers().firstValue("Content-Type").orElse("").startsWith("text/css"),
                style.headers().toString());
        assertEquals("no-cache", script.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void aForeignHostIsForbiddenOnTheWire() throws Exception {
        Boot boot = boot(context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY, "0"));
        int port = boot.console().boundPort();

        assertEquals("HTTP/1.1 403 Forbidden", statusLine(port, "evil.example.com:" + port));
        assertEquals("HTTP/1.1 200 OK", statusLine(port, "localhost:" + port));
    }

    /** The status line of a snapshot request carrying {@code host}, which the JDK client would not send. */
    private static String statusLine(int port, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/snapshot HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            for (int c = in.read(); c != -1 && c != '\r'; c = in.read()) {
                line.append((char) c);
            }
            return line.toString();
        }
    }

    @Test
    void aReloadKeepsAFreePortAndPrintsTheUrlOnlyWhenItChanges() throws Exception {
        FakeExtensionContext context = context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY, "0");
        Boot first = boot(context);
        int port = first.console().boundPort();

        Boot second = reload(first, context);

        assertEquals(port, second.console().boundPort(), "port 0 asks for the port the previous boot bound");
        assertEquals(List.of("Vidocq dev console: " + url(port)), log.messages(Level.INFO),
                "the same URL is not printed again");
        second.stop();
        boots.remove(second);

        try (ServerSocket taken = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))) {
            Boot third = boot(context);

            int moved = third.console().boundPort();
            assertNotEquals(port, moved);
            assertEquals(List.of("Vidocq dev console: " + url(port), "Vidocq dev console: " + url(moved)),
                    log.messages(Level.INFO), "a new URL is printed");
            assertEquals(List.of(), log.messages(Level.WARNING), "port 0 asked for any port: nothing was taken");
            assertEquals(List.of(), third.section().anomalies());
        }
    }

    @Test
    void aTakenPortMovesTheConsoleAndSaysSoLoudly() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int configured = taken.getLocalPort();
            FakeExtensionContext context = context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY,
                    String.valueOf(configured));
            Boot boot = boot(context);

            int bound = boot.console().boundPort();
            assertNotEquals(configured, bound);
            assertEquals(List.of("Vidocq dev console: " + url(bound)), log.messages(Level.INFO));

            List<String> warnings = log.messages(Level.WARNING);
            assertEquals(1, warnings.size(), warnings.toString());
            List<String> box = warnings.get(0).lines().toList();
            assertEquals("Vidocq dev console: port " + configured + " is taken, listening on port " + bound
                    + " instead", box.get(0));
            List<String> frame = box.subList(1, box.size());
            assertTrue(frame.stream().allMatch(line -> line.length() == frame.get(0).length()), warnings.get(0));
            assertTrue(frame.get(0).matches("\\+-+\\+"), frame.get(0));
            assertTrue(frame.get(frame.size() - 1).matches("\\+-+\\+"), frame.get(frame.size() - 1));
            String inside = String.join("\n", frame);
            assertTrue(inside.contains("port " + configured + " is taken"), inside);
            assertTrue(inside.contains("port " + bound + " instead"), inside);
            assertTrue(inside.contains(url(bound)), inside);

            List<ReportAnomaly> anomalies = boot.section().anomalies();
            assertEquals(1, anomalies.size(), anomalies.toString());
            assertEquals("VIDOCQ-DEVC-004", anomalies.get(0).code());
            assertEquals("The dev console's port " + configured + " is taken: it listens on port " + bound
                    + " instead, " + url(bound), anomalies.get(0).message());

            context.writeReport(FakeReportView.of(boot.console()));
            Map<?, ?> console = (Map<?, ?>) Json.object(get(url(bound) + "api/snapshot").body()).get("console");
            assertEquals(Map.of("configured", (long) configured, "bound", (long) bound), console.get("portTaken"));
        }
    }

    @Test
    void theConsoleIsOffOutsideADevLaunchByDefault() {
        Boot boot = boot(context(LaunchMode.PROD));

        assertEquals(0, boot.console().boundPort());
        assertEquals(List.of(), log.messages());
        assertEquals("off (auto, launch mode prod)", boot.section().toSection().summary());
        assertEquals(List.of(), boot.section().anomalies());
    }

    @Test
    void forcedOnOutsideDevItWarnsWithItsAddress() {
        Boot boot = boot(context(LaunchMode.PROD, DevConsoleSettings.ENABLED_KEY, "true",
                DevConsoleSettings.PORT_KEY, "0"));
        int port = boot.console().boundPort();

        List<ReportAnomaly> anomalies = boot.section().anomalies();

        assertEquals(List.of("VIDOCQ-DEVC-001"), anomalies.stream().map(ReportAnomaly::code).toList());
        assertEquals("The dev console is on in a prod launch: it shows the startup report and the live values of "
                + "this application on 127.0.0.1:" + port, anomalies.get(0).message());
        assertEquals("Remove vidocq.devconsole.enabled=true outside development", anomalies.get(0).hint());
        assertEquals("devconsole", anomalies.get(0).source());
    }

    @Test
    void aNonLoopbackBindWarnsInAnyMode() throws Exception {
        new ChappeEngineExtension().configure(null);
        DevConsoleExtension console = new DevConsoleExtension(memory);
        try {
            FakeExtensionContext context = context(LaunchMode.DEV, DevConsoleSettings.HOST_KEY, "0.0.0.0");
            console.onStart(context);

            console.bound(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 8888));
            RecordingSection section = new RecordingSection(console.id(), console.title());
            console.contribute(new ConsoleReportContext(LaunchMode.DEV), section);

            assertEquals(List.of("Vidocq dev console: http://localhost:8888/"), log.messages(Level.INFO));
            assertEquals(List.of("VIDOCQ-DEVC-002"),
                    section.anomalies().stream().map(ReportAnomaly::code).toList());
            assertEquals("The dev console listens on 0.0.0.0:8888, which is not a loopback address: whoever reaches "
                    + "this machine can read the startup report and the live values of this application",
                    section.anomalies().get(0).message());
        } finally {
            new ChappeServerBootstrap().onStop();
            console.onStop();
        }
    }

    @Test
    void anInvalidValueIsReportedAndItsDefaultUsed() {
        // an invalid port would bind 8888: DevConsoleSettingsTest covers it without a server
        Boot boot = boot(context(LaunchMode.DEV, DevConsoleSettings.ENABLED_KEY, "yes",
                DevConsoleSettings.HOST_KEY, "a host", DevConsoleSettings.PORT_KEY, "0"));

        List<ReportAnomaly> anomalies = boot.section().anomalies();

        assertEquals(List.of("VIDOCQ-DEVC-003", "VIDOCQ-DEVC-003"),
                anomalies.stream().map(ReportAnomaly::code).toList());
        assertEquals("Invalid value 'yes' for vidocq.devconsole.enabled (auto, true, false): using auto",
                anomalies.get(0).message());
        assertEquals("Invalid value 'a host' for vidocq.devconsole.host (a host name or an IP address): using "
                + "127.0.0.1", anomalies.get(1).message());
        assertEquals(List.of("Vidocq dev console: " + url(boot.console().boundPort())), log.messages(Level.INFO),
                "on, as auto in a dev launch, on the loopback address");
    }

    @Test
    void itsKeysAndItsPlace() {
        DevConsoleExtension console = new DevConsoleExtension();

        assertEquals("devconsole", console.name());
        assertEquals("devconsole", console.id());
        assertEquals("Dev console", console.title());
        assertEquals(9000, console.priority());
        assertEquals(Set.of("vidocq.devconsole.enabled", "vidocq.devconsole.port", "vidocq.devconsole.host"),
                console.configKeys());
        RecordingSection section = new RecordingSection(console.id(), console.title());
        console.contribute(new ConsoleReportContext(LaunchMode.DEV), section);
        assertEquals("not started", section.toSection().summary());
        assertEquals("Dev console", section.toSection().headline());
    }

    @Test
    void aMistypedKeyIsReportedByTheKeyAudit() {
        Set<String> declared = new DevConsoleExtension().configKeys();

        assertEquals(List.of("vidocq.devconsole.prot"), ConfigKeyAudit.unconsumedKeys(List.of(
                "vidocq.devconsole.enabled", "vidocq.devconsole.port", "vidocq.devconsole.host",
                "vidocq.devconsole.prot"), declared));
        assertEquals("[VIDOCQ-CFG-003] Configuration key 'vidocq.devconsole.prot' is read by nothing and has no "
                + "effect. Known keys in this namespace: vidocq.devconsole.enabled, vidocq.devconsole.host, "
                + "vidocq.devconsole.port", ConfigKeyAudit.warningFor("vidocq.devconsole.prot", declared));
    }
}
