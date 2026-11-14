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
package io.vidocq.runtime.it.lc4jcdimcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The MCP application of this module in a JVM of its own, in one launch shape, on two loopback listeners: one for the
 * MCP endpoint, one for the dev console, which is forced on so that a test can read its {@code /api/snapshot}. Both
 * bind port {@code 0}, and the ports they got are read from the lines the application logs once they listen: ports
 * picked beforehand, then closed and bound by the child JVM, could be taken in between (BUG-20261114-09). Its output
 * goes to a log file under {@code target/it-logs}.
 */
final class LaunchedServer implements AutoCloseable {

    /** How the JVM is launched. */
    enum Shape {
        /** The generated {@code bin/*.sh} with {@code @VidocqMain}: one module path, every module resolved. */
        FLAT,
        /** {@code vidocq:dev} and {@code vidocq:run}: the application alone in {@code -Dvidocq.app.path}. */
        APP_PATH,
        /** An IDE run of the main class: one module path, without {@code --add-modules ALL-MODULE-PATH}. */
        IDE
    }

    static final String APP_MODULE = "io.vidocq.runtime.it.lc4jcdimcp";
    private static final String APP_MAIN = APP_MODULE + "/" + APP_MODULE + ".McpItApp";
    private static final long BOOT_TIMEOUT_MS = 60_000;
    /** The line Chappe logs once the MCP endpoint's listener listens, with the port it bound. */
    private static final Pattern STARTED = Pattern.compile("Chappe listener 'default' started on http://[^/\\s]+:(\\d+)/");
    /** The line the dev console logs once it listens, with the port it bound. */
    private static final Pattern CONSOLE = Pattern.compile("Vidocq dev console: http://[^/\\s]+:(\\d+)/");

    private final Process process;
    private final Path log;
    private int port;
    private int consolePort;

    private LaunchedServer(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    /**
     * Starts the application and waits until it logged {@code Vidocq - Started in} and the ports its listeners bound.
     *
     * @param shape      the launch shape
     * @param name       the name of the log file, without extension
     * @param properties extra {@code -D} options, such as {@code -Dvidocq.mcp.serverName=it-server}
     */
    static LaunchedServer start(Shape shape, String name, String... properties) throws Exception {
        String lib = System.getProperty("it.lib");
        String app = System.getProperty("it.app");
        Path logs = Path.of(System.getProperty("it.logs"));
        Files.createDirectories(logs);
        Path log = logs.resolve(name + ".log");

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        switch (shape) {
            case FLAT -> command.addAll(List.of("--module-path", lib + File.pathSeparator + app,
                    "--add-modules", "ALL-MODULE-PATH"));
            case APP_PATH -> command.addAll(List.of("--module-path", lib, "--add-modules", "ALL-MODULE-PATH",
                    "-Dvidocq.app.path=" + app));
            case IDE -> command.addAll(List.of("--module-path", lib + File.pathSeparator + app));
        }
        command.add("-Dvidocq.chappe.listener.default.host=127.0.0.1");
        command.add("-Dvidocq.chappe.listener.default.port=0");
        command.add("-Dvidocq.startup.report=detailed");
        // The dev console, on its own loopback port: an IT reads its /api/snapshot. Never 8888, its default.
        command.add("-Dvidocq.devconsole.enabled=true");
        command.add("-Dvidocq.devconsole.host=127.0.0.1");
        command.add("-Dvidocq.devconsole.port=0");
        command.addAll(List.of(properties));
        if (shape == Shape.APP_PATH) {
            command.addAll(List.of("-m", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq"));
        } else {
            command.addAll(List.of("--module", APP_MAIN));
        }

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        LaunchedServer server = new LaunchedServer(process, log);
        server.awaitStarted();
        return server;
    }

    /** The base URL of the MCP endpoint. */
    String mcpUrl() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    /** The URL of the dev console's snapshot, the JSON its page polls. */
    String snapshotUrl() {
        return "http://127.0.0.1:" + consolePort + "/api/snapshot";
    }

    /** Everything the JVM printed so far. */
    String log() throws IOException {
        return Files.readString(log, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private void awaitStarted() throws Exception {
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String printed = log();
            Matcher started = STARTED.matcher(printed);
            Matcher console = CONSOLE.matcher(printed);
            if (printed.contains("Vidocq - Started in") && started.find() && console.find()) {
                port = Integer.parseInt(started.group(1));
                consolePort = Integer.parseInt(console.group(1));
                return;
            }
            if (!process.isAlive()) {
                throw new AssertionError("The server exited with " + process.exitValue() + " before it started:\n"
                        + log());
            }
            Thread.sleep(100);
        }
        close();
        throw new AssertionError("The server did not start within " + BOOT_TIMEOUT_MS + " ms:\n" + log());
    }
}
