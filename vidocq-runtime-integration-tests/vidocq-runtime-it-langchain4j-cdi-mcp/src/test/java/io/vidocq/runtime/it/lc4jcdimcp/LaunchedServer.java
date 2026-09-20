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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The MCP application of this module in a JVM of its own, in one launch shape, bound to two free loopback ports of
 * 18090-18099: one for the MCP endpoint, one for the dev console, which is forced on so that a test can read its
 * {@code /api/snapshot}. Its output goes to a log file under {@code target/it-logs}.
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
    private static final int FIRST_PORT = 18090;
    private static final int LAST_PORT = 18099;
    private static final long BOOT_TIMEOUT_MS = 60_000;

    private final Process process;
    private final Path log;
    private final int port;
    private final int consolePort;

    private LaunchedServer(Process process, Path log, int port, int consolePort) {
        this.process = process;
        this.log = log;
        this.port = port;
        this.consolePort = consolePort;
    }

    /**
     * Starts the application and waits until it logged {@code Vidocq - Started in}.
     *
     * @param shape      the launch shape
     * @param name       the name of the log file, without extension
     * @param properties extra {@code -D} options, such as {@code -Dvidocq.mcp.serverName=it-server}
     */
    static LaunchedServer start(Shape shape, String name, String... properties) throws Exception {
        int[] ports = freePorts(2);
        int port = ports[0];
        int consolePort = ports[1];
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
        command.add("-Dvidocq.chappe.listener.default.port=" + port);
        command.add("-Dvidocq.startup.report=detailed");
        // The dev console, on its own loopback port: an IT reads its /api/snapshot. Never 8888, its default.
        command.add("-Dvidocq.devconsole.enabled=true");
        command.add("-Dvidocq.devconsole.host=127.0.0.1");
        command.add("-Dvidocq.devconsole.port=" + consolePort);
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
        LaunchedServer server = new LaunchedServer(process, log, port, consolePort);
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
            if (log().contains("Vidocq - Started in")) {
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

    /**
     * The first {@code count} ports of 18090-18099 nothing listens on; never another one. Every probe is held open
     * until they are all found, so that two calls of one launch never get the same port.
     *
     * @param count how many distinct ports are needed
     */
    private static int[] freePorts(int count) throws IOException {
        int[] found = new int[count];
        List<ServerSocket> probes = new ArrayList<>();
        try {
            for (int port = FIRST_PORT; port <= LAST_PORT && probes.size() < count; port++) {
                ServerSocket probe = new ServerSocket();
                try {
                    probe.setReuseAddress(false);
                    probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    found[probes.size()] = port;
                    probes.add(probe);
                } catch (IOException taken) {
                    probe.close();
                }
            }
            if (probes.size() < count) {
                throw new IOException("Fewer than " + count + " free ports in " + FIRST_PORT + "-" + LAST_PORT);
            }
        } finally {
            for (ServerSocket probe : probes) {
                probe.close();
            }
        }
        return found;
    }
}
