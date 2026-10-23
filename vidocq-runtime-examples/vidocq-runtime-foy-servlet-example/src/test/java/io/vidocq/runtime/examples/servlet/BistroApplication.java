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
package io.vidocq.runtime.examples.servlet;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Bistro, running as a jlink image launches it: the runtime's own main with {@code --add-modules <module>
 * -Dvidocq.app.module=<module>}, in a child JVM on this test's module path and a free port. The runtime then builds
 * the application layer from that module, which reads the providers the processors listed in
 * {@code META-INF/services}. Booted in the test's JVM, the module would stay in the boot layer, where those files
 * count for nothing.
 */
final class BistroApplication {

    private static final String MODULE = "io.vidocq.runtime.examples.servlet";
    private static final String MAIN = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";

    private final Process process;
    private final Path log;
    private final String base;

    private BistroApplication(Process process, Path log, String base) {
        this.process = process;
        this.log = log;
        this.base = base;
    }

    /** Starts the application with {@code systemProperties} ({@code -Dname=value}) and waits until it listens. */
    static BistroApplication start(String logName, String... systemProperties) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String base = "http://127.0.0.1:" + port;
        Path log = Files.createTempFile(logName, ".log");
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
                "-Dvidocq.chappe.listener.default.host=127.0.0.1",
                "-Dvidocq.chappe.listener.default.port=" + port));
        command.addAll(List.of(systemProperties));
        command.addAll(List.of("-Dvidocq.app.module=" + MODULE,
                "-p", System.getProperty("jdk.module.path"), "--add-modules", MODULE, "-m", MAIN));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try (var _ = new Socket("127.0.0.1", port)) {
                return new BistroApplication(process, log, base);
            } catch (IOException _) {
                // Not listening yet: wait on the process, which also tells when it stopped.
                if (process.waitFor(100, TimeUnit.MILLISECONDS) || System.nanoTime() > deadline) {
                    fail("the application did not listen on " + base + ":\n" + Files.readString(log));
                }
            }
        }
    }

    /** {@code http://127.0.0.1:<port>}. */
    String base() {
        return base;
    }

    /** What the application wrote so far. */
    String log() throws IOException {
        return Files.readString(log);
    }

    /** Stops the application and deletes its log. */
    void stop() throws Exception {
        process.destroy();
        process.waitFor(10, TimeUnit.SECONDS);
        process.destroyForcibly();
        Files.deleteIfExists(log);
    }
}
