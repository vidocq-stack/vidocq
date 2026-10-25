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
package io.vidocq.runtime.junit;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The application a {@link VidocqTest} class runs against: a JVM of its own, listening on the loopback address.
 * A test method declares it as a parameter for its URL, its output, or to send it requests; {@link VidocqUrl} gives
 * the URL alone.
 *
 * <p>The requests go through this jar because a test that Surefire or the IDE patches into the application module
 * reads what that module reads, and an application module does not read {@code java.net.http}.</p>
 */
public final class VidocqApplication implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** How long a failed start waits for the last lines of the process's output. */
    private static final Duration DRAIN = Duration.ofSeconds(5);

    private final Process process;
    private final URI url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    /** The process's output and error streams, kept in memory: no temporary file to share or clean. */
    private final StringBuilder output = new StringBuilder();
    private final Thread reader;

    private VidocqApplication(Process process, URI url) {
        this.process = process;
        this.url = url;
        this.reader = Thread.ofVirtual().name("vidocq-test-output").start(this::readOutput);
    }

    /**
     * Runs {@code command}, its output in memory, and returns once something listens on {@code 127.0.0.1:port}.
     * Fails with the output when the process exits first, or when {@code timeout} runs out, after stopping it.
     */
    static VidocqApplication start(List<String> command, int port, Duration timeout) throws IOException {
        URI url = URI.create("http://127.0.0.1:" + port);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        VidocqApplication application = new VidocqApplication(process, url);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!listens(port)) {
                if (process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                    application.reader.join(DRAIN);
                    throw new IllegalStateException("The application stopped with exit code " + process.exitValue()
                            + " before it listened on " + url + ". Its output:\n" + application.log());
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("The application did not listen on " + url + " within "
                            + timeout.toSeconds() + " s. Its output:\n" + application.log());
                }
            }
            return application;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            application.close();
            throw new IllegalStateException("Interrupted while the application started", e);
        } catch (RuntimeException e) {
            application.close();
            throw e;
        }
    }

    /** A port free on the loopback address at the time of the call. */
    static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static boolean listens(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), (int) POLL.toMillis());
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /** {@code http://127.0.0.1:<port>}. */
    public URI url() {
        return url;
    }

    /** {@code path} on the application, with or without its leading {@code /}. */
    public URI url(String path) {
        return url.resolve(path.startsWith("/") ? path : "/" + path);
    }

    /** {@code GET path}. */
    public VidocqResponse get(String path) {
        return request("GET", path, null, null);
    }

    /** {@code POST path} with {@code body} of type {@code contentType}. */
    public VidocqResponse post(String path, String contentType, String body) {
        return request("POST", path, contentType, body);
    }

    /**
     * {@code method path}, with {@code body} of type {@code contentType} when {@code body} is not {@code null}.
     * The body of the answer is read as UTF-8.
     */
    public VidocqResponse request(String method, String path, String contentType, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path)).timeout(REQUEST_TIMEOUT)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null && contentType != null) {
            request.header("Content-Type", contentType);
        }
        try {
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, List<String>> headers = new TreeMap<>();
            response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
            return new VidocqResponse(response.statusCode(), response.body(), headers);
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + url(path) + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during " + method + " " + url(path), e);
        }
    }

    /** What the application wrote so far, on its output and error streams. */
    public String log() {
        synchronized (output) {
            return output.toString();
        }
    }

    private void readOutput() {
        char[] buffer = new char[8192];
        try (Reader in = process.inputReader()) {
            for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                synchronized (output) {
                    output.append(buffer, 0, n);
                }
            }
        } catch (IOException _) {
            // the process ended: its output is closed
        }
    }

    /** Whether the process still runs. */
    public boolean isAlive() {
        return process.isAlive();
    }

    /** Stops the application, as Ctrl+C would, then forcibly after a grace period. */
    @Override
    public void close() {
        http.close();
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
